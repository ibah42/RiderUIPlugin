import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.hitapps"
version = "0.4.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(
            providers.gradleProperty("platformType"),
            providers.gradleProperty("platformVersion"),
        )
        testFramework(TestFrameworkType.Platform)
    }
    // kotlin-stdlib сознательно не подключаем: его даёт сама IDE
    // (см. kotlin.stdlib.default.dependency=false в gradle.properties).
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    // Платформа 2026.x работает на JBR 21 — собираем под неё, а не под JDK, которым запущен Gradle.
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // untilBuild намеренно не задаём — берётся дефолт от целевой сборки.
            // Когда выйдет следующий Rider, поднять platformVersion и пересобрать.
        }
    }
    buildSearchableOptions = false
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
