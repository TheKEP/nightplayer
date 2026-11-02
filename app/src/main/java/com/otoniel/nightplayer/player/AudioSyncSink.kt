package com.otoniel.nightplayer.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/** Desfase de sincronía elegido por el usuario. Lo lee el hilo de reproducción en cada consulta. */
class LiveAudioSync(initialMs: Int = 0) {
    @Volatile
    var offsetMs: Int = initialMs
}

/**
 * AudioSink que retrasa o adelanta la imagen respecto al sonido.
 *
 * En ExoPlayer el audio hace de reloj: cada fotograma se muestra cuando la posición del audio
 * llega a su marca de tiempo. Si a esa posición le restamos [LiveAudioSync.offsetMs], el vídeo
 * se muestra ese tiempo más tarde y compensa el retraso de auriculares y altavoces Bluetooth.
 * Un valor negativo adelanta el vídeo (para salidas que van por delante de la imagen).
 *
 * Al subir el desfase la imagen se queda quieta un instante (ExoPlayer no deja que el reloj
 * retroceda) y al bajarlo salta unos fotogramas. Solo se nota mientras se ajusta.
 */
@UnstableApi
class AudioSyncSink(sink: AudioSink, private val sync: LiveAudioSync) : ForwardingAudioSink(sink) {

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
        shiftPositionUs(super.getCurrentPositionUs(sourceEnded), sync.offsetMs)

    companion object {
        fun shiftPositionUs(positionUs: Long, offsetMs: Int): Long =
            if (positionUs == AudioSink.CURRENT_POSITION_NOT_SET) positionUs else positionUs - offsetMs * 1_000L
    }
}
