package com.otoniel.nightplayer.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.otoniel.nightplayer.audio.AudioProfile
import kotlinx.coroutines.flow.first

/**
 * Una única instancia de DataStore por proceso (el delegado se encarga). DataStore es un
 * almacén clave-valor asíncrono que guarda en un fichero de la app: el equivalente moderno
 * de las SharedPreferences.
 */
internal val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Recuerda el último perfil de audio elegido. */
class ProfileStore(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    /** Funciones `suspend` = métodos async: se esperan sin bloquear el hilo (como await en C#). */
    suspend fun load(): AudioProfile {
        val saved = dataStore.data.first()[PROFILE_KEY]
        return AudioProfile.entries.firstOrNull { it.name == saved } ?: DEFAULT_PROFILE
    }

    suspend fun save(profile: AudioProfile) {
        dataStore.edit { it[PROFILE_KEY] = profile.name }
    }

    private companion object {
        val PROFILE_KEY = stringPreferencesKey("audio_profile")

        /** Primera vez: "Suave" nivela sin que el sonido parezca artificial. */
        val DEFAULT_PROFILE = AudioProfile.SOFT
    }
}
