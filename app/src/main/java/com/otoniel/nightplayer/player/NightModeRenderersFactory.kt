package com.otoniel.nightplayer.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.otoniel.nightplayer.audio.CompressorProcessor
import com.otoniel.nightplayer.audio.DialogDownmixProcessor
import com.otoniel.nightplayer.audio.LimiterProcessor
import com.otoniel.nightplayer.audio.LiveAudioProfile
import com.otoniel.nightplayer.audio.StereoWidthProcessor

/**
 * Monta los "renderers" de ExoPlayer: los componentes que decodifican y entregan vídeo y
 * audio al hardware. Solo cambiamos la parte de audio:
 *
 *  - Decodificador FFmpeg primero (AC3, E-AC3, DTS...): la tele puede no tener decodificador
 *    para esos formatos y, sobre todo, necesitamos PCM para poder procesarlo. Si FFmpeg no
 *    soporta un formato, se usa el decodificador del sistema (MediaCodec) como respaldo.
 *  - Un DefaultAudioSink que solo acepta PCM (sin passthrough de Dolby/DTS a la tele ni a
 *    una barra de sonido) y pasa todo por nuestra cadena:
 *    DialogDownmixProcessor → CompressorProcessor → StereoWidthProcessor → LimiterProcessor.
 */
@UnstableApi
class NightModeRenderersFactory(
    context: Context,
    private val liveProfile: LiveAudioProfile,
) : DefaultRenderersFactory(context) {

    @Suppress("DEPRECATION") // setAudioCapabilities: sigue siendo la forma directa de vetar el passthrough
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink =
        DefaultAudioSink.Builder(context)
            // Capacidades "por defecto" = solo PCM estéreo. Así ExoPlayer nunca intenta
            // enviar el bitstream AC3/DTS sin decodificar, que no podríamos tocar.
            .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
            .setAudioProcessors(
                arrayOf(
                    DialogDownmixProcessor(liveProfile),
                    CompressorProcessor(liveProfile),
                    StereoWidthProcessor(liveProfile),
                    LimiterProcessor(liveProfile),
                ),
            )
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        // Equivale a EXTENSION_RENDERER_MODE_PREFER, pero creando el renderer FFmpeg de forma
        // explícita en vez de por reflexión: si falta la librería, falla al compilar.
        // El orden de la lista es la prioridad: ExoPlayer usa el primero que soporte la pista.
        out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
        super.buildAudioRenderers(
            context,
            EXTENSION_RENDERER_MODE_OFF,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )
    }
}
