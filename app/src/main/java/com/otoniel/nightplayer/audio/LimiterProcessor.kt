package com.otoniel.nightplayer.audio

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Limitador de picos con lookahead: garantiza que ninguna muestra supera el techo
 * (−1 dBFS) aunque el compresor llegue tarde a un transitorio.
 *
 * "Lookahead" significa que retrasamos el audio 5 ms; así vemos el pico antes de que
 * salga y podemos bajar la ganancia en rampa (sin chasquidos) justo a tiempo.
 *
 * Cómo se calcula la ganancia, para cada frame n (todo con arrays reservados en
 * onConfigure, sin asignaciones de memoria en queueInput):
 *  1. Ganancia necesaria del frame: techo / pico (o 1 si no pasa del techo).
 *  2. Mínimo de esa ganancia en la ventana de lookahead (L frames) → m.
 *  3. Release exponencial de 80 ms: baja al instante, sube despacio → r (siempre ≤ m).
 *  4. Media móvil de r sobre L frames → g. Es una rampa suave de 5 ms.
 *
 * Como cada valor promediado es el mínimo de una ventana que contiene el frame que sale
 * ahora (retrasado L−1 frames), la media nunca supera la ganancia que ese frame necesita.
 * Aun así, se recorta al techo al final como red de seguridad ante errores de redondeo.
 *
 * Efecto secundario: el audio se retrasa ~5 ms, inapreciable frente al vídeo.
 */
class LimiterProcessor(private val liveProfile: LiveAudioProfile) : BaseAudioProcessor() {

    private var lookaheadFrames = 0
    private var channels = 0
    private var releaseCoef = 0f

    // Línea de retardo: L frames intercalados.
    private var delayLine = FloatArray(0)

    // Cola monótona para el mínimo deslizante (ventana de L valores).
    private var minValues = FloatArray(0)
    private var minFrameIndex = LongArray(0)
    private var minHead = 0
    private var minSize = 0

    // Media móvil de r.
    private var boxValues = FloatArray(0)
    private var boxSum = 0.0

    private var released = 1f
    private var frameCounter = 0L

    /** Muestras del frame actual (reutilizado, evita asignar memoria). */
    private var frameScratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (!isSupportedEncoding(inputAudioFormat.encoding)) return AudioFormat.NOT_SET
        channels = inputAudioFormat.channelCount
        lookaheadFrames = max(2, (LOOKAHEAD_SECONDS * inputAudioFormat.sampleRate).roundToInt())
        releaseCoef = onePoleCoefficient(RELEASE_SECONDS, inputAudioFormat.sampleRate)
        delayLine = FloatArray(lookaheadFrames * channels)
        minValues = FloatArray(lookaheadFrames)
        minFrameIndex = LongArray(lookaheadFrames)
        boxValues = FloatArray(lookaheadFrames)
        frameScratch = FloatArray(channels)
        resetState()
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Un buffer vacío (ExoPlayer los manda, también tras el fin de stream) no debe
        // sustituir la salida pendiente: perderíamos los últimos 5 ms vaciados de la línea de retardo.
        if (!inputBuffer.hasRemaining()) return
        val encoding = inputAudioFormat.encoding
        val sampleBytes = inputAudioFormat.bytesPerFrame / channels
        val start = inputBuffer.position()
        val end = inputBuffer.limit()
        val output = replaceOutputBuffer(end - start)
        val ceiling = currentCeiling()

        var frameStart = start
        while (frameStart < end) {
            for (ch in 0 until channels) {
                frameScratch[ch] = inputBuffer.readSample(frameStart + ch * sampleBytes, encoding)
            }
            processFrame(output, encoding, ceiling)
            frameStart += inputAudioFormat.bytesPerFrame
        }
        inputBuffer.position(end)
        output.flip()
    }

    /** Al acabar el stream vaciamos la línea de retardo para no perder los últimos 5 ms. */
    override fun onQueueEndOfStream() {
        val pendingFrames = lookaheadFrames - 1
        val output = replaceOutputBuffer(pendingFrames * inputAudioFormat.bytesPerFrame)
        val ceiling = currentCeiling()
        frameScratch.fill(0f)
        repeat(pendingFrames) { processFrame(output, inputAudioFormat.encoding, ceiling) }
        output.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) = resetState()

    private fun processFrame(output: ByteBuffer, encoding: Int, ceiling: Float) {
        val n = frameCounter
        val window = lookaheadFrames

        // 1. Ganancia necesaria para el frame que acaba de entrar.
        var peak = 0f
        for (ch in 0 until channels) peak = max(peak, abs(frameScratch[ch]))
        val required = if (peak > ceiling) ceiling / peak else 1f

        // 2. Mínimo deslizante: se descartan por delante los valores que han salido de la
        //    ventana y por detrás los que ya no pueden ser mínimo; luego se añade el nuevo.
        while (minSize > 0 && minFrameIndex[minHead] <= n - window) {
            minHead = (minHead + 1) % window
            minSize--
        }
        while (minSize > 0 && minValues[(minHead + minSize - 1) % window] >= required) minSize--
        val tail = (minHead + minSize) % window
        minValues[tail] = required
        minFrameIndex[tail] = n
        minSize++
        val windowMin = minValues[minHead]

        // 3. Release: baja inmediatamente, sube con constante de 80 ms.
        released = if (windowMin < released) windowMin else releaseCoef * released + (1f - releaseCoef) * windowMin

        // 4. Media móvil de L valores.
        val slot = (n % window).toInt()
        boxSum += released - boxValues[slot]
        boxValues[slot] = released
        val gain = (boxSum / window).toFloat()

        // Línea de retardo: guardamos el frame nuevo y sacamos el de hace L−1 frames.
        val writeBase = slot * channels
        val readBase = ((n + 1) % window).toInt() * channels
        for (ch in 0 until channels) delayLine[writeBase + ch] = frameScratch[ch]
        for (ch in 0 until channels) {
            val limited = (delayLine[readBase + ch] * gain).coerceIn(-ceiling, ceiling)
            output.writeSample(limited, encoding)
        }
        frameCounter = n + 1
    }

    /** Con el limitador "apagado" el techo es infinito: nunca hace falta reducir. */
    private fun currentCeiling(): Float =
        if (liveProfile.current.limiterEnabled) dbToLinear(AudioProfile.LIMITER_CEILING_DB) else Float.MAX_VALUE

    private fun resetState() {
        delayLine.fill(0f)
        boxValues.fill(1f)
        boxSum = lookaheadFrames.toDouble()
        minHead = 0
        minSize = 0
        released = 1f
        frameCounter = 0L
    }

    private companion object {
        const val LOOKAHEAD_SECONDS = 0.005f
        const val RELEASE_SECONDS = 0.080f
    }
}
