import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 trae Kotlin integrado: no hace falta el plugin org.jetbrains.kotlin.android.
}

// La firma de release se lee de local.properties (no versionado) o de variables de entorno,
// para que el keystore y sus contraseñas nunca acaben en el repositorio.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(propKey: String, envKey: String): String? =
    localProps.getProperty(propKey) ?: System.getenv(envKey)

val releaseStoreFile = signingValue("nightplayer.storeFile", "NIGHTPLAYER_STORE_FILE")

android {
    namespace = "com.otoniel.nightplayer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.otoniel.nightplayer"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.0"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingValue("nightplayer.storePassword", "NIGHTPLAYER_STORE_PASSWORD")
                keyAlias = signingValue("nightplayer.keyAlias", "NIGHTPLAYER_KEY_ALIAS")
                keyPassword = signingValue("nightplayer.keyPassword", "NIGHTPLAYER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Sin R8 de momento: evita problemas con las clases JNI del decodificador FFmpeg.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    testOptions {
        // Los tests del DSP corren en la JVM del PC; si alguna clase de Media3 toca la API
        // de Android, que devuelva valores por defecto en vez de fallar.
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

if (releaseStoreFile == null) {
    logger.warn("NightPlayer: no hay keystore de release configurado; el APK de release saldrá sin firmar. Ver README.md.")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls) // algunos addons de Stremio sirven HLS (.m3u8)
    implementation(libs.media3.ui)
    // Decodificador FFmpeg (AC3, E-AC3, DTS, TrueHD...) compilado por Jellyfin. Licencia GPL-3.0.
    implementation(libs.jellyfin.media3.ffmpeg)

    testImplementation(libs.junit)
}
