package com.otoniel.nightplayer.player

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Avance/retroceso progresivo con el mando y miniatura del punto de destino, al estilo de
 * las apps de TV:
 *
 *  - Cada pulsación de ←/→ mueve un "destino" 10 s; si se mantiene pulsada, el salto crece
 *    (10 s → 30 s → 60 s) para cruzar una peli larga en pocos segundos.
 *  - Al empezar, la peli se pausa y se congela de fondo la escena actual ([freezeFrame]).
 *  - El reproductor salta, por debajo de esa imagen congelada, al fotograma clave más
 *    cercano al destino (se decodifica rápido). En cuanto se pinta, se copia en pequeño al
 *    recuadro sobre la barra de tiempo ([thumbnail]), junto a la hora y cuánto se avanza.
 *    Si un salto aún no ha terminado no se pide otro (solo el último destino), para no
 *    saturar la conexión con el servidor.
 *  - Al dejar de pulsar un momento o con OK ([commit]) salta al punto exacto y sigue
 *    reproduciendo; con Atrás ([cancel]) vuelve a donde estaba.
 *
 * Por qué así: una miniatura "de verdad" exigiría un segundo decodificador de vídeo leyendo el
 * mismo stream, y la tele no puede con dos HEVC/Dolby Vision a la vez. Aquí se usa el único
 * decodificador y PixelCopy copia lo que pinta. Si PixelCopy fallara, se ve el destino a
 * pantalla completa (sin fondo congelado ni miniatura).
 */
