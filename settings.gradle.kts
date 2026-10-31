// Dónde buscar plugins de Gradle y dependencias (como los "package sources" de NuGet).
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral() // aquí está publicado el decodificador FFmpeg de Jellyfin
    }
}

rootProject.name = "nightplayer"
include(":app")
