package com.otoniel.nightplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LimiterProcessorTest {

    private val ceiling = dbToLinear(AudioProfile.LIMITER_CEILING_DB)

    /** 5 ms a 48 kHz = 240 frames de ventana → el audio sale 239 frames más tarde. */
    private val delayFrames = 239

    @Test
    fun `nada supera -1 dBFS ni con rafagas muy por encima de 0 dBFS`() {
        val random = Random(42)
        val input = FloatArray(Dsp.SAMPLE_RATE * 2 * 2) { i ->
            val frame = i / 2
            // Silencio, ráfagas cortas de ruido a +10 dBFS y transitorios aislados.
            when {
                frame % 9_600 < 50 -> random.nextFloat() * 6f - 3f
                frame % 4_800 == 0 -> 4f
                else -> random.nextFloat() * 0.2f - 0.1f
            }
        }
        for (profile in listOf(AudioProfile.SOFT, AudioProfile.NIGHT)) {
            val output = Dsp.process(listOf(LimiterProcessor(LiveAudioProfile(profile))), input, 2, endOfStream = true)
            assertTrue("pico ${Dsp.peak(output)}", Dsp.peak(output) <= ceiling + 1e-6f)
        }
    }

    @Test
    fun `con PCM de 16 bits un cuadrado a fondo de escala queda bajo el techo`() {
        val square = FloatArray(Dsp.SAMPLE_RATE * 2) { i -> if ((i / 2 / 24) % 2 == 0) 1f else -1f }
        val output = Dsp.process(
            listOf(LimiterProcessor(LiveAudioProfile(AudioProfile.NIGHT))), square, 2, C.ENCODING_PCM_16BIT,
        )
        assertTrue(Dsp.peak(output) <= ceiling + 1f / 32768f)
    }

    @Test
    fun `por debajo del techo la senal pasa intacta, solo retrasada 5 ms`() {
        val input = Dsp.sine(Dsp.dbfs(-6.0), 0.5)
        val output = Dsp.process(listOf(LimiterProcessor(LiveAudioProfile(AudioProfile.NIGHT))), input, 2)
        val delay = delayFrames * 2
        for (i in 0 until input.size - delay) assertEquals(input[i], output[i + delay], 1e-6f)
        for (i in 0 until delay) assertEquals(0f, output[i], 0f)
    }

    @Test
    fun `al acabar el stream se vacia la linea de retardo`() {
        val input = Dsp.sine(Dsp.dbfs(-6.0), 0.1)
        val output = Dsp.process(
            listOf(LimiterProcessor(LiveAudioProfile(AudioProfile.SOFT))), input, 2, endOfStream = true,
        )
        assertEquals(input.size + delayFrames * 2, output.size)
        assertEquals(input.last(), output.last(), 1e-6f)
    }

    @Test
    fun `fin de stream seguido de un buffer vacio, como hace Media3, entrega la cola y termina`() {
        val limiter = LimiterProcessor(LiveAudioProfile(AudioProfile.NIGHT))
        val active = Dsp.prepare(listOf(limiter), 2)
        Dsp.feed(active, Dsp.sine(Dsp.dbfs(-6.0), 0.1), 2)

        limiter.queueEndOfStream()
        limiter.queueInput(AudioProcessor.EMPTY_BUFFER)
        assertEquals(delayFrames * 2 * 4, limiter.output.remaining())
        assertTrue(limiter.isEnded)
    }

    @Test
    fun `apagado en el perfil Desactivado`() {
        val input = FloatArray(4_800) { 1.5f }
        val output = Dsp.process(listOf(LimiterProcessor(LiveAudioProfile(AudioProfile.OFF))), input, 2, endOfStream = true)
        assertEquals(1.5f, Dsp.peak(output), 1e-6f)
    }

    @Test
    fun `silencio sigue siendo silencio`() {
        val output = Dsp.process(
            listOf(LimiterProcessor(LiveAudioProfile(AudioProfile.NIGHT))), Dsp.silence(0.5), 2, endOfStream = true,
        )
        assertTrue(output.all { it == 0f })
    }
}
