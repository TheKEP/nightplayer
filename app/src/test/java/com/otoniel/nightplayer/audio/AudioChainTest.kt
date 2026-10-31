package com.otoniel.nightplayer.audio

import org.junit.Assert.assertTrue
import org.junit.Test

/** La cadena completa, como la monta NightModeRenderersFactory, con una "peli" sintética 5.1. */
class AudioChainTest {

    private fun chain(profile: AudioProfile) = LiveAudioProfile(profile).let {
        listOf(DialogDownmixProcessor(it), CompressorProcessor(it), LimiterProcessor(it))
    }

    /** 5.1 con un seno en los canales indicados (máscara de 6 posiciones). */
    private fun surround(peakDb: Double, seconds: Double, mask: BooleanArray): FloatArray {
        val mono = Dsp.sine(Dsp.dbfs(peakDb), seconds, channels = 1)
        return FloatArray(mono.size * 6) { i -> if (mask[i % 6]) mono[i / 6] else 0f }
    }

    private val dialogOnly = booleanArrayOf(false, false, true, false, false, false)
    private val everything = BooleanArray(6) { true }

    /** Diálogo a −20 dBFS en el central, luego "explosión" a 0 dBFS en todos los canales. */
    private fun scene(): Pair<FloatArray, Int> {
        val dialog = surround(-20.0, 2.0, dialogOnly)
        val action = surround(0.0, 2.0, everything)
        return (dialog + action) to (dialog.size / 6 * 2) // índice (en estéreo) donde empieza la acción
    }

    private fun actionVsDialogDb(profile: AudioProfile): Double {
        val (input, actionStart) = scene()
        val out = Dsp.process(chain(profile), input, 6, endOfStream = true)
        val tail = Dsp.SAMPLE_RATE / 5 * 2 // últimos 200 ms de cada parte
        val dialogDb = Dsp.toDb(Dsp.peak(out, actionStart - tail, actionStart))
        val actionDb = Dsp.toDb(Dsp.peak(out, actionStart + 2 * Dsp.SAMPLE_RATE * 2 - tail, actionStart + 2 * Dsp.SAMPLE_RATE * 2))
        return actionDb - dialogDb
    }

    @Test
    fun `en Noche la accion no sube mas de 6 dB sobre el dialogo`() {
        val night = actionVsDialogDb(AudioProfile.NIGHT)
        val off = actionVsDialogDb(AudioProfile.OFF)
        assertTrue("Desactivado: $off dB", off > 20.0)
        assertTrue("Noche: $night dB", night <= 6.0)
    }

    @Test
    fun `tras toda la cadena nada supera -1 dBFS`() {
        for (profile in listOf(AudioProfile.SOFT, AudioProfile.NIGHT)) {
            val out = Dsp.process(chain(profile), scene().first, 6, endOfStream = true)
            assertTrue("$profile: ${Dsp.toDb(Dsp.peak(out))} dBFS", Dsp.peak(out) <= dbToLinear(-1f) + 1e-6f)
        }
    }
}