class ScrubController(
    private val preview: View,
    private val targetText: TextView,
    private val deltaText: TextView,
    private val thumbnail: ImageView,
    /** Imagen a pantalla completa sobre el vídeo y bajo los controles. */
    private val freezeFrame: ImageView,
    /** La superficie donde ExoPlayer pinta el vídeo. */
    private val videoSurface: () -> SurfaceView?,
    /** La barra de tiempo de los controles (puede no existir o no estar visible aún). */
    private val timeBar: () -> View?,
) {
    private var player: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    var isScrubbing = false
        private set

    private var startPositionMs = 0L
    private var targetMs = 0L
    private var lastStepAtMs = 0L
    private var wasPlaying = false

    /** Los saltos de vista previa esperan a tener congelada la escena de fondo. */
    private var freezeReady = false

    /** Hay un salto de vista previa en curso (esperando a que se pinte su fotograma). */
    private var previewSeekInFlight = false
    private var previewedTargetMs = C.TIME_UNSET

    // Bitmap del fondo congelado, reutilizado entre avances.
    private var freezeBitmap: Bitmap? = null

    /**
     * Miniaturas ya vistas, por posición de destino (ms): volver a pasar por una zona es
     * instantáneo (sin pedir nada al servidor). LinkedHashMap en "orden de acceso" = caché LRU;
     * las que se expulsan se reutilizan para no crear bitmaps nuevos.
     */
    private val thumbCache = object : LinkedHashMap<Long, Bitmap>(THUMB_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>): Boolean {
            if (size <= THUMB_CACHE_SIZE) return false
            spareThumbs.add(eldest.value)
            return true
        }
    }
    private val spareThumbs = ArrayDeque<Bitmap>()
    private var previewSeekStartedAt = 0L

    private val commitRunnable = Runnable { commit() }
    private val repositionRunnable = Runnable { positionPreview() }
    private val captureThumbnailRunnable = Runnable { captureThumbnail() }
    private val unfreezeRunnable = Runnable { if (!isScrubbing) hideFreezeFrame() }

    /** Si un salto de vista previa no avisa (red lenta, error), se deja pedir otro. */
    private val previewSeekTimeout = Runnable { onPreviewFrameShown() }

    private val playerListener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            if (!isScrubbing || !previewSeekInFlight) return
            // onRenderedFirstFrame avisa al enviar el fotograma a la pantalla; se espera un
            // instante para que PixelCopy lo encuentre ya pintado.
            mainHandler.removeCallbacks(captureThumbnailRunnable)
            mainHandler.postDelayed(captureThumbnailRunnable, CAPTURE_DELAY_MS)
        }
    }

    init {
        // Si el recuadro cambia de tamaño, se recoloca antes de pintarse (sin saltos visibles).
        preview.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (isScrubbing) positionPreview() }
    }

    fun attach(newPlayer: ExoPlayer?) {
        cancel()
        hideFreezeFrame()
        thumbCache.clear()
        spareThumbs.clear()
        player?.removeListener(playerListener)
        player = newPlayer
        newPlayer?.addListener(playerListener)
    }

    /** Un paso adelante o atrás. Devuelve false si el vídeo no admite saltos (p. ej. directo). */
    fun step(forward: Boolean, event: KeyEvent): Boolean {
        val p = player ?: return false
        val durationMs = p.duration.takeIf { it != C.TIME_UNSET && p.isCurrentMediaItemSeekable } ?: return false

        // Manteniendo pulsado (repeticiones cada ~50 ms) solo se avanza cuando ha llegado la
        // miniatura del paso anterior: así cada imagen que se ve corresponde a la hora del
        // recuadro y no se salta ninguna. Las pulsaciones sueltas avanzan siempre.
        if (event.repeatCount > 0) {
            if (previewSeekInFlight || event.eventTime - lastStepAtMs < HELD_STEP_INTERVAL_MS) return true
        }
        lastStepAtMs = event.eventTime

        if (!isScrubbing) start(p)
        val stepMs = stepFor(heldMs = event.eventTime - event.downTime)
        // Hasta 1 s antes del final: llegar al final exacto daría la peli por terminada.
        val lastMs = (durationMs - END_MARGIN_MS).coerceAtLeast(0L)
        targetMs = (targetMs + if (forward) stepMs else -stepMs).coerceIn(0L, lastMs)
        requestPreviewFrame()

        targetText.text = formatTime(targetMs)
        deltaText.text = formatDelta(targetMs - startPositionMs)
        preview.visibility = View.VISIBLE
        positionPreview()
        // Los controles pueden estar apareciendo (animación) o el recuadro crecer al llegar la
        // primera miniatura: se recoloca un poco después.
        mainHandler.removeCallbacks(repositionRunnable)
        mainHandler.postDelayed(repositionRunnable, REPOSITION_DELAY_MS)

        mainHandler.removeCallbacks(commitRunnable)
        mainHandler.postDelayed(commitRunnable, COMMIT_DELAY_MS)
        return true
    }

    /** Salta al punto exacto del destino y sigue reproduciendo si se estaba reproduciendo. */
    fun commit() {
        if (isScrubbing) finishAt(targetMs)
    }

    /** Descarta el avance: vuelve a donde estaba. */
    fun cancel() {
        if (isScrubbing) finishAt(startPositionMs)
    }

    private fun start(p: ExoPlayer) {
        isScrubbing = true
        mainHandler.removeCallbacks(unfreezeRunnable)
        startPositionMs = p.currentPosition
        targetMs = startPositionMs
        wasPlaying = p.playWhenReady
        p.pause()
        // Saltos al fotograma clave más cercano: mucho más rápidos que al punto exacto.
        p.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        previewSeekInFlight = false
        previewedTargetMs = C.TIME_UNSET
        reserveThumbnailSpace()
        freezeReady = false
        captureFreezeFrame()
    }

    /**
     * El hueco de la miniatura se reserva desde el principio con la proporción del vídeo (y un
     * fondo gris mientras llega la primera), para que el recuadro no cambie de tamaño ni salte.
     */
    private fun reserveThumbnailSpace() {
        val surface = validSurface()
        if (surface == null) {
            thumbnail.visibility = View.GONE
            return
        }
        val params = thumbnail.layoutParams
        val height = (params.width.toFloat() * surface.height / surface.width).roundToInt()
        if (params.height != height) {
            params.height = height
            thumbnail.layoutParams = params
        }
        thumbnail.setImageDrawable(null)
        thumbnail.visibility = View.VISIBLE
    }

    private fun finishAt(positionMs: Long) {
        isScrubbing = false
        mainHandler.removeCallbacks(commitRunnable)
        mainHandler.removeCallbacks(repositionRunnable)
        mainHandler.removeCallbacks(previewSeekTimeout)
        mainHandler.removeCallbacks(captureThumbnailRunnable)
        preview.visibility = View.GONE
        thumbnail.visibility = View.GONE
        thumbnail.setImageDrawable(null)
        player?.let { p ->
            p.setSeekParameters(SeekParameters.DEFAULT)
            p.seekTo(positionMs)
            if (wasPlaying) p.play()
        }
        // El fondo congelado se quita un poco después, cuando el vídeo ya muestra el destino
        // exacto; si no, se vería un instante el último fotograma clave de la vista previa.
        mainHandler.postDelayed(unfreezeRunnable, UNFREEZE_DELAY_MS)
    }

    private fun hideFreezeFrame() {
        freezeFrame.visibility = View.GONE
        freezeFrame.setImageDrawable(null)
    }

    /** Copia la escena actual al fondo congelado; después empiezan los saltos de vista previa. */
    private fun captureFreezeFrame() {
        val surface = validSurface()
        if (surface == null) {
            freezeReady = true
            return
        }
        val bitmap = reusableBitmap(freezeBitmap, surface, FREEZE_WIDTH_PX).also { freezeBitmap = it }
        PixelCopy.request(surface, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) {
                if (isScrubbing) {
                    freezeFrame.setImageBitmap(bitmap)
                    freezeFrame.visibility = View.VISIBLE
                }
            } else {
                Log.w(TAG, "PixelCopy no disponible ($result): vista previa a pantalla completa")
                thumbnail.visibility = View.GONE
            }
            freezeReady = true
            if (isScrubbing) requestPreviewFrame()
        }, mainHandler)
    }

    /** Copia el fotograma de destino recién pintado a la miniatura del recuadro (y a la caché). */
    private fun captureThumbnail() {
        val surface = validSurface()
        if (surface == null || freezeFrame.visibility != View.VISIBLE) {
            onPreviewFrameShown()
            return
        }
        val bitmap = obtainThumbBitmap(surface)
        val shownTargetMs = previewedTargetMs
        Log.d(TAG, "Miniatura de $previewedTargetMs ms tras ${SystemClock.elapsedRealtime() - previewSeekStartedAt} ms")
        PixelCopy.request(surface, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) {
                thumbCache[shownTargetMs] = bitmap
                if (isScrubbing) showThumbnail(bitmap)
            } else {
                spareThumbs.add(bitmap)
            }
            onPreviewFrameShown()
        }, mainHandler)
    }

    private fun showThumbnail(bitmap: Bitmap) {
        thumbnail.setImageBitmap(bitmap)
        thumbnail.visibility = View.VISIBLE
    }

    /** Un bitmap libre del tamaño de miniatura; si el vídeo cambió de proporción, vacía la caché. */
    private fun obtainThumbBitmap(surface: SurfaceView): Bitmap {
        val height = (THUMB_WIDTH_PX.toFloat() * surface.height / surface.width).roundToInt().coerceAtLeast(1)
        val anyCached = thumbCache.values.firstOrNull() ?: spareThumbs.firstOrNull()
        if (anyCached != null && anyCached.height != height) {
            thumbCache.clear()
            spareThumbs.clear()
        }
        return spareThumbs.removeFirstOrNull() ?: Bitmap.createBitmap(THUMB_WIDTH_PX, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Miniatura guardada más cercana a [positionMs], si está a menos de [THUMB_CACHE_TOLERANCE_MS].
     * La miniatura es el fotograma clave más próximo (puede variar unos segundos), así que una
     * de un destino muy cercano es igual de buena.
     */
    private fun cachedThumbnailNear(positionMs: Long): Bitmap? {
        val nearest = thumbCache.keys.minByOrNull { abs(it - positionMs) } ?: return null
        if (abs(nearest - positionMs) > THUMB_CACHE_TOLERANCE_MS) return null
        return thumbCache[nearest] // el acceso la marca como usada recientemente (LRU)
    }

    private fun validSurface(): SurfaceView? =
        videoSurface()?.takeIf { it.width > 0 && it.holder.surface?.isValid == true }

    /** Pide el fotograma del destino, salvo que ya haya uno en camino. */
    private fun requestPreviewFrame() {
        val p = player ?: return
        if (!freezeReady || previewSeekInFlight || targetMs == previewedTargetMs) return
        val cached = cachedThumbnailNear(targetMs)
        if (cached != null && freezeFrame.visibility == View.VISIBLE) {
            // Ya vista: se muestra al instante, sin saltar el vídeo.
            previewedTargetMs = targetMs
            showThumbnail(cached)
            return
        }
        previewSeekInFlight = true
        previewedTargetMs = targetMs
        previewSeekStartedAt = SystemClock.elapsedRealtime()
        p.seekTo(targetMs)
        mainHandler.removeCallbacks(previewSeekTimeout)
        mainHandler.postDelayed(previewSeekTimeout, PREVIEW_SEEK_TIMEOUT_MS)
    }

    /** El fotograma pedido ya está: si el destino ha cambiado entretanto, se pide el nuevo. */
    private fun onPreviewFrameShown() {
        if (!previewSeekInFlight) return
        previewSeekInFlight = false
        mainHandler.removeCallbacks(previewSeekTimeout)
        if (isScrubbing) requestPreviewFrame()
    }

    /** Bitmap de [width] px de ancho con la proporción del vídeo; reutiliza [current] si vale. */
    private fun reusableBitmap(current: Bitmap?, surface: SurfaceView, width: Int): Bitmap {
        val height = (width.toFloat() * surface.height / surface.width).roundToInt().coerceAtLeast(1)
        if (current != null && current.width == width && current.height == height) return current
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    /** Coloca el recuadro encima de la barra de tiempo, en la posición del destino. */
    private fun positionPreview() {
        val parent = preview.parent as? View ?: return
        val durationMs = player?.duration?.takeIf { it > 0 } ?: return
        val bar = timeBar()?.takeIf { it.isShown && it.width > 0 }
        if (preview.width == 0) {
            // Aún sin medir: se recoloca en el siguiente frame.
            mainHandler.post(repositionRunnable)
            return
        }
        val margin = preview.resources.displayMetrics.density * 16
        if (bar == null || parent !is ViewGroup) {
            // Sin barra visible: centrado en la parte baja de la pantalla.
            preview.x = (parent.width - preview.width) / 2f
            preview.y = parent.height - preview.height - margin * 6
            return
        }
        // Posición de la barra según el layout, sin las traslaciones de la animación con la que
        // aparecen los controles: así el recuadro no sube y baja mientras se animan.
        val barRect = Rect(0, 0, bar.width, bar.height)
        parent.offsetDescendantRectToMyCoords(bar, barRect)
        val barX = barRect.left
        val barY = barRect.top
        val fraction = targetMs.toFloat() / durationMs
        val centerX = barX + fraction * bar.width
        preview.x = (centerX - preview.width / 2f).coerceIn(margin, parent.width - preview.width - margin)
        preview.y = barY - preview.height - margin / 2
    }

    private companion object {
        const val TAG = "NightPlayer"
        const val COMMIT_DELAY_MS = 1_200L
        const val CAPTURE_DELAY_MS = 80L
        const val UNFREEZE_DELAY_MS = 400L
        const val PREVIEW_SEEK_TIMEOUT_MS = 3_000L
        const val END_MARGIN_MS = 1_000L
        const val HELD_STEP_INTERVAL_MS = 150L
        const val REPOSITION_DELAY_MS = 350L
        const val FREEZE_WIDTH_PX = 1280
        const val THUMB_WIDTH_PX = 480 // 240 dp a densidad 2,0
        const val THUMB_CACHE_SIZE = 24
        const val THUMB_CACHE_TOLERANCE_MS = 3_000L

        /** Al mantener pulsado se avanza una vez por miniatura, así que los pasos crecen. */
        fun stepFor(heldMs: Long): Long = when {
            heldMs < 1_500 -> 10_000L
            heldMs < 4_000 -> 30_000L
            heldMs < 8_000 -> 60_000L
            else -> 120_000L
        }

        fun formatTime(ms: Long): String {
            val totalSeconds = ms / 1000
            val h = totalSeconds / 3600
            val m = (totalSeconds % 3600) / 60
            val s = totalSeconds % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }

        fun formatDelta(deltaMs: Long): String {
            val sign = if (deltaMs < 0) "−" else "+"
            return sign + formatTime(abs(deltaMs))
        }
    }
}
