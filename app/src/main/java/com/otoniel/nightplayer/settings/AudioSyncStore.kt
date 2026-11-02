package com.otoniel.nightplayer.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * Desfase de sincronía (ms) de cada salida de audio: unos auriculares Bluetooth necesitan el
 * suyo y los altavoces de la tele normalmente 0, así que se guarda por dispositivo.
 */
class AudioSyncStore(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    suspend fun load(outputKey: String): Int = dataStore.data.first()[keyFor(outputKey)] ?: 0

    suspend fun save(outputKey: String, offsetMs: Int) {
        dataStore.edit {
            if (offsetMs == 0) it.remove(keyFor(outputKey)) else it[keyFor(outputKey)] = offsetMs
        }
    }

    private fun keyFor(outputKey: String) = intPreferencesKey("audio_sync:$outputKey")
}
