rootProject.name = "allman-view"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Позволяет Gradle самому скачать JDK 21 под toolchain, если его нет в системе.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
