package com.hitapps.allmanview.scan

/**
 * The vocabulary the scanner speaks: what it is asked to do, and what it hands back.
 *
 * Data only, no behaviour, and no dependency on anything outside the standard library -- the
 * whole `scan` package is a pure function of the text, so it runs in a plain unit test.
 */

/**
 * Lexer dialect. It only exists to find string literal boundaries correctly; everything else
 * is the same across C-like languages.
 */
enum class Flavor {
    /** C#: `@"verbatim"`, `"""raw"""`, `$"interp"` */
    CSHARP,

    /** C/C++/shaders: `R"delim(raw)delim"`, `1'000'000` */
    CPP,

    /** Java, Kotlin, Scala, Groovy, Swift, Dart: `"""` text blocks */
    JVM,

    /** JS, TS, Go, PHP: `` `template ${literals}` `` */
    WEB,

    /** Everything else: only `"..."` and `'...'`. The safe default. */
    GENERIC,
}

/** Who a curly block belongs to. */
enum class BlockKind {
    /** class, struct, interface, enum, record. */
    TYPE,

    /** A method, constructor or local function declaration. */
    FUNCTION,

    /**
     * A `namespace` block. A kind of its own rather than a flag on [OTHER]: it then flows
     * through the same accent pipeline as the two above -- colour, weight, shadow, label --
     * and a namespace inside a namespace counts as nested, exactly like a type inside a type.
     */
    NAMESPACE,

    /** Everything else: if, loops, lambdas, initializers, properties. */
    OTHER,
}

/**
 * A brace that should stand out more than the rest.
 *
 * @param offset offset of the brace itself in the document
 * @param kind which kind of block it belongs to
 * @param nameOffset offset of the type or method name the base colour comes from; -1 if none
 * @param nameLength length of that name
 * @param keyword what to print in the label: `class`, `struct`, `fun`
 * @param isOpening whether this is the opening or the closing brace
 * @param spannedLines how many lines the block covers; always 0 for the opening brace
 * @param isNested another block of the same kind encloses this one: a type inside a type, or a
 *   local function inside a function. Any depth counts, so only the outermost one is not nested.
 * @param isLambda the block is a lambda or an anonymous delegate rather than a declared function
 * @param headerOffset offset where the declaration itself starts, which for a multi-line
 *   signature is well before the brace; -1 when the block has no header
 * @param siblingOrdinal 1-based position among this block's type/namespace siblings in the same
 *   container -- the file, or the nearest enclosing namespace, type or function -- once that
 *   container has two or more of them; 0 when there is nothing to number
 */
data class BraceAccent(
    val offset: Int,
    val kind: BlockKind,
    val nameOffset: Int,
    val nameLength: Int,
    val keyword: String,
    val isOpening: Boolean,
    val spannedLines: Int,
    val isNested: Boolean = false,
    val isLambda: Boolean = false,
    val headerOffset: Int = -1,
    val siblingOrdinal: Int = 0,
)

/** What exactly to split. */
data class ScanOptions(
    /** Split `} else {` into three lines, not just the hanging `{`. */
    val fullAllman: Boolean = true,

    /** Split `if (x) return;` into two lines. */
    val splitStatements: Boolean = true,

    /** Expand `if (x) { Foo(); }` into four lines. */
    val expandInlineBlocks: Boolean = true,

    /** Mark type braces: class, struct, interface, enum, record. */
    val accentTypes: Boolean = true,

    /**
     * Master switch for every kind of function block. The five flags below narrow it further:
     * each one decides whether that sub-kind is a block the scanner reports at all, so turning
     * one off removes its colour and its shadow along with its label, and takes it out of the
     * nesting and sibling bookkeeping too.
     */
    val accentFunctions: Boolean = true,

    /** Ordinary methods and local functions -- the `fun` label. */
    val accentMethods: Boolean = true,

    /** Constructors, static constructors and destructors -- `ctor`, `static ctor`, `dtor`. */
    val accentConstructors: Boolean = true,

    /** A property's own block -- the `prop` label, not its accessors. */
    val accentProperties: Boolean = true,

    /** Property accessor bodies -- `get`, `set`, `init`. */
    val accentAccessors: Boolean = true,

    /** Lambda and anonymous-delegate bodies. */
    val accentLambdas: Boolean = true,

    /** Mark namespace braces. */
    val accentNamespaces: Boolean = true,
)

/**
 * A single phantom line.
 *
 * The text is always a contiguous slice of the document, so the renderer can fetch its real
 * highlighting: the character at index `i` lives at `sourceOffset + i` in the document.
 *
 * @param text what to draw
 * @param sourceOffset offset of that text in the document
 * @param extraIndentLevels how many levels deeper than the owner line's indent.
 *   Levels rather than spaces: only the editor knows how wide a level is, and
 *   `Graphics.drawString` does not expand a tab, so the indent would vanish.
 */
data class PhantomLine(
    val text: String,
    val sourceOffset: Int,
    val extraIndentLevels: Int = 0,
)

/**
 * One place where a line is visually broken into Allman style.
 *
 * The original text is not hidden: it stays where it is and is dimmed to grey, while the
 * phantom is drawn underneath in the normal colour.
 *
 * @param dimStart offset of the first dimmed character
 * @param dimEnd offset just past the dimmed slice (exclusive)
 * @param anchorOffset offset of the owner line's end, the anchor for the block inlay
 * @param indent leading whitespace of the owner line, verbatim (tabs/spaces)
 * @param phantomLines what to draw as phantom lines, top to bottom
 */
data class PhantomSite(
    val dimStart: Int,
    val dimEnd: Int,
    val anchorOffset: Int,
    val indent: String,
    val phantomLines: List<PhantomLine>,
) {
    /** Phantom texts only, handy in tests and logs. */
    val phantomTexts: List<String>
        get() = phantomLines.map { it.text }
}

/** The result of one pass over the document. */
data class ScanResult(
    val sites: List<PhantomSite>,
    val accents: List<BraceAccent>,
)
