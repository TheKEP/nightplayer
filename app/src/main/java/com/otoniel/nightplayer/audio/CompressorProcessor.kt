package com.otoniel.nightplayer.audio

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.log10
import kotlin.math.pow

/**
 * Compresor feed-forward con todos los canales enlazados: se calcula una sola ganancia
 * a partir del nivel conjunto y se aplica igual a todos, para no mover la imagen estéreo.
 *
 * Por cada muestra (frame):
 *  1. Detector RMS (~10 ms): media exponencial de la potencia de la señal.
 *  2. Curva estática en dB con rodilla suave de 6 dB. Por encima del umbral T:
 *     `y = T + (x − T) / R`. La reducción deseada es `y − x` (≤ 0).
 *  3. Suavizado con attack (cuando hay que reducir más) y release (cuando se puede soltar).
 *  4. Se suma la ganancia de compensación (makeup), también suavizada al cambiar de perfil.
 */
class CompressorProcessor(private val liveProfile: LiveAudioProfile) : BaseAudioProcessor() {

    private var detectorCoef = 0f
    private var smoothingCoef = 0f

    // Coeficientes de attack/release del perfil actual; se recalculan solo si cambia el perfil.
    private var coefProfile: AudioProfile? = null
    private var attackCoef = 0f
    private var releaseCoef = 0f

    // Estado entre buffers.
    private var meanSquare = 0f
    private var gainReductionDb = 0f
    private var makeupDb = 0f

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (!isSupportedEncoding(inputAudioFormat.encoding)) return AudioFormat.NOT_SET
        detectorCoef = onePoleCoefficient(RMS_WINDOW_SECONDS, inputAudioFormat.sampleRate)
        smoothingCoef = onePoleCoefficient(PARAMETER_SMOOTHING_SECONDS, inputAudioFormat.sampleRate)
        coefProfile = null
        // Mismo formato de salida que de entrada: solo cambiamos el volumen.
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val profile = liveProfile.current
        if (profile !== coefProfile) {
            attackCoef = onePoleCoefficient(profile.attackMs / 1000f, inputAudioFormat.sampleRate)
            releaseCoef = onePoleCoefficient(profile.releaseMs / 1000f, inputAudioFormat.sampleRate)
            coefProfile = profile
        }
        val threshold = profile.thresholdDb
        val ratio = profile.ratio
        val targetMakeup = profile.makeupDb

        val encoding = inputAudioFormat.encoding
        val channels = inputAudioFormat.channelCount
        val sampleBytes = inputAudioFormat.bytesPerFrame / channels
        val start = inputBuffer.position()
        val end = inputBuffer.limit()
        val output = replaceOutputBuffer(end - start)

        var ms = meanSquare
        var reduction = gainReductionDb
        var makeup = makeupDb

        var frameStart = start
        while (frameStart < end) {
            // 1. Detector: potencia media del frame (todos los canales) y media exponencial.
            var sumSquares = 0f
            for (ch in 0 until channels) {
                val s = inputBuffer.readSample(frameStart + ch * sampleBytes, encoding)
                sumSquares += s * s
            }
            ms = detectorCoef * ms + (1f - detectorCoef) * (sumSquares / channels)
            val levelDb = 10f * log10(ms + POWER_FLOOR)

            // 2 y 3. Reducción deseada y suavizado attack/release.
            val targetReduction = staticCurveDb(levelDb, threshold, ratio) - levelDb
            val coef = if (targetReduction < reduction) attackCoef else releaseCoef
            reduction = coef * reduction + (1f - coef) * targetReduction

            // 4. Makeup suavizado y aplicación de la ganancia total.
            makeup = smoothingCoef * makeup + (1f - smoothingCoef) * targetMakeup
            val gain = 10f.pow((reduction + makeup) / 20f)
            for (ch in 0 until channels) {
                val s = inputBuffer.readSample(frameStart + ch * sampleBytes, encoding)
                output.writeSample(s * gain, encoding)
            }
            frameStart += inputAudioFormat.bytesPerFrame
        }

        meanSquare = ms
        gainReductionDb = reduction
        makeupDb = makeup
        inputBuffer.position(end)
        output.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        meanSquare = 0f
        gainReductionDb = 0f
        makeupDb = liveProfile.current.makeupDb
    }

    companion object {
        private const val RMS_WINDOW_SECONDS = 0.010f
        private const val KNEE_WIDTH_DB = 6f

        /** Evita log10(0) con silencio absoluto (≈ −120 dBFS). */
        private const val POWER_FLOOR = 1e-12f

        /**
         * Curva estática del compresor con rodilla suave: devuelve el nivel de salida (dB)
         * para un nivel de entrada [x] (dB). Pública para poder probarla aislada.
         */
        fun staticCurveDb(x: Float, threshold: Float, ratio: Float): Float {
            val overshoot = x - threshold
            return when {
                2f * overshoot < -KNEE_WIDTH_DB -> x
                2f * overshoot > KNEE_WIDTH_DB -> threshold + overshoot / ratio
                else -> {
                    // Dentro de la rodilla: transición cuadrática entre "sin comprimir" y la recta.
                    val k = overshoot + KNEE_WIDTH_DB / 2f
                    x + (1f / ratio - 1f) * k * k / (2f * KNEE_WIDTH_DB)
                }
            }
        }
    }
}
