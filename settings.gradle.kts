pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        // Fija la versión del plugin de Google Services para que el módulo app lo resuelva sin classpath
        id("com.google.gms.google-services") version "4.4.2"
    }
}

dependencyResolutionManagement {
    // Esta línea hace que Gradle prefiera repos definidos aquí (evita el error que viste)
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "EspaldApp"
include(":app")