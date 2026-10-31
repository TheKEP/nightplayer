package com.otoniel.nightplayer.audio

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CompressorProcessorTest {

    /** RMS de un seno = pico − 3,01 dB. */
    private val sineRmsOffsetDb = -3.0103

    /** Ganancia teórica en estado estacionario para un seno de pico [peakDb]. */
    private fun expectedOutputPeakDb(profile: AudioProfile, peakDb: Double): Double {
        val rms = (peakDb + sineRmsOffsetDb).toFloat()
        val reduction = CompressorProcessor.staticCurveDb(rms, profile.thresholdDb, profile.ratio) - rms
        return peakDb + reduction + profile.makeupDb
    }

    private fun steadyStatePeakDb(profile: AudioProfile, peakDb: Double, encoding: Int = C.ENCODING_PCM_FLOAT): Double {
        val processor = CompressorProcessor(LiveAudioProfile(profile))
        val output = Dsp.process(listOf(processor), Dsp.sine(Dsp.dbfs(peakDb), 2.0), 2, encoding)
        return Dsp.tailPeakDb(output, 0.2)
    }

    @Test
    fun `curva estatica - identidad bajo el umbral y recta por encima`() {
        assertEquals(-50f, CompressorProcessor.staticCurveDb(-50f, -30f, 6f), 1e-4f)
        assertEquals(-30f + 12f / 6f, CompressorProcessor.staticCurveDb(-18f, -30f, 6f), 1e-4f)
        // Continuidad en los bordes de la rodilla de 6 dB (−33 y −27 dB).
        for (edge in listOf(-33f, -27f)) {
            val below = CompressorProcessor.staticCurveDb(edge - 1e-3f, -30f, 6f)
            val above = CompressorProcessor.staticCurveDb(edge + 1e-3f, -30f, 6f)
            assertEquals(below, above, 1e-2f)
        }
    }

    @Test
    fun `perfil desactivado no modifica la senal`() {
        val input = Dsp.sine(Dsp.dbfs(-20.0), 0.5)
        val output = Dsp.process(listOf(CompressorProcessor(LiveAudioProfile(AudioProfile.OFF))), input, 2)
        assertEquals(input.size, output.size)
        for (i in input.indices) assertEquals(input[i], output[i], 1e-5f)
    }

    @Test
    fun `seno de 1 kHz a -40, -20 y 0 dBFS da la ganancia esperada en Suave y Noche`() {
        for (profile in listOf(AudioProfile.SOFT, AudioProfile.NIGHT)) {
            for (level in listOf(-40.0, -20.0, 0.0)) {
                val expected = expectedOutputPeakDb(profile, level)
                val measured = steadyStatePeakDb(profile, level)
                assertEquals("$profile a $level dBFS", expected, measured, 0.5)
            }
        }
    }

    @Test
    fun `Noche reduce el rango dinamico de 40 dB a menos de 20 dB`() {
        val quiet = steadyStatePeakDb(AudioProfile.NIGHT, -40.0)
        val loud = steadyStatePeakDb(AudioProfile.NIGHT, 0.0)
        assertTrue("rango ${loud - quiet} dB", loud - quiet < 20.0)
    }

    @Test
    fun `tambien funciona con PCM de 16 bits`() {
        val expected = expectedOutputPeakDb(AudioProfile.NIGHT, -20.0)
        assertEquals(expected, steadyStatePeakDb(AudioProfile.NIGHT, -20.0, C.ENCODING_PCM_16BIT), 0.5)
    }

    @Test
    fun `rafaga - el attack alcanza la reduccion en pocos ms`() {
        val profile = AudioProfile.NIGHT
        val quiet = Dsp.sine(Dsp.dbfs(-40.0), 1.0)
        val burst = Dsp.sine(Dsp.dbfs(0.0), 0.5)
        val output = Dsp.process(listOf(CompressorProcessor(LiveAudioProfile(profile))), quiet + burst, 2)

        val burstStart = quiet.size
        val window = { fromMs: Int, toMs: Int ->
            Dsp.toDb(Dsp.peak(output, burstStart + fromMs * 96, burstStart + toMs * 96))
        }
        val steady = expectedOutputPeakDb(profile, 0.0)
        // Al principio de la ráfaga se pasa (el limitador se encarga de eso)...
        assertTrue("primer ms ${window(0, 1)}", window(0, 1) > steady + 3.0)
        // ...pero a los 50 ms ya está a menos de 1 dB del valor estacionario.
        assertEquals(steady, window(50, 60), 1.0)
    }

    @Test
    fun `silencio sigue siendo silencio`() {
        for (profile in AudioProfile.entries) {
            val output = Dsp.process(listOf(CompressorProcessor(LiveAudioProfile(profile))), Dsp.silence(0.5), 2)
            assertTrue(output.all { it == 0f })
        }
    }

    @Test
    fun `cambio de perfil en caliente sin saltos bruscos`() {
        val live = LiveAudioProfile(AudioProfile.OFF)
        val active = Dsp.prepare(listOf(CompressorProcessor(live)), 1)
        val dc = FloatArray(Dsp.SAMPLE_RATE / 2) { 0.05f } // señal constante: cualquier salto se vería
        val before = Dsp.feed(active, dc, 1)
        live.current = AudioProfile.NIGHT
        val after = Dsp.feed(active, dc, 1)

        val joined = before + after
        var maxStep = 0f
        for (i in 1 until joined.size) maxStep = maxOf(maxStep, abs(joined[i] - joined[i - 1]))
        // Un salto instantáneo de +8 dB de makeup sería ~0,076; suavizado queda muy por debajo.
        assertTrue("salto máximo $maxStep", maxStep < 0.005f)
        // Y el cambio llega: la salida final es distinta de la entrada.
        assertTrue(abs(after.last() - 0.05f) > 0.01f)
    }
}
