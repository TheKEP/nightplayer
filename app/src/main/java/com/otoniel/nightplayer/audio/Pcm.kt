package com.otoniel.nightplayer.audio

import androidx.media3.common.C
import java.nio.ByteBuffer
import kotlin.math.exp
import kotlin.math.pow

/*
 * Utilidades compartidas por los procesadores.
 *
 * ExoPlayer entrega el audio como bytes PCM intercalados (L R L R ... en estéreo) dentro
 * de un ByteBuffer. Trabajamos internamente en Float [-1, 1] y escribimos de vuelta en la
 * misma codificación que llegó: 16 bits con signo o float de 32 bits.
 */

internal fun isSupportedEncoding(encoding: Int): Boolean =
    encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT

internal fun encodingName(encoding: Int): String = when (encoding) {
    C.ENCODING_PCM_16BIT -> "PCM_16BIT"
    C.ENCODING_PCM_FLOAT -> "PCM_FLOAT"
    else -> "otra ($encoding)"
}

/** Lee la muestra que empieza en el byte absoluto [byteIndex] (no mueve la posición). */
@Suppress("NOTHING_TO_INLINE")
internal inline fun ByteBuffer.readSample(byteIndex: Int, encoding: Int): Float =
    if (encoding == C.ENCODING_PCM_FLOAT) getFloat(byteIndex) else getShort(byteIndex) / 32768f

/** Escribe una muestra en la posición actual del buffer (y la avanza). */
@Suppress("NOTHING_TO_INLINE")
internal inline fun ByteBuffer.writeSample(value: Float, encoding: Int) {
    if (encoding == C.ENCODING_PCM_FLOAT) {
        putFloat(value)
    } else {
        // Recorte a 16 bits: sin él, un valor > 1 daría la vuelta y sonaría como un chasquido.
        putShort((value * 32768f).coerceIn(-32768f, 32767f).toInt().toShort())
    }
}

internal fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)

/**
 * Coeficiente de un filtro de un polo (media móvil exponencial) con constante de tiempo
 * [timeSeconds]: `y = coef * y + (1 - coef) * x`. Tras `timeSeconds` recorre el 63 % del
 * camino; tras 3 veces ese tiempo, el 95 %.
 */
internal fun onePoleCoefficient(timeSeconds: Float, sampleRate: Int): Float =
    if (timeSeconds <= 0f) 0f else exp(-1.0 / (timeSeconds * sampleRate)).toFloat()

/** Los cambios de perfil llegan al 95 % en ~50 ms (3 constantes de tiempo): sin clics. */
internal const val PARAMETER_SMOOTHING_SECONDS = 0.050f / 3f
