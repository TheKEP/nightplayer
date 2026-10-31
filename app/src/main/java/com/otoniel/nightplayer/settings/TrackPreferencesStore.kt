package com.otoniel.nightplayer.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * Idiomas de audio y subtítulos elegidos la última vez, para aplicarlos a la siguiente peli
 * (como hace Stremio con su "idioma preferido"). Stremio no pasa estas preferencias al
 * reproductor externo, así que las recordamos nosotros.
 */
data class TrackPreferences(
    /** Código ISO del idioma de audio (p. ej. "es"), o null si nunca se ha elegido. */
    val audioLanguage: String?,
    /** Idioma de subtítulos, [SUBTITLES_OFF] si se quitaron, o null si nunca se ha elegido. */
    val subtitleLanguage: String?,
) {
    companion object {
        const val SUBTITLES_OFF = "off"
    }
}

class TrackPreferencesStore(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    suspend fun load(): TrackPreferences {
        val prefs = dataStore.data.first()
        return TrackPreferences(prefs[AUDIO_KEY], prefs[SUBTITLE_KEY])
    }

    suspend fun saveAudioLanguage(language: String) {
        dataStore.edit { it[AUDIO_KEY] = language }
    }

    suspend fun saveSubtitleLanguage(languageOrOff: String) {
        dataStore.edit { it[SUBTITLE_KEY] = languageOrOff }
    }

    private companion object {
        val AUDIO_KEY = stringPreferencesKey("audio_language")
        val SUBTITLE_KEY = stringPreferencesKey("subtitle_language")
    }
}
