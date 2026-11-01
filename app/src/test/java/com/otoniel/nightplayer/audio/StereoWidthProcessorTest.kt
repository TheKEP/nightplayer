package com.otoniel.nightplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StereoWidthProcessorTest {

    /** Frames estéreo constantes [l, r]. */
    private fun frames(count: Int, l: Float, r: Float) = FloatArray(count * 2) { i -> if (i % 2 == 0) l else r }

    private fun widen(profile: AudioProfile, input: FloatArray, encoding: Int = C.ENCODING_PCM_FLOAT) =
        Dsp.process(listOf(StereoWidthProcessor(LiveAudioProfile(profile))), input, 2, encoding)

    @Test
    fun `solo actua sobre estereo`() {
        for (channels in listOf(1, 6)) {
            val processor = StereoWidthProcessor(LiveAudioProfile(AudioProfile.NIGHT))
            processor.configure(AudioFormat(Dsp.SAMPLE_RATE, channels, C.ENCODING_PCM_FLOAT))
            assertFalse(processor.isActive)
        }
        val stereo = StereoWidthProcessor(LiveAudioProfile(AudioProfile.OFF))
        stereo.configure(AudioFormat(Dsp.SAMPLE_RATE, 2, C.ENCODING_PCM_FLOAT))
        assertTrue(stereo.isActive)
    }

    @Test
    fun `perfil desactivado no modifica la senal`() {
        val input = Dsp.sine(0.5f, 0.1) + frames(1_000, 0.3f, -0.2f)
        assertArrayEquals(input, widen(AudioProfile.OFF, input), 0f)
    }

    @Test
    fun `lo comun a ambos lados (dialogo) no cambia`() {
        for (profile in AudioProfile.entries) {
            val out = widen(profile, frames(1_000, 0.4f, 0.4f))
            assertTrue("$profile", out.all { it == 0.4f })
        }
    }

    @Test
    fun `la parte lateral se multiplica por la anchura del perfil`() {
        for (profile in AudioProfile.entries) {
            val out = widen(profile, frames(1_000, 0.5f, 0.1f)) // M = 0.3, S = 0.2
            val w = profile.stereoWidth
            assertEquals("$profile L", 0.3f + w * 0.2f, out[out.size - 2], 1e-5f)
            assertEquals("$profile R", 0.3f - w * 0.2f, out[out.size - 1], 1e-5f)
        }
    }

    @Test
    fun `tambien funciona con PCM de 16 bits`() {
        val out = widen(AudioProfile.NIGHT, frames(1_000, 0.5f, 0.1f), C.ENCODING_PCM_16BIT)
        assertEquals(0.3f + 1.4f * 0.2f, out[out.size - 2], 1e-3f)
        assertEquals(0.3f - 1.4f * 0.2f, out[out.size - 1], 1e-3f)
    }

    @Test
    fun `cambio de perfil en caliente sin saltos bruscos`() {
        val live = LiveAudioProfile(AudioProfile.OFF)
        val active = Dsp.prepare(listOf(StereoWidthProcessor(live)), 2)
        val input = frames(2_400, 0.5f, -0.5f) // solo lados: S = 0.5
        val before = Dsp.feed(active, input, 2)
        live.current = AudioProfile.NIGHT
        val after = Dsp.feed(active, input, 2)
        // Primer frame tras el cambio: casi igual que antes (la rampa empieza suave)...
        assertEquals(before.last(), after[1], 1e-3f)
        // ...y al final del trozo (50 ms) ya ha llegado a la anchura de Noche.
        assertEquals(-0.5f * 1.4f, after.last(), 1e-2f)
    }
}
