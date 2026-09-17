package com.hitapps.allmanview.scan

/**
 * Maps a file extension to a dialect.
 *
 * Dialects differ only in how string literals are built. When the extension is unknown
 * we fall back to [Flavor.GENERIC]: it understands `"..."` and `'...'`, which is safe
 * for almost any C-like syntax.
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

    /** Everything else where a trailing `{` is meaningful but literals hold no surprises. */
    private val PLAIN_EXTENSIONS = setOf(
        "json", "json5", "jsonc", "rs", "css", "scss", "less", "sass", "styl",
        "proto", "sql", "zig", "hcl", "tf", "tfvars", "uss",
    )

    /** Default value of the "file extensions" setting. */
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
