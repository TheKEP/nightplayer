package com.otoniel.nightplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogDownmixProcessorTest {

    /** Un frame 5.1 en orden Android: FL, FR, FC, LFE, BL, BR. */
    private fun frames51(count: Int, fl: Float = 0f, fr: Float = 0f, fc: Float = 0f, lfe: Float = 0f, bl: Float = 0f, br: Float = 0f) =
        FloatArray(count * 6) { i -> floatArrayOf(fl, fr, fc, lfe, bl, br)[i % 6] }

    private fun downmix(profile: AudioProfile, input: FloatArray, channels: Int = 6, encoding: Int = C.ENCODING_PCM_FLOAT) =
        Dsp.process(listOf(DialogDownmixProcessor(LiveAudioProfile(profile))), input, channels, encoding)

    private fun centerGain(profile: AudioProfile) = 0.707f * dbToLinear(profile.dialogGainDb)

    @Test
    fun `estereo y mono pasan sin procesar`() {
        for (channels in listOf(1, 2)) {
            val processor = DialogDownmixProcessor(LiveAudioProfile(AudioProfile.NIGHT))
            processor.configure(AudioFormat(Dsp.SAMPLE_RATE, channels, C.ENCODING_PCM_FLOAT))
            assertFalse(processor.isActive)
        }
    }

    @Test
    fun `5_1 sale en estereo con el mismo encoding`() {
        for (encoding in listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)) {
            val processor = DialogDownmixProcessor(LiveAudioProfile(AudioProfile.OFF))
            val out = processor.configure(AudioFormat(Dsp.SAMPLE_RATE, 6, encoding))
            assertTrue(processor.isActive)
            assertEquals(2, out.channelCount)
            assertEquals(encoding, out.encoding)
        }
    }

    @Test
    fun `el central va a ambos lados con la ganancia de dialogo de cada perfil`() {
        for (profile in AudioProfile.entries) {
            val c = centerGain(profile)
            val expected = 0.5f * c / (1f + c + 0.5f)
            val output = downmix(profile, frames51(1_000, fc = 0.5f))
            assertEquals("$profile L", expected, output[output.size - 2], 1e-4f)
            assertEquals("$profile R", expected, output[output.size - 1], 1e-4f)
        }
    }

    @Test
    fun `Noche sube el dialogo respecto al resto frente a Desactivado`() {
        fun dialogToFrontRatio(profile: AudioProfile): Float {
            val dialog = downmix(profile, frames51(1_000, fc = 0.5f)).last()
            val front = downmix(profile, frames51(1_000, fl = 0.5f)).let { it[it.size - 2] }
            return dialog / front
        }
        val ratioDb = Dsp.toDb(dialogToFrontRatio(AudioProfile.NIGHT)) - Dsp.toDb(dialogToFrontRatio(AudioProfile.OFF))
        assertEquals(6.0, ratioDb, 0.05)
    }

    @Test
    fun `el LFE se descarta y no hay mezcla entre izquierda y derecha`() {
        val lfeOnly = downmix(AudioProfile.NIGHT, frames51(500, lfe = 0.9f))
        assertTrue(lfeOnly.all { it == 0f })

        val leftOnly = downmix(AudioProfile.OFF, frames51(500, fl = 0.5f, bl = 0.5f))
        val norm = 1f + centerGain(AudioProfile.OFF) + 0.5f
        assertEquals((0.5f + 0.25f) / norm, leftOnly[leftOnly.size - 2], 1e-4f)
        assertEquals(0f, leftOnly[leftOnly.size - 1], 0f)
    }

    @Test
    fun `7_1 suma traseros y laterales`() {
        // FL, FR, FC, LFE, BL, BR, SL, SR
        val input = FloatArray(500 * 8) { i -> if (i % 8 == 4 || i % 8 == 6) 0.4f else 0f }
        val output = downmix(AudioProfile.OFF, input, channels = 8)
        val norm = 1f + centerGain(AudioProfile.OFF) + 2 * 0.5f
        assertEquals(0.5f * 0.8f / norm, output[output.size - 2], 1e-4f)
    }

    @Test
    fun `nunca satura aunque todos los canales esten a fondo de escala`() {
        for (profile in AudioProfile.entries) {
            val output = downmix(profile, frames51(1_000, 1f, 1f, 1f, 1f, 1f, 1f))
            assertTrue(Dsp.peak(output) <= 1f + 1e-6f)
        }
    }

    @Test
    fun `silencio sigue siendo silencio`() {
        assertTrue(downmix(AudioProfile.NIGHT, frames51(1_000)).all { it == 0f })
    }
}
