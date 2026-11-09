package com.otoniel.nightplayer

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import androidx.media3.common.text.Cue
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.otoniel.nightplayer.audio.AudioProfile
import com.otoniel.nightplayer.audio.LiveAudioProfile
import com.otoniel.nightplayer.player.AudioOutput
import com.otoniel.nightplayer.player.AudioOutputs
import com.otoniel.nightplayer.player.LiveAudioSync
import com.otoniel.nightplayer.player.NightModeRenderersFactory
import com.otoniel.nightplayer.player.ScrubController
import com.otoniel.nightplayer.settings.AudioSyncStore
import com.otoniel.nightplayer.settings.CaptionStyle
import com.otoniel.nightplayer.settings.CaptionStyleStore
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
    private var syncButton: TextView? = null
    private lateinit var syncPanel: TextView
    private lateinit var scrubber: ScrubController

    private lateinit var captionStyleStore: CaptionStyleStore
    private var captionStyle = CaptionStyle()
    private lateinit var captionPanel: TextView
    private var captionButton: TextView? = null
    private var adjustingCaptions = false
    private var captionRow = 0

    private lateinit var profileStore: ProfileStore
    private lateinit var trackPreferencesStore: TrackPreferencesStore
    private var trackPreferences = TrackPreferences(audioLanguage = null, subtitleLanguage = null)
    private var savedTrackParameters: TrackSelectionParameters? = null
    private var lastTrackParameters: TrackSelectionParameters? = null
    private var pendingAudioChange = false
    private var pendingTextChange = false
    private val liveProfile = LiveAudioProfile()

    private lateinit var audioManager: AudioManager
    private lateinit var audioSyncStore: AudioSyncStore
    private val liveSync = LiveAudioSync()
    private var audioOutput: AudioOutput? = null
    private var adjustingSync = false

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
    private val closeSyncAdjustRunnable = Runnable { closeSyncAdjust() }
    private val closeCaptionAdjustRunnable = Runnable { closeCaptionAdjust() }
    private val refreshAudioOutputRunnable = Runnable { refreshAudioOutput() }

    /** Al conectar o desconectar auriculares, la salida cambia y se aplica su sincronía. */
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = scheduleAudioOutputRefresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = scheduleAudioOutputRefresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        messageView = findViewById(R.id.message)
        titleView = findViewById(R.id.title)
        profileToast = findViewById(R.id.profile_toast)
        syncPanel = findViewById(R.id.sync_panel)

        // Leer el perfil guardado bloqueando un instante: es un fichero diminuto y así el
        // audio empieza ya con el perfil correcto, sin un salto de volumen al arrancar.
        profileStore = ProfileStore(this)
        trackPreferencesStore = TrackPreferencesStore(this)
        captionStyleStore = CaptionStyleStore(this)
        captionStyle = captionStyleStore.load()
        audioSyncStore = AudioSyncStore(this)
        audioManager = getSystemService(AudioManager::class.java)
        audioOutput = AudioOutputs.current(audioManager)
        runBlocking {
            liveProfile.current = profileStore.load()
            trackPreferences = trackPreferencesStore.load()
            liveSync.offsetMs = audioOutput?.let { audioSyncStore.load(it.key) } ?: 0
        }
        Log.i(TAG, "Salida de audio: ${audioOutput?.key} sincronía=${liveSync.offsetMs} ms")

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
            when {
                adjustingSync -> closeSyncAdjust()
                adjustingCaptions -> closeCaptionAdjust()
                scrubber.isScrubbing -> scrubber.cancel()
                else -> finishWithResult(END_BY_USER)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (request != null) {
            initializePlayer()
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        }
    }

    override fun onPause() {
        super.onPause()
        // Se prepara el resultado ya: si Android destruye la app en segundo plano,
        // Stremio recibe igualmente la última posición.
        if (!resultIsFinal && request != null) setResult(RESULT_OK, buildResult(END_BY_USER))
    }

    override fun onStop() {
        super.onStop()
        if (request != null) {
            closeSyncAdjust()
            closeCaptionAdjust()
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
            syncPanel.removeCallbacks(refreshAudioOutputRunnable)
        }
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

        val newPlayer = ExoPlayer.Builder(this, NightModeRenderersFactory(this, liveProfile, liveSync))
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

            val sync = LayoutInflater.from(this)
                .inflate(R.layout.sync_button, basicControls, false) as TextView
            sync.setOnClickListener { openSyncAdjust() }
            basicControls.addView(sync, 1)
            syncButton = sync

            // Botón de estilo de subtítulos. Reutiliza el layout del botón de sincronía; si
            // quieres otro icono, crea un layout propio (p. ej. caption_style_button).
            val caption = LayoutInflater.from(this)
                .inflate(R.layout.sync_button, basicControls, false) as TextView
            caption.text = "Aa"
            caption.contentDescription = "Caption style"
            caption.setOnClickListener { openCaptionAdjust() }
            basicControls.addView(caption, 2)
            captionButton = caption
        }
        updateProfileButton()
        updateSyncButton()

        // Panel de estilo: arriba, para no tapar los subtítulos (que salen abajo).
        captionPanel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(dp(28), dp(16), dp(28), dp(16))
            setBackgroundColor(0xCC000000.toInt())
            isFocusable = false
            visibility = View.GONE
        }
        playerView.overlayFrameLayout?.addView(
            captionPanel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = dp(32) },
        )
        applyCaptionStyle()

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
     *  - Ajustando la sincronía: ←/→ cambian el desfase y OK termina.
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
        if (adjustingCaptions) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> if (firstPress) moveCaptionRow(-1)
                KeyEvent.KEYCODE_DPAD_DOWN -> if (firstPress) moveCaptionRow(1)
                KeyEvent.KEYCODE_DPAD_LEFT -> if (firstPress) stepCaption(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (firstPress) stepCaption(1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (firstPress) closeCaptionAdjust()
                else -> return false
            }
            return true
        }
        if (adjustingSync) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                    stepSync(forward = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (firstPress) closeSyncAdjust()
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> Unit // sin controles encima del vídeo
                else -> return false
            }
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
        showToast(getString(R.string.profile_toast, getString(profile.labelRes)))
        lifecycleScope.launch { profileStore.save(profile) }
    }

    private fun showToast(text: String) {
        profileToast.text = text
        profileToast.visibility = View.VISIBLE
        profileToast.removeCallbacks(hideProfileToast)
        profileToast.postDelayed(hideProfileToast, TOAST_DURATION_MS)
    }

    private fun updateProfileButton() {
        val label = getString(liveProfile.current.labelRes)
        profileButton?.text = getString(R.string.profile_button, label)
        profileButton?.contentDescription = getString(R.string.profile_toast, label)
    }

    // ---------------------------------------------------------------- Sincronía audio/imagen

    /** Modo de ajuste: se ocultan los controles para ver bien los labios mientras se ajusta. */
    private fun openSyncAdjust() {
        adjustingSync = true
        playerView.hideController()
        profileToast.visibility = View.GONE
        updateSyncPanel()
        syncPanel.visibility = View.VISIBLE
        syncPanel.removeCallbacks(closeSyncAdjustRunnable)
        syncPanel.postDelayed(closeSyncAdjustRunnable, SYNC_ADJUST_TIMEOUT_MS)
    }

    private fun stepSync(forward: Boolean) {
        val step = if (forward) SYNC_STEP_MS else -SYNC_STEP_MS
        liveSync.offsetMs = (liveSync.offsetMs + step).coerceIn(SYNC_MIN_MS, SYNC_MAX_MS)
        updateSyncButton()
        updateSyncPanel()
        syncPanel.removeCallbacks(closeSyncAdjustRunnable)
        syncPanel.postDelayed(closeSyncAdjustRunnable, SYNC_ADJUST_TIMEOUT_MS)
    }

    /** Sale del modo de ajuste y guarda el desfase para la salida de audio actual. */
    private fun closeSyncAdjust() {
        if (!adjustingSync) return
        adjustingSync = false
        syncPanel.removeCallbacks(closeSyncAdjustRunnable)
        syncPanel.visibility = View.GONE
        val output = audioOutput ?: return
        val offset = liveSync.offsetMs
        Log.i(TAG, "Sincronía de audio para ${output.key}: $offset ms")
        lifecycleScope.launch { audioSyncStore.save(output.key, offset) }
    }

    /** Los avisos de dispositivo llegan antes de que Android cambie la ruta: se espera un poco. */
    private fun scheduleAudioOutputRefresh() {
        syncPanel.removeCallbacks(refreshAudioOutputRunnable)
        syncPanel.postDelayed(refreshAudioOutputRunnable, AUDIO_OUTPUT_REFRESH_DELAY_MS)
    }

    private fun refreshAudioOutput() {
        val output = AudioOutputs.current(audioManager)
        if (output?.key == audioOutput?.key) return
        closeSyncAdjust() // guarda lo ajustado para la salida anterior
        audioOutput = output
        lifecycleScope.launch {
            val offset = output?.let { audioSyncStore.load(it.key) } ?: 0
            if (audioOutput?.key != output?.key) return@launch // ha vuelto a cambiar mientras tanto
            Log.i(TAG, "Salida de audio: ${output?.key} sincronía=$offset ms")
            liveSync.offsetMs = offset
            updateSyncButton()
            if (offset != 0) showToast(getString(R.string.audio_sync_toast, formatSync(offset), outputName()))
        }
    }

    private fun updateSyncButton() {
        syncButton?.text = getString(R.string.audio_sync_button, formatSync(liveSync.offsetMs))
    }

    private fun updateSyncPanel() {
        syncPanel.text = getString(R.string.audio_sync_panel, formatSync(liveSync.offsetMs), outputName())
    }

    private fun outputName(): String = audioOutput?.name ?: getString(R.string.audio_output_unknown)

    private fun formatSync(offsetMs: Int): String =
        if (offsetMs == 0) "0 ms" else String.format(Locale.ROOT, "%+d ms", offsetMs)

    // ---------------------------------------------------------------- Estilo de subtítulos

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Aplica el estilo guardado a la vista de subtítulos de Media3. */
    private fun applyCaptionStyle() {
        val view = playerView.subtitleView ?: return
        val s = captionStyle
        // Con false, nuestro estilo manda también sobre subtítulos ASS/SSA/WebVTT con estilo propio.
        view.setApplyEmbeddedStyles(s.embeddedStyles)
        view.setApplyEmbeddedFontSizes(s.embeddedStyles)
        view.setStyle(
            CaptionStyleCompat(
                s.textColor,
                s.backgroundColor,
                Color.TRANSPARENT,
                s.edgeType,
                Color.BLACK,
                if (s.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT,
            ),
        )
        view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * s.sizeFactor)
    }

    /** Modo de ajuste: ↑/↓ elige fila, ←/→ cambia el valor, OK o Atrás cierran. */
    private fun openCaptionAdjust() {
        adjustingCaptions = true
        captionRow = 0
        playerView.hideController()
        profileToast.visibility = View.GONE
        // Si ahora mismo no se ve ningún subtítulo, se enseña uno de muestra.
        if (player?.currentCues?.cues.isNullOrEmpty()) {
            playerView.subtitleView?.setCues(
                listOf(Cue.Builder().setText("Sample caption: the quick brown fox").build()),
            )
        }
        updateCaptionPanel()
        captionPanel.visibility = View.VISIBLE
        restartCaptionTimeout()
    }

    private fun moveCaptionRow(delta: Int) {
        val rows = CaptionStyle.ROW_LABELS.size
        captionRow = (captionRow + delta + rows) % rows
        updateCaptionPanel()
        restartCaptionTimeout()
    }

    private fun stepCaption(delta: Int) {
        captionStyle = captionStyle.step(captionRow, delta)
        applyCaptionStyle()
        updateCaptionPanel()
        restartCaptionTimeout()
    }

    private fun closeCaptionAdjust() {
        if (!adjustingCaptions) return
        adjustingCaptions = false
        captionPanel.removeCallbacks(closeCaptionAdjustRunnable)
        captionPanel.visibility = View.GONE
        // Quita el subtítulo de muestra y devuelve el que toque.
        playerView.subtitleView?.setCues(player?.currentCues?.cues)
        captionStyleStore.save(captionStyle)
    }

    private fun restartCaptionTimeout() {
        captionPanel.removeCallbacks(closeCaptionAdjustRunnable)
        captionPanel.postDelayed(closeCaptionAdjustRunnable, CAPTION_ADJUST_TIMEOUT_MS)
    }

    private fun updateCaptionPanel() {
        val text = StringBuilder("Caption style\n")
        CaptionStyle.ROW_LABELS.forEachIndexed { row, label ->
            val value = captionStyle.valueLabel(row)
            text.append('\n')
            if (row == captionRow) text.append("▶  $label:  ‹ $value ›") else text.append("    $label:  $value")
        }
        text.append("\n\n↑↓ choose   ←→ change   OK close")
        captionPanel.text = text
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

        // Pasos de 25 ms: el desfase se empieza a notar hacia los 45 ms (sonido adelantado) y
        // los 125 ms (retrasado), así que 50 ms se quedan cortos para afinar.
        const val SYNC_STEP_MS = 25
        // El Bluetooth puede añadir 100–300 ms (más con algunos códecs). En negativo se limita:
        // adelantar mucho el vídeo obliga al decodificador a ir por delante y descarta fotogramas.
        const val SYNC_MIN_MS = -300
        const val SYNC_MAX_MS = 1_000
        const val SYNC_ADJUST_TIMEOUT_MS = 8_000L
        const val CAPTION_ADJUST_TIMEOUT_MS = 15_000L
        const val AUDIO_OUTPUT_REFRESH_DELAY_MS = 750L

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
