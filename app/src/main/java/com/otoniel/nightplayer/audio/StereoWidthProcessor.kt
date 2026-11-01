package com.otoniel.nightplayer.audio

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * Ensancha la imagen estéreo en mid/side para compensar el estrechamiento del modo noche:
 * el central reforzado suena igual en ambos altavoces y el compresor iguala los niveles,
 * así que lo que solo está a un lado (ambiente, efectos panoramizados) pierde presencia.
 *
 *   M = (L + R) / 2     lo común a los dos lados: diálogo, central
 *   S = (L − R) / 2     lo que solo está a un lado
 *   L' = M + w·S,  R' = M − w·S
 *
 * Se calcula como `L' = L + (w − 1)·S`, que con w = 1 deja la señal bit a bit igual. El
 * diálogo (M) no cambia. Va antes del limitador, que recoge los picos que pueda añadir.
 *
 * Solo actúa sobre estéreo: mono no tiene lados y el multicanal ya llega convertido por el
 * downmix.
 */
class StereoWidthProcessor(private val liveProfile: LiveAudioProfile) : BaseAudioProcessor() {

    /** Anchura ya suavizada (cambia poco a poco al cambiar de perfil). */
    private var width = 1f
    private var smoothingCoef = 0f

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (!isSupportedEncoding(inputAudioFormat.encoding) || inputAudioFormat.channelCount != 2) {
            return AudioFormat.NOT_SET
        }
        smoothingCoef = onePoleCoefficient(PARAMETER_SMOOTHING_SECONDS, inputAudioFormat.sampleRate)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val encoding = inputAudioFormat.encoding
        val sampleBytes = inputAudioFormat.bytesPerFrame / 2
        val start = inputBuffer.position()
        val end = inputBuffer.limit()
        val output = replaceOutputBuffer(end - start)
        val targetWidth = liveProfile.current.stereoWidth
        var w = width

        var frameStart = start
        while (frameStart < end) {
            val l = inputBuffer.readSample(frameStart, encoding)
            val r = inputBuffer.readSample(frameStart + sampleBytes, encoding)
            w = smoothingCoef * w + (1f - smoothingCoef) * targetWidth
            val extraSide = (w - 1f) * (l - r) * 0.5f
            output.writeSample(l + extraSide, encoding)
            output.writeSample(r - extraSide, encoding)
            frameStart += inputAudioFormat.bytesPerFrame
        }

        width = w
        inputBuffer.position(end)
        output.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        // Tras un seek se empieza ya con el valor final, sin rampa.
        width = liveProfile.current.stereoWidth
    }
}
