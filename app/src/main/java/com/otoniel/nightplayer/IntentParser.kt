package com.otoniel.nightplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log

/** Lo que hay que reproducir, extraído del intent que manda Stremio (u otra app). */
data class PlaybackRequest(
    val uri: Uri,
    val mimeType: String?,
    val title: String?,
    /** Posición inicial en ms, o null si no viene. */
    val startPositionMs: Long?,
    val subtitles: List<ExternalSubtitle>,
)

data class ExternalSubtitle(
    val uri: Uri,
    val name: String?,
    /** Si debe aparecer activado al empezar. */
    val enabled: Boolean,
)

/**
 * Lee el intent ACTION_VIEW de forma tolerante.
 *
 * Qué extras manda Stremio no está documentado. Seguimos la convención de MX Player / VLC
 * (title, position, subs, subs.name, subs.enable) como hipótesis y aceptamos tipos
 * alternativos (Int o Long, String o Uri, arrays o listas). Todo lo que llega se escribe en
 * Logcat con [logIntent] para poder ajustar esto con datos reales.
 */
object IntentParser {

    private const val TAG = "NightPlayer"

    const val EXTRA_TITLE = "title"
    const val EXTRA_POSITION = "position"

    /** Stremio (Android TV) manda la posición dos veces: en "position" y en "startfrom". */
    const val EXTRA_START_FROM = "startfrom"
    const val EXTRA_SUBS = "subs"
    const val EXTRA_SUBS_NAME = "subs.name"
    const val EXTRA_SUBS_ENABLE = "subs.enable"

    fun parse(intent: Intent): PlaybackRequest? {
        val uri = intent.data ?: return null
        val extras = intent.extras

        val title = extras?.anyValue(EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
            ?: extras?.anyValue(Intent.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.let(::titleFromFileName)

        val position = (extras?.anyValue(EXTRA_POSITION) ?: extras?.anyValue(EXTRA_START_FROM))
            ?.let(::toLong)?.takeIf { it > 0 }

        val subUris = toUriList(extras?.anyValue(EXTRA_SUBS))
        val subNames = toStringList(extras?.anyValue(EXTRA_SUBS_NAME))
        val enabled = toUriList(extras?.anyValue(EXTRA_SUBS_ENABLE)).map { it.toString() }.toSet()
        val subtitles = subUris.mapIndexed { i, subUri ->
            ExternalSubtitle(subUri, subNames.getOrNull(i), subUri.toString() in enabled)
        }

        return PlaybackRequest(uri, intent.type, title, position, subtitles)
    }

    /** Vuelca a Logcat todo el intent: acción, URL, tipo, categorías y cada extra con su tipo. */
    fun logIntent(intent: Intent) {
        Log.i(TAG, "Intent recibido: action=${intent.action} data=${intent.data} type=${intent.type}")
        Log.i(TAG, "  categorías=${intent.categories} flags=0x${Integer.toHexString(intent.flags)}")
        val extras = intent.extras
        if (extras == null || extras.isEmpty) {
            Log.i(TAG, "  (sin extras)")
            return
        }
        for (key in extras.keySet().sorted()) {
            val value = extras.anyValue(key)
            Log.i(TAG, "  extra '$key' (${value?.javaClass?.name}) = ${describe(value)}")
        }
    }

    /**
     * Stremio no manda título: lo sacamos del nombre del fichero de la URL.
     * "Big.Buck.Bunny(2008)[MULTI ESP-ENG][HDR10 HEVC]-Grupo.mkv" → "Big Buck Bunny (2008)".
     * Se quita la extensión, todo lo que va entre corchetes y los puntos/guiones bajos.
     */
    internal fun titleFromFileName(fileName: String): String? {
        val cleaned = fileName
            .substringBeforeLast('.', fileName)
            .replace(Regex("\\[[^]]*]"), " ")
            .replace(Regex("[._]+"), " ")
            .replace("(", " (")
            .replace(Regex("\\s+-\\s*\\S*$"), "") // "-Grupo" del final
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.ifBlank { fileName.takeIf { it.isNotBlank() } }
    }

    /** Bundle.get(key) está obsoleto porque no es type-safe, que es justo lo que queremos aquí. */
    @Suppress("DEPRECATION")
    private fun Bundle.anyValue(key: String): Any? = get(key)

    private fun toLong(value: Any): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull() ?: value.trim().toDoubleOrNull()?.toLong()
        else -> null
    }

    private fun toUriList(value: Any?): List<Uri> = elements(value).mapNotNull {
        when (it) {
            is Uri -> it
            is String -> it.takeIf(String::isNotBlank)?.let(Uri::parse)
            else -> null
        }
    }

    private fun toStringList(value: Any?): List<String?> = elements(value).map { it?.toString() }

    /** Acepta un valor suelto, un array (Parcelable[] o String[]) o una lista. */
    private fun elements(value: Any?): List<Any?> = when (value) {
        null -> emptyList()
        is Array<*> -> value.toList()
        is Iterable<*> -> value.toList()
        else -> listOf(value)
    }

    private fun describe(value: Any?): String = when (value) {
        is Array<*> -> value.contentToString()
        is IntArray -> value.contentToString()
        is LongArray -> value.contentToString()
        else -> value.toString()
    }
}
