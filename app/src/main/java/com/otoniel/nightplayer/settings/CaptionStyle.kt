package com.otoniel.nightplayer.settings

import android.content.Context
import android.graphics.Color
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat

/**
 * Estilo de los subtítulos. Cada opción se guarda como un índice en su lista, así que
 * añadir un color o un tamaño nuevo es tocar solo las listas de abajo.
 */
@UnstableApi
data class CaptionStyle(
    val colorIndex: Int = 0,
    val backgroundIndex: Int = BACKGROUNDS.lastIndex, // negro sólido: el aspecto de siempre
    val edgeIndex: Int = 0,
    val bold: Boolean = false,
    val sizeIndex: Int = 1, // 100 %
    val embeddedStyles: Boolean = false,
) {
    val textColor: Int get() = COLORS[colorIndex].second
    val backgroundColor: Int get() = Color.argb(BACKGROUNDS[backgroundIndex].second, 0, 0, 0)
    val edgeType: Int get() = EDGES[edgeIndex].second
    val sizeFactor: Float get() = SIZES[sizeIndex].second

    /** Etiqueta del valor actual de cada fila (ver ROW_LABELS). */
    fun valueLabel(row: Int): String = when (row) {
        ROW_COLOR -> COLORS[colorIndex].first
        ROW_BACKGROUND -> BACKGROUNDS[backgroundIndex].first
        ROW_EDGE -> EDGES[edgeIndex].first
        ROW_BOLD -> if (bold) "On" else "Off"
        ROW_SIZE -> SIZES[sizeIndex].first
        else -> if (embeddedStyles) "On" else "Off"
    }

    /** Mueve el valor de una fila una posición (con vuelta al principio/final). */
    fun step(row: Int, delta: Int): CaptionStyle = when (row) {
        ROW_COLOR -> copy(colorIndex = wrap(colorIndex + delta, COLORS.size))
        ROW_BACKGROUND -> copy(backgroundIndex = wrap(backgroundIndex + delta, BACKGROUNDS.size))
        ROW_EDGE -> copy(edgeIndex = wrap(edgeIndex + delta, EDGES.size))
        ROW_BOLD -> copy(bold = !bold)
        ROW_SIZE -> copy(sizeIndex = wrap(sizeIndex + delta, SIZES.size))
        else -> copy(embeddedStyles = !embeddedStyles)
    }

    companion object {
        const val ROW_COLOR = 0
        const val ROW_BACKGROUND = 1
        const val ROW_EDGE = 2
        const val ROW_BOLD = 3
        const val ROW_SIZE = 4
        const val ROW_EMBEDDED = 5
        val ROW_LABELS = listOf("Text color", "Background", "Edge", "Bold", "Size", "File styling")

        val COLORS = listOf(
            "White" to 0xFFFFFFFF.toInt(),
            "Yellow" to 0xFFFFEB3B.toInt(),
            "Green" to 0xFF69F0AE.toInt(),
            "Cyan" to 0xFF18FFFF.toInt(),
            "Magenta" to 0xFFFF4081.toInt(),
        )
        // Opacidad del fondo (0–255).
        val BACKGROUNDS = listOf("Off" to 0, "Light" to 100, "Medium" to 170, "Solid" to 255)
        val EDGES = listOf(
            "None" to CaptionStyleCompat.EDGE_TYPE_NONE,
            "Outline" to CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            "Shadow" to CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
        )
        val SIZES = listOf("75%" to 0.75f, "100%" to 1f, "125%" to 1.25f, "150%" to 1.5f, "200%" to 2f)

        private fun wrap(i: Int, size: Int) = ((i % size) + size) % size
    }
}

/** Guarda el estilo en SharedPreferences: es diminuto, se lee de forma síncrona al arrancar. */
@UnstableApi
class CaptionStyleStore(context: Context) {
    private val prefs = context.getSharedPreferences("caption_style", Context.MODE_PRIVATE)

    fun load(): CaptionStyle {
        val d = CaptionStyle()
        return CaptionStyle(
            colorIndex = prefs.getInt("color", d.colorIndex).coerceIn(0, CaptionStyle.COLORS.lastIndex),
            backgroundIndex = prefs.getInt("background", d.backgroundIndex)
                .coerceIn(0, CaptionStyle.BACKGROUNDS.lastIndex),
            edgeIndex = prefs.getInt("edge", d.edgeIndex).coerceIn(0, CaptionStyle.EDGES.lastIndex),
            bold = prefs.getBoolean("bold", d.bold),
            sizeIndex = prefs.getInt("size", d.sizeIndex).coerceIn(0, CaptionStyle.SIZES.lastIndex),
            embeddedStyles = prefs.getBoolean("embedded", d.embeddedStyles),
        )
    }

    fun save(s: CaptionStyle) {
        prefs.edit()
            .putInt("color", s.colorIndex)
            .putInt("background", s.backgroundIndex)
            .putInt("edge", s.edgeIndex)
            .putBoolean("bold", s.bold)
            .putInt("size", s.sizeIndex)
            .putBoolean("embedded", s.embeddedStyles)
            .apply()
    }
}