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

    private val C_FAMILY_EXTENSIONS = setOf(
        "c", "h", "cc", "cpp", "cxx", "c++", "hh", "hpp", "hxx", "h++", "inl", "ino",
        "m", "mm", // Objective-C
        "hlsl", "cginc", "compute", "shader", "glsl", "vert", "frag", "geom", "metal", "usf", "ush", // shaders
    )

    private val JVM_EXTENSIONS = setOf(
        "java", "kt", "kts", "scala", "sc", "groovy", "gradle", "dart",
    )

    private val SWIFT_EXTENSIONS = setOf("swift")

    private val RUST_EXTENSIONS = setOf("rs")

    private val WEB_EXTENSIONS = setOf(
        "js", "jsx", "mjs", "cjs", "ts", "tsx", "mts", "cts", "go", "php",
    )

    /** Everything else where a trailing `{` is meaningful but literals hold no surprises. */
    private val PLAIN_EXTENSIONS = setOf(
        "json", "json5", "jsonc", "css", "scss", "less", "sass", "styl",
        "proto", "sql", "zig", "hcl", "tf", "tfvars", "uss",
    )

    /** Default value of the "file extensions" setting. */
    val DEFAULT_EXTENSIONS: String = buildDefaultExtensions()

    fun forExtension(extension: String): Flavor {
        val normalized = extension.lowercase()
        return when (normalized) {
            in CSHARP_EXTENSIONS -> Flavor.CSHARP
            in C_FAMILY_EXTENSIONS -> Flavor.C_FAMILY
            in JVM_EXTENSIONS -> Flavor.JVM
            in SWIFT_EXTENSIONS -> Flavor.SWIFT
            in RUST_EXTENSIONS -> Flavor.RUST
            in WEB_EXTENSIONS -> Flavor.WEB
            else -> Flavor.GENERIC
        }
    }

    private fun buildDefaultExtensions(): String {
        val all = CSHARP_EXTENSIONS +
            C_FAMILY_EXTENSIONS +
            JVM_EXTENSIONS +
            SWIFT_EXTENSIONS +
            RUST_EXTENSIONS +
            WEB_EXTENSIONS +
            PLAIN_EXTENSIONS

        return all.sorted().joinToString(",")
    }
}
