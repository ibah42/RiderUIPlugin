package com.hitapps.allmanview.scan

/**
 * Сопоставление расширения файла и диалекта.
 *
 * Разница между диалектами — только в устройстве строковых литералов. Если расширение
 * неизвестно, берём [Flavor.GENERIC]: он понимает `"..."` и `'...'` и потому безопасен
 * почти для любого C-подобного синтаксиса.
 */
object Dialects {

    private val CSHARP_EXTENSIONS = setOf("cs", "csx")

    private val CPP_EXTENSIONS = setOf(
        "c", "h", "cc", "cpp", "cxx", "c++", "hh", "hpp", "hxx", "h++", "inl", "ino",
        "m", "mm", // Objective-C
        "hlsl", "cginc", "compute", "shader", "glsl", "vert", "frag", "geom", "metal", "usf", "ush",
    )

    private val JVM_EXTENSIONS = setOf(
        "java", "kt", "kts", "scala", "sc", "groovy", "gradle", "swift", "dart",
    )

    private val WEB_EXTENSIONS = setOf(
        "js", "jsx", "mjs", "cjs", "ts", "tsx", "mts", "cts", "go", "php",
    )

    /** Прочее, где `{` в конце строки осмысленна, но экзотики в литералах нет. */
    private val PLAIN_EXTENSIONS = setOf(
        "json", "json5", "jsonc", "rs", "css", "scss", "less", "sass", "styl",
        "proto", "sql", "zig", "hcl", "tf", "tfvars", "uss",
    )

    /** Список по умолчанию для настройки «расширения файлов». */
    val DEFAULT_EXTENSIONS: String = buildDefaultExtensions()

    fun forExtension(extension: String): Flavor {
        val normalized = extension.lowercase()
        return when (normalized) {
            in CSHARP_EXTENSIONS -> Flavor.CSHARP
            in CPP_EXTENSIONS -> Flavor.CPP
            in JVM_EXTENSIONS -> Flavor.JVM
            in WEB_EXTENSIONS -> Flavor.WEB
            else -> Flavor.GENERIC
        }
    }

    private fun buildDefaultExtensions(): String {
        val all = CSHARP_EXTENSIONS +
            CPP_EXTENSIONS +
            JVM_EXTENSIONS +
            WEB_EXTENSIONS +
            PLAIN_EXTENSIONS

        return all.sorted().joinToString(",")
    }
}
