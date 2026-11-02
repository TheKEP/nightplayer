package com.otoniel.nightplayer.player

import android.media.AudioDeviceInfo
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertEquals
import org.junit.Test

@UnstableApi
class AudioSyncTest {

    @Test
    fun `un desfase positivo retrasa el reloj y por tanto la imagen`() {
        assertEquals(9_850_000L, AudioSyncSink.shiftPositionUs(10_000_000L, 150))
    }

    @Test
    fun `un desfase negativo adelanta el reloj`() {
        assertEquals(10_100_000L, AudioSyncSink.shiftPositionUs(10_000_000L, -100))
    }

    @Test
    fun `sin posicion todavia no se toca`() {
        assertEquals(
            AudioSink.CURRENT_POSITION_NOT_SET,
            AudioSyncSink.shiftPositionUs(AudioSink.CURRENT_POSITION_NOT_SET, 200),
        )
    }

    @Test
    fun `con auriculares Bluetooth conectados se elige el Bluetooth`() {
        val types = listOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        )
        assertEquals(2, AudioOutputs.preferredIndex(types))
    }

    @Test
    fun `sin Bluetooth ni cable gana el HDMI al altavoz`() {
        val types = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_HDMI)
        assertEquals(1, AudioOutputs.preferredIndex(types))
    }

    @Test
    fun `tipo desconocido o lista vacia`() {
        assertEquals(0, AudioOutputs.preferredIndex(listOf(AudioDeviceInfo.TYPE_REMOTE_SUBMIX)))
        assertEquals(-1, AudioOutputs.preferredIndex(emptyList()))
    }
}
