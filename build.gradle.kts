import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.hitapps"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(
            IntelliJPlatformType.fromCode(providers.gradleProperty("platformType").get()),
            providers.gradleProperty("platformVersion").get(),
        )
        testFramework(TestFrameworkType.Platform)
    }
    implementation(kotlin("stdlib"))
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // Верхнюю границу не фиксируем — иначе плагин будет отваливаться на каждом апдейте Rider.
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
}

tasks.test {
    useJUnit()
}
