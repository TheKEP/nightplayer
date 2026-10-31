package com.otoniel.nightplayer.audio

import androidx.annotation.StringRes
import com.otoniel.nightplayer.R

/**
 * Perfiles de nivelación de volumen (valores de partida del plan, a ajustar a oído).
 *
 * "Desactivado" no apaga los procesadores: es un perfil con valores neutros
 * (ganancias a 0 dB y ratio 1:1). Así cambiar de perfil nunca reconfigura el reproductor
 * ni corta el audio.
 */
enum class AudioProfile(
    @param:StringRes val labelRes: Int,
    /** Refuerzo del canal central (diálogos) en el downmix 5.1/7.1 → estéreo. */
    val dialogGainDb: Float,
    /** Umbral del compresor: por encima de este nivel RMS empieza a reducir. */
    val thresholdDb: Float,
    /** Ratio del compresor: 6 significa que 6 dB por encima del umbral salen como 1 dB. */
    val ratio: Float,
    val attackMs: Float,
    val releaseMs: Float,
    /** Ganancia de compensación tras comprimir, para que los diálogos suban. */
    val makeupDb: Float,
    val limiterEnabled: Boolean,
) {
    OFF(
        R.string.profile_off, dialogGainDb = 0f, thresholdDb = 0f, ratio = 1f,
        attackMs = 10f, releaseMs = 200f, makeupDb = 0f, limiterEnabled = false,
    ),
    SOFT(
        R.string.profile_soft, dialogGainDb = 3f, thresholdDb = -24f, ratio = 3f,
        attackMs = 10f, releaseMs = 200f, makeupDb = 4f, limiterEnabled = true,
    ),
    NIGHT(
        R.string.profile_night, dialogGainDb = 6f, thresholdDb = -30f, ratio = 6f,
        attackMs = 5f, releaseMs = 300f, makeupDb = 8f, limiterEnabled = true,
    );

    /** Siguiente perfil en el ciclo Desactivado → Suave → Noche → Desactivado. */
    fun next(): AudioProfile = entries[(ordinal + 1) % entries.size]

    companion object {
        /** Techo del limitador en dBFS. */
        const val LIMITER_CEILING_DB = -1f
    }
}

/**
 * Perfil activo compartido entre la UI (hilo principal) y los procesadores de audio
 * (hilo de reproducción de ExoPlayer).
 *
 * @Volatile garantiza que el hilo de audio ve el cambio en el siguiente buffer sin locks,
 * igual que un campo `volatile` en C#. Como AudioProfile es inmutable, basta con
 * sustituir la referencia.
 */
class LiveAudioProfile(initial: AudioProfile = AudioProfile.OFF) {
    @Volatile
    var current: AudioProfile = initial
}
