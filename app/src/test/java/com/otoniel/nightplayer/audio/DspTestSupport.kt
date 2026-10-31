package com.otoniel.nightplayer.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/** Utilidades para alimentar procesadores con señales sintéticas en la JVM. */
internal object Dsp {
    const val SAMPLE_RATE = 48_000

    /** Tamaño de buffer parecido al que usa ExoPlayer (~10 ms). */
    private const val CHUNK_FRAMES = 480

    fun dbfs(db: Double): Float = 10.0.pow(db / 20.0).toFloat()

    fun toDb(linear: Float): Double = 20.0 * log10(max(linear.toDouble(), 1e-12))

    /** Seno intercalado en [channels] canales con amplitud de pico [amplitude]. */
    fun sine(amplitude: Float, seconds: Double, channels: Int = 2, frequency: Double = 1000.0): FloatArray {
        val frames = (seconds * SAMPLE_RATE).toInt()
        val out = FloatArray(frames * channels)
        for (f in 0 until frames) {
            val v = (amplitude * sin(2.0 * PI * frequency * f / SAMPLE_RATE)).toFloat()
            for (ch in 0 until channels) out[f * channels + ch] = v
        }
        return out
    }

    fun silence(seconds: Double, channels: Int = 2) = FloatArray((seconds * SAMPLE_RATE).toInt() * channels)

    /** Configura la cadena como haría DefaultAudioSink y devuelve los procesadores activos. */
    fun prepare(
        processors: List<AudioProcessor>,
        channels: Int,
        encoding: Int = C.ENCODING_PCM_FLOAT,
    ): List<AudioProcessor> {
        var format = AudioFormat(SAMPLE_RATE, channels, encoding)
        for (p in processors) {
            val out = p.configure(format)
            p.flush()
            if (p.isActive) format = out
        }
        return processors.filter { it.isActive }
    }

    /** Configura y pasa [input] por la cadena de procesadores. */
    fun process(
        processors: List<AudioProcessor>,
        input: FloatArray,
        channels: Int,
        encoding: Int = C.ENCODING_PCM_FLOAT,
        endOfStream: Boolean = false,
    ): FloatArray = feed(prepare(processors, channels, encoding), input, channels, encoding, endOfStream)

    /**
     * Pasa [input] por procesadores ya configurados, en trozos como haría ExoPlayer.
     * Se puede llamar varias veces seguidas: el estado interno se conserva entre llamadas.
     */
    fun feed(
        active: List<AudioProcessor>,
        input: FloatArray,
        channels: Int,
        encoding: Int = C.ENCODING_PCM_FLOAT,
        endOfStream: Boolean = false,
    ): FloatArray {
        val result = ArrayList<Float>(input.size)
        var offset = 0
        while (offset < input.size) {
            val count = minOf(CHUNK_FRAMES * channels, input.size - offset)
            var buffer = encode(input, offset, count, encoding)
            for (p in active) {
                p.queueInput(buffer)
                buffer = p.output
            }
            decodeInto(buffer, encoding, result)
            offset += count
        }
        if (endOfStream) {
            // Propaga el fin de stream procesador a procesador, como hace DefaultAudioSink.
            for ((i, p) in active.withIndex()) {
                p.queueEndOfStream()
                var buffer = p.output
                for (next in active.drop(i + 1)) {
                    next.queueInput(buffer)
                    buffer = next.output
                }
                decodeInto(buffer, encoding, result)
            }
        }
        return result.toFloatArray()
    }

    fun peak(samples: FloatArray, from: Int = 0, to: Int = samples.size): Float {
        var p = 0f
        for (i in from until to) p = max(p, abs(samples[i]))
        return p
    }

    /** Pico de la última parte de la señal (estado estacionario). */
    fun tailPeakDb(samples: FloatArray, seconds: Double, channels: Int = 2): Double {
        val n = (seconds * SAMPLE_RATE).toInt() * channels
        return toDb(peak(samples, samples.size - n, samples.size))
    }

    private fun encode(src: FloatArray, offset: Int, count: Int, encoding: Int): ByteBuffer {
        val bytes = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val buffer = ByteBuffer.allocateDirect(count * bytes).order(ByteOrder.nativeOrder())
        for (i in offset until offset + count) buffer.writeSample(src[i], encoding)
        buffer.flip()
        return buffer
    }

    private fun decodeInto(buffer: ByteBuffer, encoding: Int, out: MutableList<Float>) {
        val bytes = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        var i = buffer.position()
        while (i < buffer.limit()) {
            out.add(buffer.readSample(i, encoding))
            i += bytes
        }
        buffer.position(buffer.limit())
    }
}
