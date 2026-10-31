package com.otoniel.nightplayer

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import com.otoniel.nightplayer.audio.AudioProfile
import com.otoniel.nightplayer.audio.LiveAudioProfile
import com.otoniel.nightplayer.player.NightModeRenderersFactory
import com.otoniel.nightplayer.player.ScrubController
import com.otoniel.nightplayer.settings.ProfileStore
import com.otoniel.nightplayer.settings.TrackPreferences
import com.otoniel.nightplayer.settings.TrackPreferencesStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Locale

/**
 * Pantalla del reproductor.
 *
 * Una Activity es una "ventana" con ciclo de vida gestionado por Android
 * (onCreate → onStart → … → onStop → onDestroy). El reproductor se crea en onStart y se
 * libera en onStop: si el usuario pulsa Inicio, la tele recupera el decodificador de vídeo
 * y, al volver, seguimos desde la misma posición.
 *
 * Si se abre desde el inicio de la tele (sin vídeo), solo muestra cómo configurar Stremio.
 */
@UnstableApi
class PlayerActivity : ComponentActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var messageView: TextView
    private lateinit var titleView: TextView
    private lateinit var profileToast: TextView
    private var profileButton: TextView? = null
    private lateinit var scrubber: ScrubController

    private lateinit var profileStore: ProfileStore
    private lateinit var trackPreferencesStore: TrackPreferencesStore
    private var trackPreferences = TrackPreferences(audioLanguage = null, subtitleLanguage = null)
    private var savedTrackParameters: TrackSelectionParameters? = null
    private var lastTrackParameters: TrackSelectionParameters? = null
    private var pendingAudioChange = false
    private var pendingTextChange = false
    private val liveProfile = LiveAudioProfile()

    private var request: PlaybackRequest? = null
    private var player: ExoPlayer? = null

    // Estado que sobrevive a onStop/onStart (p. ej. al pulsar Inicio y volver).
    private var resumePositionMs: Long? = null
    private var resumePlayWhenReady = true
    private var lastKnownDurationMs: Long = C.TIME_UNSET
    private var resultIsFinal = false

    /** Tecla cuya pulsación ha gestionado un atajo nuestro (ver dispatchKeyEvent). */
    private var consumedKeyCode = KeyEvent.KEYCODE_UNKNOWN

    private val hideProfileToast = Runnable { profileToast.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        messageView = findViewById(R.id.message)
        titleView = findViewById(R.id.title)
        profileToast = findViewById(R.id.profile_toast)

        // Leer el perfil guardado bloqueando un instante: es un fichero diminuto y así el
        // audio empieza ya con el perfil correcto, sin un salto de volumen al arrancar.
        profileStore = ProfileStore(this)
        trackPreferencesStore = TrackPreferencesStore(this)
        runBlocking {
            liveProfile.current = profileStore.load()
            trackPreferences = trackPreferencesStore.load()
        }

        IntentParser.logIntent(intent)
        val parsed = IntentParser.parse(intent)
        request = parsed
        if (parsed == null) {
            // Abierta desde el inicio de la tele: nada que reproducir.
            playerView.visibility = View.GONE
            messageView.setText(R.string.launcher_hint)
            messageView.visibility = View.VISIBLE
            return
        }

        resumePositionMs = parsed.startPositionMs
        titleView.text = parsed.title
        setUpControls()

        // Atrás: cancela un avance en curso o, si no hay, vuelve a Stremio a la primera (aunque
        // los controles estén a la vista). Va aquí y no en dispatchKeyEvent porque en Android 13+
        // el sistema puede entregar Atrás directamente a este callback ("atrás predictivo").
        onBackPressedDispatcher.addCallback(this) {
            if (scrubber.isScrubbing) scrubber.cancel() else finishWithResult(END_BY_USER)
        }
    }

    override fun onStart() {
        super.onStart()
        if (request != null) initializePlayer()
    }

    override fun onPause() {
        super.onPause()
        // Se prepara el resultado ya: si Android destruye la app en segundo plano,
        // Stremio recibe igualmente la última posición.
        if (!resultIsFinal && request != null) setResult(RESULT_OK, buildResult(END_BY_USER))
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    // ---------------------------------------------------------------- Reproductor

    private fun initializePlayer() {
        val req = request ?: return
        if (player != null) return

        // Fuente HTTP: el servidor local de Stremio puede redirigir de http a https y viceversa.
        // (Probado OkHttp para acelerar los saltos: no mejora; manda la latencia del servidor.)
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("NightPlayer")
            .setAllowCrossProtocolRedirects(true)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)

        val newPlayer = ExoPlayer.Builder(this, NightModeRenderersFactory(this, liveProfile))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .build()

        // Al volver de Inicio se conservan las pistas elegidas en esta sesión; si no, se parte
        // de los idiomas recordados. Se fija antes de añadir el listener para que no cuente
        // como un cambio hecho por el usuario.
        newPlayer.trackSelectionParameters =
            savedTrackParameters ?: initialTrackParameters(newPlayer.trackSelectionParameters)
        lastTrackParameters = newPlayer.trackSelectionParameters

        newPlayer.addListener(playerListener)
        newPlayer.addAnalyticsListener(diagnosticsListener)

        val mediaItem = buildMediaItem(req)
        val start = resumePositionMs
        if (start != null) newPlayer.setMediaItem(mediaItem, start) else newPlayer.setMediaItem(mediaItem)
        newPlayer.playWhenReady = resumePlayWhenReady
        newPlayer.prepare()

        playerView.player = newPlayer
        scrubber.attach(newPlayer)
        player = newPlayer
    }

    private fun releasePlayer() {
        val p = player ?: return
        resumePositionMs = p.currentPosition
        resumePlayWhenReady = p.playWhenReady
        savedTrackParameters = p.trackSelectionParameters
        rememberDuration(p)
        scrubber.attach(null)
        playerView.player = null
        p.release()
        player = null
    }

    private fun buildMediaItem(req: PlaybackRequest): MediaItem {
        val subtitleConfigs = req.subtitles.map { sub ->
            MediaItem.SubtitleConfiguration.Builder(sub.uri)
                .setMimeType(guessSubtitleMimeType(sub.uri))
                .setLabel(sub.name)
                .setSelectionFlags(if (sub.enabled) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        return MediaItem.Builder()
            .setUri(req.uri)
            .setMimeType(guessVideoMimeType(req.mimeType))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(req.title).build())
            .setSubtitleConfigurations(subtitleConfigs)
            .build()
    }

    /**
     * Parámetros de selección de pistas al empezar:
     *  - Sin audio offload: con offload el audio lo procesa el hardware y nuestra cadena no lo vería.
     *  - Audio: el último idioma elegido y, si no existe en la peli, el idioma de la tele.
     *  - Subtítulos: el último idioma elegido; si se quitaron, solo los "forzados" (carteles,
     *    diálogos en otro idioma). Si nunca se eligieron, el criterio por defecto de ExoPlayer.
     */
    private fun initialTrackParameters(defaults: TrackSelectionParameters): TrackSelectionParameters {
        val builder = defaults.buildUpon()
            .setAudioOffloadPreferences(
                AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED)
                    .build(),
            )
        val audioLanguages = listOfNotNull(trackPreferences.audioLanguage, Locale.getDefault().language).distinct()
        builder.setPreferredAudioLanguages(*audioLanguages.toTypedArray())
        when (val subtitles = trackPreferences.subtitleLanguage) {
            null -> Unit
            TrackPreferences.SUBTITLES_OFF -> builder.setIgnoredTextSelectionFlags(C.SELECTION_FLAG_FORCED.inv())
            else -> builder.setPreferredTextLanguage(subtitles)
        }
        Log.i(TAG, "Preferencias de pistas: audio=$audioLanguages subtítulos=${trackPreferences.subtitleLanguage}")
        return builder.build()
    }

    /** Guarda el idioma de las pistas que el usuario acaba de cambiar en el menú. */
    private fun rememberSelectedTracks(tracks: Tracks, audioChanged: Boolean, textChanged: Boolean) {
        val audio = if (audioChanged) {
            tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }?.selectedLanguage()
        } else {
            null
        }
        val subtitles = if (textChanged) {
            tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }?.selectedLanguage()
                ?: TrackPreferences.SUBTITLES_OFF
        } else {
            null
        }
        trackPreferences = TrackPreferences(
            audioLanguage = audio ?: trackPreferences.audioLanguage,
            subtitleLanguage = subtitles ?: trackPreferences.subtitleLanguage,
        )
        Log.i(TAG, "Pistas elegidas: audio=$audio subtítulos=$subtitles")
        lifecycleScope.launch {
            audio?.let { trackPreferencesStore.saveAudioLanguage(it) }
            subtitles?.let { trackPreferencesStore.saveSubtitleLanguage(it) }
        }
    }

    private fun TrackSelectionParameters.overridesOf(type: Int) =
        overrides.values.filter { it.type == type }.toSet()

    private fun TrackSelectionParameters.textSettings() = listOf(
        overridesOf(C.TRACK_TYPE_TEXT),
        ignoredTextSelectionFlags,
        preferredTextLanguages,
        C.TRACK_TYPE_TEXT in disabledTrackTypes,
    )

    private fun Tracks.Group.selectedLanguage(): String? =
        (0 until length).firstOrNull { isTrackSelected(it) }?.let { getTrackFormat(it).language }
            ?.takeIf { it != C.LANGUAGE_UNDETERMINED }

    private val playerListener = object : Player.Listener {
        /**
         * Solo cambia por el menú de pistas (lo nuestro se fija antes de añadir el listener).
         * Se anota qué tipo de pista cambió; el idioma real se lee cuando ExoPlayer aplica la
         * selección (onTracksChanged).
         */
        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
            val previous = lastTrackParameters
            lastTrackParameters = parameters
            if (previous == null) return
            pendingAudioChange = pendingAudioChange ||
                previous.overridesOf(C.TRACK_TYPE_AUDIO) != parameters.overridesOf(C.TRACK_TYPE_AUDIO)
            pendingTextChange = pendingTextChange || previous.textSettings() != parameters.textSettings()
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (pendingAudioChange || pendingTextChange) {
                rememberSelectedTracks(tracks, pendingAudioChange, pendingTextChange)
                pendingAudioChange = false
                pendingTextChange = false
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val p = player ?: return
            if (playbackState == Player.STATE_READY) rememberDuration(p)
            if (playbackState == Player.STATE_ENDED) finishWithResult(END_BY_COMPLETION)
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Error de reproducción (${error.errorCodeName})", error)
            messageView.text = getString(R.string.playback_error, error.errorCodeName, error.message ?: "")
            messageView.visibility = View.VISIBLE
            // Sin controles encima del mensaje; Atrás vuelve directamente a Stremio.
            playerView.hideController()
            playerView.useController = false
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) messageView.visibility = View.GONE
        }
    }

    /** Solo para Logcat: qué decodificador se usa y si hay cortes (underruns) de audio. */
    private val diagnosticsListener = object : AnalyticsListener {
        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            Log.i(
                TAG,
                "Pista de audio: ${format.sampleMimeType} ${format.channelCount} canales " +
                    "${format.sampleRate} Hz idioma=${format.language}",
            )
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            Log.i(TAG, "Decodificador de audio: $decoderName")
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            Log.i(TAG, "Decodificador de vídeo: $decoderName")
        }

        override fun onAudioUnderrun(
            eventTime: AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long,
        ) {
            Log.w(TAG, "Underrun de audio: buffer=${bufferSizeMs} ms, ${elapsedSinceLastFeedMs} ms sin datos")
        }
    }

    // ---------------------------------------------------------------- Mando y controles

    private fun setUpControls() {
        playerView.setShowNextButton(false)
        playerView.setShowPreviousButton(false)
        // Sin botones de ±10 s en el centro: ←/→ ya avanzan (ver handleShortcut) y así el
        // único botón central es play/pausa.
        playerView.setShowRewindButton(false)
        playerView.setShowFastForwardButton(false)
        playerView.setShowSubtitleButton(true)
        playerView.setControllerShowTimeoutMs(CONTROLLER_TIMEOUT_MS)
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility -> titleView.visibility = visibility },
        )

        // Botón de perfil dentro de la barra de controles de Media3, junto a subtítulos y ajustes
        // (los ajustes ya incluyen la elección de pista de audio).
        val basicControls = playerView.findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_basic_controls)
        if (basicControls != null) {
            val button = LayoutInflater.from(this)
                .inflate(R.layout.profile_button, basicControls, false) as TextView
            button.setOnClickListener { setProfile(liveProfile.current.next()) }
            basicControls.addView(button, 0)
            profileButton = button
        }
        updateProfileButton()

        val timeBar = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
        // Por si la barra recibe el foco y una tecla llega a ella: pasos de 10 s, no de 1/20 de la peli.
        (timeBar as? DefaultTimeBar)?.setKeyTimeIncrement(SEEK_INCREMENT_MS)
        // Escena congelada durante el avance: va en la capa "overlay" del PlayerView, que está
        // encima del vídeo y debajo de los controles.
        val freezeFrame = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        playerView.overlayFrameLayout?.addView(
            freezeFrame,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        scrubber = ScrubController(
            preview = findViewById(R.id.scrub_preview),
            targetText = findViewById(R.id.scrub_target),
            deltaText = findViewById(R.id.scrub_delta),
            thumbnail = findViewById(R.id.scrub_thumbnail),
            freezeFrame = freezeFrame,
            videoSurface = { playerView.videoSurfaceView as? SurfaceView },
            timeBar = { timeBar },
        )
    }

    /** True si el foco está en la fila de botones de abajo (perfil, CC, ajustes...). */
    private fun focusInButtonRow(): Boolean {
        val bottomBar = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_bottom_bar) ?: return false
        var view: View? = currentFocus
        while (view != null) {
            if (view === bottomBar) return true
            view = view.parent as? View
        }
        return false
    }

    /**
     * Atajos del mando. Se interceptan aquí, antes de que la vista de controles los vea:
     *  - Menú: cambia de perfil de audio.
     *  - ←/→: avance progresivo con recuadro de destino (ver ScrubController).
     *  - OK: confirma el avance o pausa/reanuda.
     * Con el foco en la fila de botones de abajo (↓ para llegar), ←/→/OK funcionan como
     * siempre: mover el foco y pulsar el botón. ↑/↓ los gestiona el PlayerView.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player ?: return super.dispatchKeyEvent(event)

        // Cada tecla llega dos veces: al pulsar (DOWN) y al soltar (UP). Si gestionamos el
        // DOWN, también hay que tragarse su UP; si no, el UP llegaría a los controles y, por
        // ejemplo, pulsaría el botón que acaba de recibir el foco.
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == consumedKeyCode) {
            consumedKeyCode = KeyEvent.KEYCODE_UNKNOWN
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            Log.d(
                TAG,
                "Tecla ${KeyEvent.keyCodeToString(event.keyCode)} " +
                    "controlesVisibles=${playerView.isControllerFullyVisible} pos=${p.currentPosition}",
            )
            if (handleShortcut(event, p)) {
                consumedKeyCode = event.keyCode
                return true
            }
        }
        return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    /** Devuelve true si la pulsación era un atajo nuestro (y ya está gestionada). */
    private fun handleShortcut(event: KeyEvent, p: Player): Boolean {
        val firstPress = event.repeatCount == 0
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (firstPress) setProfile(liveProfile.current.next())
            return true
        }
        if (playerView.isControllerFullyVisible && focusInButtonRow()) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                playerView.showController()
                if (!scrubber.step(forward = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT, event)) return false
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (firstPress) {
                if (scrubber.isScrubbing) {
                    scrubber.commit()
                } else if (p.isPlaying) {
                    p.pause()
                } else {
                    p.play()
                }
                playerView.showController()
            }
            else -> return false
        }
        return true
    }

    private fun setProfile(profile: AudioProfile) {
        liveProfile.current = profile // el hilo de audio lo aplica en el siguiente buffer
        Log.i(TAG, "Perfil de audio: ${profile.name}")
        updateProfileButton()
        profileToast.text = getString(R.string.profile_toast, getString(profile.labelRes))
        profileToast.visibility = View.VISIBLE
        profileToast.removeCallbacks(hideProfileToast)
        profileToast.postDelayed(hideProfileToast, TOAST_DURATION_MS)
        lifecycleScope.launch { profileStore.save(profile) }
    }

    private fun updateProfileButton() {
        val label = getString(liveProfile.current.labelRes)
        profileButton?.text = getString(R.string.profile_button, label)
        profileButton?.contentDescription = getString(R.string.profile_toast, label)
    }

    // ---------------------------------------------------------------- Vuelta a Stremio

    /**
     * Devuelve a quien nos abrió (Stremio) la posición y la duración, con la convención de
     * MX Player (position/duration en ms como Int, end_by) y la de VLC (extra_position /
     * extra_duration como Long). Si Stremio no lee el resultado, simplemente se ignora.
     */
    private fun finishWithResult(endBy: String) {
        if (resultIsFinal) return
        resultIsFinal = true
        setResult(RESULT_OK, buildResult(endBy))
        // Silencio inmediato: el reproductor se libera en onStop, que Android puede retrasar
        // hasta que Stremio vuelve a estar en pantalla, y mientras tanto se seguiría oyendo.
        player?.pause()
        finish()
    }

    private fun buildResult(endBy: String): Intent {
        val p = player
        if (p != null) rememberDuration(p)
        val duration = lastKnownDurationMs.takeIf { it != C.TIME_UNSET } ?: 0L
        val position = when {
            endBy == END_BY_COMPLETION -> duration
            p != null -> p.currentPosition
            else -> resumePositionMs ?: 0L
        }
        Log.i(TAG, "Resultado para la app de origen: position=$position duration=$duration end_by=$endBy")
        return Intent(RESULT_ACTION).apply {
            data = request?.uri ?: Uri.EMPTY
            putExtra(EXTRA_POSITION, position.toInt())
            putExtra(EXTRA_DURATION, duration.toInt())
            putExtra(EXTRA_END_BY, endBy)
            putExtra(EXTRA_VLC_POSITION, position)
            putExtra(EXTRA_VLC_DURATION, duration)
        }
    }

    private fun rememberDuration(p: Player) {
        if (p.duration != C.TIME_UNSET) lastKnownDurationMs = p.duration
    }

    private companion object {
        const val TAG = "NightPlayer"
        const val SEEK_INCREMENT_MS = 10_000L
        const val CONTROLLER_TIMEOUT_MS = 5_000
        const val TOAST_DURATION_MS = 2_000L

        const val RESULT_ACTION = "com.mxtech.intent.result.VIEW"
        const val EXTRA_POSITION = "position"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_END_BY = "end_by"
        const val EXTRA_VLC_POSITION = "extra_position"
        const val EXTRA_VLC_DURATION = "extra_duration"
        const val END_BY_USER = "user"
        const val END_BY_COMPLETION = "playback_completion"

        /** Solo damos pista a ExoPlayer para HLS/DASH; MKV y MP4 los detecta solo. */
        fun guessVideoMimeType(intentType: String?): String? {
            val type = intentType?.lowercase() ?: return null
            return when {
                "mpegurl" in type -> MimeTypes.APPLICATION_M3U8
                "dash" in type -> MimeTypes.APPLICATION_MPD
                else -> null
            }
        }

        /**
         * ExoPlayer necesita el tipo de cada subtítulo externo. Se deduce por la extensión
         * (en la ruta o en la query). Si no hay pista, WebVTT: es lo que suele servir el
         * servidor local de Stremio (hipótesis a confirmar con el log).
         */
        fun guessSubtitleMimeType(uri: Uri): String {
            val text = uri.toString().lowercase()
            return when {
                ".srt" in text -> MimeTypes.APPLICATION_SUBRIP
                ".ass" in text || ".ssa" in text -> MimeTypes.TEXT_SSA
                ".ttml" in text || ".dfxp" in text -> MimeTypes.APPLICATION_TTML
                else -> MimeTypes.TEXT_VTT
            }
        }
    }
}
