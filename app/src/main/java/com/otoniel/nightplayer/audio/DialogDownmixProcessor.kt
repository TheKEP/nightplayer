package com.otoniel.nightplayer.audio

import android.util.Log
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * Downmix de 5.1/7.1 a estéreo reforzando el canal central, que es donde van los diálogos.
 *
 *   L = FL + c·FC + s·SL
 *   R = FR + c·FC + s·SR      con c = 0,707 × ganancia de diálogo y s = 0,5
 *
 * El LFE (subgraves) se descarta: los altavoces de la tele no lo reproducen y solo
 * añadiría picos. El resultado se divide por la suma de coeficientes para no saturar; el
 * compresor recupera el nivel después.
 *
 * Mono, estéreo y otros formatos pasan sin tocar (el procesador queda inactivo).
 */
class DialogDownmixProcessor(private val liveProfile: LiveAudioProfile) : BaseAudioProcessor() {

    /** Coeficiente del central ya suavizado (cambia poco a poco al cambiar de perfil). */
    private var centerGain = CENTER_BASE
    private var smoothingCoef = 0f

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        Log.i(
            TAG,
            "Audio PCM entrante: ${inputAudioFormat.channelCount} canales, " +
                "${inputAudioFormat.sampleRate} Hz, ${encodingName(inputAudioFormat.encoding)}",
        )
        if (!isSupportedEncoding(inputAudioFormat.encoding)) {
            Log.w(TAG, "Codificación no soportada por el downmix; se deja pasar sin procesar")
            return AudioFormat.NOT_SET
        }
        if (inputAudioFormat.channelCount != 6 && inputAudioFormat.channelCount != 8) {
            return AudioFormat.NOT_SET // NOT_SET = "no hago nada": ExoPlayer se salta este procesador
        }
        smoothingCoef = onePoleCoefficient(PARAMETER_SMOOTHING_SECONDS, inputAudioFormat.sampleRate)
        return AudioFormat(inputAudioFormat.sampleRate, 2, inputAudioFormat.encoding)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val encoding = inputAudioFormat.encoding
        val inChannels = inputAudioFormat.channelCount
        val inFrameBytes = inputAudioFormat.bytesPerFrame
        val sampleBytes = inFrameBytes / inChannels
        val start = inputBuffer.position()
        val end = inputBuffer.limit()
        val frames = (end - start) / inFrameBytes
        val output = replaceOutputBuffer(frames * outputAudioFormat.bytesPerFrame)

        // Orden de canales de Android: FL, FR, FC, LFE, BL, BR (5.1) y además SL, SR (7.1).
        val is71 = inChannels == 8
        val surroundSum = if (is71) 2 * SURROUND_GAIN else SURROUND_GAIN
        val targetCenter = targetCenterGain()
        var center = centerGain

        for (frame in 0 until frames) {
            val base = start + frame * inFrameBytes
            val fl = inputBuffer.readSample(base, encoding)
            val fr = inputBuffer.readSample(base + sampleBytes, encoding)
            val fc = inputBuffer.readSample(base + 2 * sampleBytes, encoding)
            var sl = inputBuffer.readSample(base + 4 * sampleBytes, encoding)
            var sr = inputBuffer.readSample(base + 5 * sampleBytes, encoding)
            if (is71) {
                sl += inputBuffer.readSample(base + 6 * sampleBytes, encoding)
                sr += inputBuffer.readSample(base + 7 * sampleBytes, encoding)
            }

            center = smoothingCoef * center + (1f - smoothingCoef) * targetCenter
            val normalization = 1f / (1f + center + surroundSum)
            output.writeSample((fl + center * fc + SURROUND_GAIN * sl) * normalization, encoding)
            output.writeSample((fr + center * fc + SURROUND_GAIN * sr) * normalization, encoding)
        }

        centerGain = center
        inputBuffer.position(end)
        output.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        // Tras un seek se empieza ya con el valor final, sin rampa.
        centerGain = targetCenterGain()
    }

    private fun targetCenterGain(): Float = CENTER_BASE * dbToLinear(liveProfile.current.dialogGainDb)

    private companion object {
        const val TAG = "NightPlayer"
        const val CENTER_BASE = 0.707f
        const val SURROUND_GAIN = 0.5f
    }
}
