package com.otoniel.nightplayer.player

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** Salida de audio en uso. [key] identifica el dispositivo para recordar su sincronía. */
data class AudioOutput(val key: String, val name: String?)

object AudioOutputs {

    /** Dispositivo por el que sale ahora el audio de una peli, o null si no hay ninguno. */
    fun current(audioManager: AudioManager): AudioOutput? {
        val device = routedDevice(audioManager) ?: return null
        val name = device.productName?.toString()?.trim().orEmpty()
        return AudioOutput(key = "${device.type}:$name", name = name.ifEmpty { null })
    }

    private fun routedDevice(audioManager: AudioManager): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
            audioManager.getAudioDevicesForAttributes(attributes).firstOrNull()?.let { return it }
        }
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outputs.getOrNull(preferredIndex(outputs.map { it.type }))
    }

    /**
     * Antes de Android 13 no se puede preguntar por dónde sale el audio: se imita la prioridad
     * de Android (Bluetooth, luego cable/USB, luego HDMI y altavoz). Devuelve -1 si no hay salidas.
     */
    internal fun preferredIndex(types: List<Int>): Int =
        PRIORITY.firstNotNullOfOrNull { type -> types.indexOf(type).takeIf { it >= 0 } }
            ?: if (types.isEmpty()) -1 else 0

    // Los tipos más nuevos que el sistema simplemente no aparecen en dispositivos antiguos.
    @SuppressLint("InlinedApi")
    private val PRIORITY = listOf(
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_HDMI_EARC,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    )
}
