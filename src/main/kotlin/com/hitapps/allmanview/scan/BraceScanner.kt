package com.hitapps.allmanview.scan

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
 */
data class BraceAccent(
    val offset: Int,
    val kind: BlockKind,
    val nameOffset: Int,
    val nameLength: Int,
    val keyword: String,
    val isOpening: Boolean,
    val spannedLines: Int,
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

    /** Mark braces of functions, methods, constructors and lambdas. */
    val accentFunctions: Boolean = true,
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

/** Lexer state. */
private enum class LexerState {
    CODE,
    LINE_COMMENT,
    BLOCK_COMMENT,

    /** `"..."` with backslash escapes. */
    STRING,

    /** `'x'`, a character literal. */
    CHARACTER,

    /** C#: `@"..."`, where a quote is escaped by doubling it. */
    VERBATIM_STRING,

    /** `"""..."""`: C# raw strings and Java/Kotlin/Swift text blocks. */
    TRIPLE_QUOTED_STRING,

    /** C++: `R"delim(...)delim"`. */
    CPP_RAW_STRING,

    /** `` `...` `` with `${...}` holes. */
    BACKTICK_TEMPLATE,
}

/** The string context to return to once an interpolation hole closes. */
private class InterpolationFrame(
    val lexerState: LexerState,
    val interpolationDollars: Int,
    val rawQuoteCount: Int,
    val cppRawDelimiter: String,
    val holeBraceDepth: Int,
)

/** An open block on the nesting stack. */
private class OpenBlock(
    val kind: BlockKind,
    val nameOffset: Int,
    val nameLength: Int,
    val keyword: String,
    val openLineNumber: Int,
)

/** A parsed string literal prefix: `$`, `@`, `R`. */
private class LiteralPrefix(
    val dollars: Int,
    val isVerbatim: Boolean,
    val isCppRaw: Boolean,
)

/**
 * The header of a control construct at the start of a line: `if (x)`, `foreach (var a in b)`,
 * `else`, `} catch (E e)`.
 */
private class LineHeader(
    /** Offset where the header starts, already past a leading `}` if there is one. */
    val headerStart: Int,

    /** Offset just past the header. */
    val headerEnd: Int,

    /** The line starts with a `}` that must stay where it is. */
    val hasLeadingCloseBrace: Boolean,

    /** The header's first keyword: `if`, `else`, `catch`, `while`. */
    val firstKeyword: String,
)

/**
 * A linear scan of the document. No IntelliJ dependencies: a pure function of the text.
 *
 * Single-line states (a quoted string, a character literal, `//`) are force-reset at a newline.
 * That bounds any possible desync to exactly one line instead of the rest of the file.
 */
class BraceScanner(
    private val text: CharSequence,
    private val flavor: Flavor,
    private val options: ScanOptions = ScanOptions(),
) {
    // Dialect capabilities, kept as flags rather than enum comparisons all over the code:
    // there are many languages, and they differ in exactly one thing, their string literals.
    private val supportsVerbatimStrings = flavor == Flavor.CSHARP
    private val supportsTripleQuotedStrings = flavor == Flavor.CSHARP || flavor == Flavor.JVM
    private val supportsCppRawStrings = flavor == Flavor.CPP
    private val supportsDigitSeparatorQuote = flavor == Flavor.CPP
    private val supportsBacktickTemplates = flavor == Flavor.WEB

    private val foundSites = ArrayList<PhantomSite>()
    private val foundAccents = ArrayList<BraceAccent>()

    /** Stack of open `{`: a closing brace uses it to learn whose block it closes. */
    private val blockStack = ArrayDeque<OpenBlock>()

    private val textLength = text.length

    /** Cursor over the document. */
    private var position = 0

    // --- current line state ---

    private var lineStartOffset = 0
    private var lineNumber = 0

    /** The line starts in code, not inside a multi-line literal or comment. */
    private var lineStartsInCode = true

    private var firstCodeOffset = -1
    private var lastCodeOffset = -1

    /**
     * Offsets of parentheses and braces closed at the top level of this line.
     * They let the line be cut by offset instead of by parsing a substring: a substring can
     * contain a literal with brackets inside, while these offsets are already lexer-filtered.
     */
    private val lineParenCloseOffsets = ArrayList<Int>()
    private val lineBraceOpenOffsets = ArrayList<Int>()
    private val lineBraceCloseOffsets = ArrayList<Int>()

    // --- multi-line constructs ---

    /**
     * Depth of `(` and `[`. Needed so that for a multi-line signature
     * ```
     * void Foo(
     *     int a,
     *     int b) {
     * ```
     * the phantom brace lands under `void` rather than under `int b`.
     */
    private var bracketDepth = 0
    private var bracketDepthAtLineStart = 0
    private var statementLineStartOffset = 0
    private var statementLineNumber = 0

    /**
     * Bounds of the previous line that held code.
     *
     * Needed when the `{` sits on its own line, that is, when the code is already Allman. The
     * block header is then on the line above, and without this a class or method is not found.
     */
    private var previousCodeStart = -1
    private var previousCodeEnd = -1

    // --- lexer state ---

    private var lexerState = LexerState.CODE

    /** How many `$` precede the quote: `$"..."` is 1, `$$"""..."""` is 2, no interpolation 0. */
    private var interpolationDollars = 0

    /** Length of the opening quote run for [LexerState.TRIPLE_QUOTED_STRING]. */
    private var rawQuoteCount = 0

    /** The delimiter from `R"delim(`. */
    private var cppRawDelimiter = ""

    private val interpolationStack = ArrayDeque<InterpolationFrame>()

    /** Depth of `{}` inside the current interpolation hole. */
    private var holeBraceDepth = 0

    fun scan(): ScanResult {
        while (position < textLength) {
            val current = text[position]
            if (current == '\n') {
                finishLine(position)
                position++
                continue
            }
            when (lexerState) {
                LexerState.CODE -> stepCode(current)
                LexerState.LINE_COMMENT -> position++
                LexerState.BLOCK_COMMENT -> stepBlockComment(current)
                LexerState.STRING -> stepString(current)
                LexerState.CHARACTER -> stepCharacterLiteral(current)
                LexerState.VERBATIM_STRING -> stepVerbatimString(current)
                LexerState.TRIPLE_QUOTED_STRING -> stepTripleQuotedString(current)
                LexerState.CPP_RAW_STRING -> stepCppRawString(current)
                LexerState.BACKTICK_TEMPLATE -> stepBacktickTemplate(current)
            }
        }
        finishLine(textLength)
        return ScanResult(foundSites, foundAccents)
    }

    // ------------------------------------------------------------------- lexer states

    private fun stepCode(current: Char) {
        when (current) {
            '/' -> {
                openCommentOrSlash()
            }
            '"' -> {
                openDoubleQuotedLiteral()
            }
            '\'' -> {
                openSingleQuotedLiteral()
            }
            '`' -> {
                openBacktickLiteral()
            }
            '(', '[' -> {
                markCode(position)
                bracketDepth++
                position++
            }
            ')', ']' -> {
                markCode(position)
                if (bracketDepth > 0) {
                    bracketDepth--
                }
                if (bracketDepth == 0 && current == ')') {
                    remember(lineParenCloseOffsets, position)
                }
                position++
            }
            '{' -> {
                markCode(position)
                if (interpolationStack.isEmpty()) {
                    remember(lineBraceOpenOffsets, position)
                    openBlock(position)
                } else {
                    holeBraceDepth++
                }
                position++
            }
            '}' -> {
                closeBraceOrInterpolationHole()
            }
            ' ', '\t', '\r' -> {
                position++
            }
            else -> {
                markCode(position)
                position++
            }
        }
    }

    private fun openCommentOrSlash() {
        when (charRelative(1)) {
            '/' -> {
                lexerState = LexerState.LINE_COMMENT
                position += 2
            }
            '*' -> {
                lexerState = LexerState.BLOCK_COMMENT
                position += 2
            }
            else -> {
                markCode(position)
                position++
            }
        }
    }

    private fun closeBraceOrInterpolationHole() {
        if (interpolationStack.isNotEmpty() && holeBraceDepth == 0) {
            closeInterpolationHole()
            return
        }
        markCode(position)
        if (interpolationStack.isEmpty()) {
            remember(lineBraceCloseOffsets, position)
            closeBlock(position)
        } else {
            holeBraceDepth--
        }
        position++
    }

    private fun stepBlockComment(current: Char) {
        if (current == '*' && charRelative(1) == '/') {
            lexerState = LexerState.CODE
            position += 2
        } else {
            position++
        }
    }

    private fun stepString(current: Char) {
        when (current) {
            '\\' -> {
                advanceOverEscape()
            }
            '"' -> {
                position++
                closeStringLiteral()
            }
            '{' -> {
                stepInterpolationBraceOrSkip()
            }
            else -> {
                position++
            }
        }
    }

    private fun stepCharacterLiteral(current: Char) {
        when (current) {
            '\\' -> {
                advanceOverEscape()
            }
            '\'' -> {
                position++
                lexerState = LexerState.CODE
            }
            else -> {
                position++
            }
        }
    }

    private fun stepVerbatimString(current: Char) {
        when (current) {
            '"' -> {
                // in a verbatim string a quote is escaped by doubling, not by a backslash
                if (charRelative(1) == '"') {
                    position += 2
                } else {
                    position++
                    closeStringLiteral()
                }
            }
            '{' -> {
                stepInterpolationBraceOrSkip()
            }
            else -> {
                position++
            }
        }
    }

    private fun stepTripleQuotedString(current: Char) {
        when (current) {
            '"' -> {
                val quoteRun = countRepeated('"', position)
                position += quoteRun
                if (quoteRun >= rawQuoteCount) {
                    closeStringLiteral()
                }
            }
            '{' -> {
                stepInterpolationBraceOrSkip()
            }
            else -> {
                position++
            }
        }
    }

    private fun stepCppRawString(current: Char) {
        val isCloser = current == ')' &&
            matchesAt(position + 1, cppRawDelimiter) &&
            charAt(position + 1 + cppRawDelimiter.length) == '"'

        if (isCloser) {
            position += 1 + cppRawDelimiter.length + 1
            closeStringLiteral()
            return
        }
        position++
    }

    private fun stepBacktickTemplate(current: Char) {
        when (current) {
            '\\' -> {
                advanceOverEscape()
            }
            '`' -> {
                position++
                closeStringLiteral()
            }
            '$' -> {
                if (charRelative(1) == '{') {
                    openInterpolationHole()
                    position += 2
                } else {
                    position++
                }
            }
            else -> {
                position++
            }
        }
    }

    /** A `{` inside a string either opens an interpolation hole or is plain content. */
    private fun stepInterpolationBraceOrSkip() {
        if (interpolationDollars > 0) {
            handleInterpolationBrace()
        } else {
            position++
        }
    }

    // -------------------------------------------------------------- opening literals

    private fun openDoubleQuotedLiteral() {
        markCode(position)

        val prefix = readLiteralPrefix()
        if (prefix.isCppRaw) {
            openCppRawString()
            return
        }

        val quoteRun = countRepeated('"', position)
        if (quoteRun >= 3 && supportsTripleQuotedStrings) {
            rawQuoteCount = quoteRun
            interpolationDollars = prefix.dollars
            lexerState = LexerState.TRIPLE_QUOTED_STRING
            position += quoteRun
            return
        }
        if (quoteRun == 2) {
            // an empty literal, "" or @""
            position += 2
            return
        }

        interpolationDollars = prefix.dollars
        if (prefix.isVerbatim) {
            lexerState = LexerState.VERBATIM_STRING
        } else {
            lexerState = LexerState.STRING
        }
        position++
    }

    /** The prefix glued to the quote: `$`, `@`, `R`. Never reaches onto the previous line. */
    private fun readLiteralPrefix(): LiteralPrefix {
        var dollars = 0
        var isVerbatim = false
        var isCppRaw = false
        var prefixPosition = position - 1

        while (prefixPosition >= lineStartOffset) {
            val prefixChar = text[prefixPosition]
            if (prefixChar == '$') {
                dollars++
                prefixPosition--
                continue
            }
            if (prefixChar == '@' && supportsVerbatimStrings) {
                isVerbatim = true
                prefixPosition--
                continue
            }
            if (prefixChar == 'R' && supportsCppRawStrings) {
                isCppRaw = true
            }
            break
        }
        return LiteralPrefix(dollars, isVerbatim, isCppRaw)
    }

    private fun openCppRawString() {
        val delimiter = StringBuilder()
        var scanPosition = position + 1

        while (scanPosition < textLength &&
            text[scanPosition] != '(' &&
            text[scanPosition] != '\n' &&
            delimiter.length < MAX_CPP_RAW_DELIMITER
        ) {
            delimiter.append(text[scanPosition])
            scanPosition++
        }

        if (scanPosition < textLength && text[scanPosition] == '(') {
            cppRawDelimiter = delimiter.toString()
            lexerState = LexerState.CPP_RAW_STRING
            position = scanPosition + 1
            return
        }

        // does not look like a raw string, so treat it as an ordinary one
        lexerState = LexerState.STRING
        interpolationDollars = 0
        position++
    }

    private fun openSingleQuotedLiteral() {
        markCode(position)
        if (isDigitSeparator()) {
            position++
            return
        }
        lexerState = LexerState.CHARACTER
        position++
    }

    /** C++: in `1'000'000` the apostrophe is a digit separator, not a character literal. */
    private fun isDigitSeparator(): Boolean {
        if (!supportsDigitSeparatorQuote) {
            return false
        }
        if (position <= lineStartOffset || position + 1 >= textLength) {
            return false
        }
        return text[position - 1].isLetterOrDigit() && text[position + 1].isLetterOrDigit()
    }

    private fun openBacktickLiteral() {
        markCode(position)
        if (supportsBacktickTemplates) {
            lexerState = LexerState.BACKTICK_TEMPLATE
            interpolationDollars = 1
        }
        position++
    }

    // ----------------------------------------------------------------- interpolation

    private fun handleInterpolationBrace() {
        val braceRun = countRepeated('{', position)

        // with a single $ the sequence {{ is an escaped brace, not a hole
        if (interpolationDollars in 1..(braceRun / 2)) {
            position += 2 * interpolationDollars
            return
        }
        if (braceRun >= interpolationDollars) {
            openInterpolationHole()
            position += braceRun
            return
        }
        position += braceRun
    }

    private fun openInterpolationHole() {
        interpolationStack.addLast(
            InterpolationFrame(
                lexerState = lexerState,
                interpolationDollars = interpolationDollars,
                rawQuoteCount = rawQuoteCount,
                cppRawDelimiter = cppRawDelimiter,
                holeBraceDepth = holeBraceDepth,
            ),
        )
        holeBraceDepth = 0
        lexerState = LexerState.CODE
        interpolationDollars = 0
        rawQuoteCount = 0
        cppRawDelimiter = ""
    }

    private fun closeInterpolationHole() {
        val frame = interpolationStack.removeLast()
        lexerState = frame.lexerState
        interpolationDollars = frame.interpolationDollars
        rawQuoteCount = frame.rawQuoteCount
        cppRawDelimiter = frame.cppRawDelimiter
        holeBraceDepth = frame.holeBraceDepth
        position++
    }

    /**
     * Closes the current literal. Inside an interpolation hole the stack is left alone:
     * returning to the enclosing string is the closing `}`'s job, not this quote's.
     */
    private fun closeStringLiteral() {
        lexerState = LexerState.CODE
        interpolationDollars = 0
        rawQuoteCount = 0
        cppRawDelimiter = ""
    }

    // ------------------------------------------------------------- per-line analysis

    private fun finishLine(lineEndOffset: Int) {
        emitLine(lineEndOffset)

        if (lineStartsInCode && firstCodeOffset >= 0 && lastCodeOffset >= 0) {
            previousCodeStart = statementLineStartOffset
            previousCodeEnd = lastCodeOffset + 1
        }

        val isUnterminatedSingleLine = lexerState == LexerState.LINE_COMMENT ||
            lexerState == LexerState.STRING ||
            lexerState == LexerState.CHARACTER

        if (isUnterminatedSingleLine) {
            // do not carry a desync any further
            lexerState = LexerState.CODE
            interpolationDollars = 0
        }

        lineStartOffset = lineEndOffset + 1
        lineNumber++
        lineStartsInCode = lexerState == LexerState.CODE
        firstCodeOffset = -1
        lastCodeOffset = -1
        lineParenCloseOffsets.clear()
        lineBraceOpenOffsets.clear()
        lineBraceCloseOffsets.clear()

        bracketDepthAtLineStart = bracketDepth
        if (bracketDepth == 0) {
            statementLineStartOffset = lineStartOffset
            statementLineNumber = lineNumber
        }
    }

    private fun emitLine(lineEndOffset: Int) {
        if (!lineStartsInCode) {
            return
        }
        if (firstCodeOffset < 0 || lastCodeOffset < 0) {
            return
        }

        val indent = readIndent()

        if (text[lastCodeOffset] == '{') {
            emitHangingBrace(lastCodeOffset, lineEndOffset, indent)
            return
        }
        if (text[lastCodeOffset] == '}' && options.expandInlineBlocks) {
            emitInlineBlock(lineEndOffset, indent)
            return
        }
        emitStatementSplit(lineEndOffset, indent)
    }

    private fun emitHangingBrace(braceOffset: Int, lineEndOffset: Int, indent: String) {
        if (braceOffset <= firstCodeOffset) {
            // the `{` is the first code character, so the line is already Allman
            return
        }

        val header = parseLineHeader()
        if (header != null && movesLeadingCloseBrace(header)) {
            val headerText = text.subSequence(header.headerStart, braceOffset).toString().trimEnd()
            if (headerText.isNotEmpty()) {
                // dim everything that moved down: " else {"
                addSite(
                    dimStart = firstCodeOffset + 1,
                    dimEnd = braceOffset + 1,
                    anchorOffset = lineEndOffset,
                    indent = indent,
                    phantomLines = listOf(
                        PhantomLine(headerText, header.headerStart),
                        PhantomLine("{", braceOffset),
                    ),
                )
                return
            }
        }

        val head = text.subSequence(firstCodeOffset, braceOffset).toString().trimEnd()
        if (head.isEmpty()) {
            return
        }

        // dim only the brace itself; the spaces before it are invisible anyway
        addSite(
            dimStart = braceOffset,
            dimEnd = braceOffset + 1,
            anchorOffset = lineEndOffset,
            indent = indent,
            phantomLines = listOf(PhantomLine("{", braceOffset)),
        )
    }

    /**
     * `if (x) { Foo(); }`: the header stays and the block expands into three lines.
     *
     * The header must be a control construct, otherwise the auto-property
     * `public int X { get; set; }` would match the rule, and it must not be split.
     */
    private fun emitInlineBlock(lineEndOffset: Int, indent: String) {
        val header = parseLineHeader()
        if (header == null) {
            return
        }

        val openCount = countOffsetsAtOrAfter(lineBraceOpenOffsets, header.headerEnd)
        val closeCount = countOffsetsAtOrAfter(lineBraceCloseOffsets, header.headerEnd)
        if (openCount == 0 || openCount != closeCount) {
            return
        }
        if (lineBraceCloseOffsets.last() != lastCodeOffset) {
            return
        }

        val openOffset = firstOffsetAtOrAfter(lineBraceOpenOffsets, header.headerEnd)
        if (openOffset < 0) {
            return
        }

        val innerStart = skipSpacesFrom(openOffset + 1)
        val innerText = text.subSequence(innerStart, lastCodeOffset).toString().trimEnd()

        val blockLines = ArrayList<PhantomLine>()
        blockLines.add(PhantomLine("{", openOffset))
        if (innerText.isNotEmpty()) {
            blockLines.add(PhantomLine(innerText, innerStart, extraIndentLevels = 1))
        }
        blockLines.add(PhantomLine("}", lastCodeOffset))

        if (movesLeadingCloseBrace(header)) {
            val headerText = text.subSequence(header.headerStart, openOffset).toString().trimEnd()
            if (headerText.isEmpty()) {
                return
            }
            val phantomLines = ArrayList<PhantomLine>()
            phantomLines.add(PhantomLine(headerText, header.headerStart))
            phantomLines.addAll(blockLines)

            addSite(
                dimStart = firstCodeOffset + 1,
                dimEnd = lastCodeOffset + 1,
                anchorOffset = lineEndOffset,
                indent = indent,
                phantomLines = phantomLines,
            )
            return
        }

        addSite(
            dimStart = openOffset,
            dimEnd = lastCodeOffset + 1,
            anchorOffset = lineEndOffset,
            indent = indent,
            phantomLines = blockLines,
        )
    }

    /**
     * `if (x) return;`: the header stays and the statement moves down one indent level.
     * A bare `} else` lands here too and is split according to the full Allman rules.
     */
    private fun emitStatementSplit(lineEndOffset: Int, indent: String) {
        val header = parseLineHeader()
        if (header == null) {
            return
        }

        val statementStart = skipSpacesFrom(header.headerEnd)
        val statementText = text.subSequence(statementStart, lastCodeOffset + 1).toString().trimEnd()

        // an empty statement `while (x);` and a bare `{` are not our case
        val hasStatement = options.splitStatements &&
            statementText.isNotEmpty() &&
            statementText != ";" &&
            !statementText.startsWith("{")

        if (movesLeadingCloseBrace(header)) {
            val headerEnd: Int
            if (hasStatement) {
                headerEnd = statementStart
            } else {
                headerEnd = lastCodeOffset + 1
            }
            val headerText = text.subSequence(header.headerStart, headerEnd).toString().trimEnd()
            if (headerText.isEmpty()) {
                return
            }

            val phantomLines = ArrayList<PhantomLine>()
            phantomLines.add(PhantomLine(headerText, header.headerStart))
            if (hasStatement) {
                phantomLines.add(PhantomLine(statementText, statementStart, extraIndentLevels = 1))
            }

            addSite(
                dimStart = firstCodeOffset + 1,
                dimEnd = lastCodeOffset + 1,
                anchorOffset = lineEndOffset,
                indent = indent,
                phantomLines = phantomLines,
            )
            return
        }

        if (!hasStatement) {
            return
        }
        addSite(
            dimStart = statementStart,
            dimEnd = lastCodeOffset + 1,
            anchorOffset = lineEndOffset,
            indent = indent,
            phantomLines = listOf(
                PhantomLine(statementText, statementStart, extraIndentLevels = 1),
            ),
        )
    }

    /**
     * Parses the header of a control construct at the start of a line.
     *
     * The cut uses offsets recorded by the lexer rather than a substring: in
     * `if (Check(")")) Foo();` a naive search would find the closing paren inside the literal.
     */
    private fun parseLineHeader(): LineHeader? {
        var cursor = firstCodeOffset
        var hasLeadingCloseBrace = false

        if (text[cursor] == '}') {
            hasLeadingCloseBrace = true
            cursor = skipSpacesFrom(cursor + 1)
            if (cursor > lastCodeOffset) {
                return null
            }
        }

        val headerStart = cursor
        var firstKeyword = ""

        while (true) {
            val keyword = matchKeywordAt(cursor)
            if (keyword == null) {
                break
            }
            if (firstKeyword.isEmpty()) {
                firstKeyword = keyword
            }
            cursor = skipSpacesFrom(cursor + keyword.length)

            if (cursor <= lastCodeOffset && text[cursor] == '(') {
                val closeOffset = firstOffsetAtOrAfter(lineParenCloseOffsets, cursor)
                if (closeOffset < 0) {
                    return null
                }
                cursor = skipSpacesFrom(closeOffset + 1)
                break
            }
            if (keyword in KEYWORDS_REQUIRING_PARENS) {
                // `using System.Text;` is a directive, not a construct with parentheses
                return null
            }
            if (keyword != "else") {
                break
            }
        }

        if (firstKeyword.isEmpty()) {
            return null
        }
        return LineHeader(headerStart, cursor, hasLeadingCloseBrace, firstKeyword)
    }

    private fun matchKeywordAt(offset: Int): String? {
        for (keyword in HEADER_KEYWORDS) {
            if (!matchesAt(offset, keyword)) {
                continue
            }
            val following = charAt(offset + keyword.length)
            if (following.isLetterOrDigit() || following == '_') {
                continue
            }
            return keyword
        }
        return null
    }

    /**
     * Indent for the phantom line. When the line continues an unclosed `(` or `[`, the indent
     * is taken from the line the construct started on. The length limit guards against a desync
     * caused by unbalanced brackets.
     */
    private fun readIndent(): String {
        val isContinuation = bracketDepthAtLineStart > 0 &&
            lineNumber - statementLineNumber in 1..MAX_CONTINUATION_LINES

        val indentStart: Int
        if (isContinuation) {
            indentStart = statementLineStartOffset
        } else {
            indentStart = lineStartOffset
        }

        var indentEnd = indentStart
        while (indentEnd < textLength && (text[indentEnd] == ' ' || text[indentEnd] == '\t')) {
            indentEnd++
        }
        return text.subSequence(indentStart, indentEnd).toString()
    }

    private fun addSite(
        dimStart: Int,
        dimEnd: Int,
        anchorOffset: Int,
        indent: String,
        phantomLines: List<PhantomLine>,
    ) {
        if (dimStart >= dimEnd) {
            return
        }
        if (phantomLines.isEmpty()) {
            return
        }
        foundSites.add(PhantomSite(dimStart, dimEnd, anchorOffset, indent, phantomLines))
    }

    // ------------------------------------------------------- curly brace ownership

    private fun openBlock(braceOffset: Int) {
        val block = classifyBlock(braceOffset)
        blockStack.addLast(block)
        rememberAccent(braceOffset, block, isOpening = true, spannedLines = 0)
    }

    private fun closeBlock(braceOffset: Int) {
        if (blockStack.isEmpty()) {
            // the file is mid-edit, so braces are unbalanced
            return
        }
        val block = blockStack.removeLast()
        val spannedLines = lineNumber - block.openLineNumber
        rememberAccent(braceOffset, block, isOpening = false, spannedLines = spannedLines)
    }

    private fun rememberAccent(
        braceOffset: Int,
        block: OpenBlock,
        isOpening: Boolean,
        spannedLines: Int,
    ) {
        if (!isAccented(block.kind)) {
            return
        }
        foundAccents.add(
            BraceAccent(
                offset = braceOffset,
                kind = block.kind,
                nameOffset = block.nameOffset,
                nameLength = block.nameLength,
                keyword = block.keyword,
                isOpening = isOpening,
                spannedLines = spannedLines,
            ),
        )
    }

    private fun isAccented(kind: BlockKind): Boolean {
        if (kind == BlockKind.TYPE) {
            return options.accentTypes
        }
        if (kind == BlockKind.FUNCTION) {
            return options.accentFunctions
        }
        return false
    }

    private fun classifyBlock(braceOffset: Int): OpenBlock {
        if (!options.accentTypes && !options.accentFunctions) {
            return otherBlock()
        }

        var headerStart = headerStartFor(braceOffset)
        var headerEnd = braceOffset

        if (firstCodeOffset == braceOffset) {
            // the brace is the first code on the line, so the code is Allman and the header is above
            if (previousCodeEnd <= 0 || previousCodeEnd > braceOffset) {
                return otherBlock()
            }
            headerStart = previousCodeStart
            headerEnd = previousCodeEnd
        }

        if (headerStart >= headerEnd) {
            return otherBlock()
        }

        val header = declarationPart(text.subSequence(headerStart, headerEnd).toString())
        val typeBlock = classifyTypeHeader(header, headerStart)
        if (typeBlock != null) {
            return typeBlock
        }
        return classifyFunctionHeader(header, headerStart)
    }

    private fun otherBlock(): OpenBlock {
        return OpenBlock(BlockKind.OTHER, -1, 0, "", lineNumber)
    }

    private fun namedBlock(
        kind: BlockKind,
        keyword: String,
        header: String,
        headerStart: Int,
        nameIndex: Int,
    ): OpenBlock {
        if (nameIndex < 0) {
            return OpenBlock(kind, -1, 0, keyword, lineNumber)
        }
        val name = identifierAt(header, nameIndex)
        return OpenBlock(kind, headerStart + nameIndex, name.length, keyword, lineNumber)
    }

    /**
     * The header without literals and without constraints.
     *
     * Constraints are cut before classification, otherwise `void Bind<T>(T v) where T : class {`
     * would see the word `class` and pass as a type declaration.
     */
    private fun declarationPart(header: String): String {
        val withoutLiterals = header.substring(0, quoteLimit(header))
        val whereIndex = findKeyword(withoutLiterals, WHERE_KEYWORDS)
        if (whereIndex < 0) {
            return withoutLiterals
        }
        return withoutLiterals.substring(0, whereIndex)
    }

    /**
     * Where this brace's header begins.
     *
     * Usually the start of the line the construct began on, so a multi-line signature is read
     * whole. But when the line already had braces, the header starts after the last of them:
     * otherwise in `class A { void M() {` the second brace would see `class` and pass as a type.
     */
    private fun headerStartFor(braceOffset: Int): Int {
        var start = statementLineStartOffset
        for (offset in lineBraceOpenOffsets) {
            if (offset < braceOffset && offset + 1 > start) {
                start = offset + 1
            }
        }
        for (offset in lineBraceCloseOffsets) {
            if (offset < braceOffset && offset + 1 > start) {
                start = offset + 1
            }
        }
        return start
    }

    private fun classifyTypeHeader(header: String, headerStart: Int): OpenBlock? {
        val keywordIndex = findKeyword(header, TYPE_KEYWORDS)
        if (keywordIndex < 0) {
            return null
        }
        val keyword = keywordAt(header, keywordIndex, TYPE_KEYWORDS) ?: return null

        // `record struct Point`: several keywords can follow one another
        var cursor = keywordIndex
        while (true) {
            val next = keywordAt(header, cursor, TYPE_KEYWORDS)
            if (next == null) {
                break
            }
            cursor = skipSpacesIn(header, cursor + next.length)
        }

        val nameIndex = identifierStartAt(header, cursor)
        if (nameIndex >= 0) {
            return namedBlock(BlockKind.TYPE, keyword, header, headerStart, nameIndex)
        }

        // Go: in `type Point struct {` the name comes before the keyword
        val beforeIndex = identifierStartBefore(header, keywordIndex)
        return namedBlock(BlockKind.TYPE, keyword, header, headerStart, beforeIndex)
    }

    private fun classifyFunctionHeader(header: String, headerStart: Int): OpenBlock {
        val trimmed = header.trimEnd()

        // check the lambda first: `return items.Select(x => {` is a lambda body,
        // even though the line starts with the word return
        if (trimmed.endsWith("=>") || endsWithWord(trimmed, "delegate")) {
            return lambdaBlock(header, headerStart)
        }
        if (!trimmed.endsWith(")")) {
            return otherBlock()
        }

        val firstWordIndex = identifierStartAt(header, 0)
        if (firstWordIndex < 0) {
            return otherBlock()
        }
        val firstWord = identifierAt(header, firstWordIndex)
        if (firstWord == "delegate") {
            // an anonymous method with a parameter list: `delegate(int x) {`
            return lambdaBlock(header, headerStart)
        }
        if (firstWord in NON_DECLARATION_KEYWORDS) {
            return otherBlock()
        }
        if (containsAssignment(header)) {
            // `var a = new Foo() {` is an initializer, not a declaration
            return otherBlock()
        }

        val parenIndex = header.indexOf('(')
        if (parenIndex < 0) {
            return otherBlock()
        }

        val nameEnd = skipGenericsBefore(header, parenIndex)
        val nameIndex = identifierStartBefore(header, nameEnd)
        if (nameIndex < 0) {
            return otherBlock()
        }
        return namedBlock(BlockKind.FUNCTION, FUNCTION_KEYWORD, header, headerStart, nameIndex)
    }

    /**
     * A lambda or an anonymous method.
     *
     * They have no name of their own, so the nearest meaningful one is used: the assignment
     * target (`Action handler = () => {`) or the method the lambda is passed to
     * (`Run(() => {`). The colour comes from the same place.
     */
    private fun lambdaBlock(header: String, headerStart: Int): OpenBlock {
        // The call the lambda is passed to comes first: in `var r = items.Select(y => {`
        // the meaningful name is Select, not the variable on the left.
        val openIndex = lastUnclosedParen(header)
        if (openIndex >= 0) {
            val nameEnd = skipGenericsBefore(header, openIndex)
            val nameIndex = identifierStartBefore(header, nameEnd)
            if (nameIndex >= 0) {
                return namedBlock(BlockKind.FUNCTION, FUNCTION_KEYWORD, header, headerStart, nameIndex)
            }
        }

        // No parentheses means the lambda is simply assigned: `Action handler = () => {`
        val assignIndex = assignmentIndex(header)
        if (assignIndex >= 0) {
            val nameIndex = identifierStartBefore(header, assignIndex)
            if (nameIndex >= 0) {
                return namedBlock(BlockKind.FUNCTION, FUNCTION_KEYWORD, header, headerStart, nameIndex)
            }
        }
        return namedBlock(BlockKind.FUNCTION, FUNCTION_KEYWORD, header, headerStart, -1)
    }

    private fun lastUnclosedParen(header: String): Int {
        val opened = ArrayList<Int>()
        for (index in header.indices) {
            if (header[index] == '(') {
                opened.add(index)
            }
            if (header[index] == ')' && opened.isNotEmpty()) {
                opened.removeAt(opened.size - 1)
            }
        }
        if (opened.isEmpty()) {
            return -1
        }
        return opened[opened.size - 1]
    }

    /** Nothing after the first quote in a header can be parsed; that is a literal. */
    private fun quoteLimit(header: String): Int {
        val quoteIndex = header.indexOf('"')
        if (quoteIndex < 0) {
            return header.length
        }
        return quoteIndex
    }

    private fun findKeyword(header: String, keywords: Set<String>): Int {
        val limit = quoteLimit(header)
        for (index in 0 until limit) {
            if (index > 0 && isIdentifierChar(header[index - 1])) {
                continue
            }
            if (keywordAt(header, index, keywords) != null) {
                return index
            }
        }
        return -1
    }

    private fun keywordAt(header: String, index: Int, keywords: Set<String>): String? {
        for (keyword in keywords) {
            if (!header.startsWith(keyword, index)) {
                continue
            }
            val following = header.getOrNull(index + keyword.length)
            if (following != null && isIdentifierChar(following)) {
                continue
            }
            return keyword
        }
        return null
    }

    private fun endsWithWord(header: String, word: String): Boolean {
        if (!header.endsWith(word)) {
            return false
        }
        val before = header.getOrNull(header.length - word.length - 1)
        return before == null || !isIdentifierChar(before)
    }

    /** In `Foo<T>(` the name precedes the generic parameters, so `<...>` is rewound. */
    private fun skipGenericsBefore(header: String, parenIndex: Int): Int {
        var cursor = parenIndex - 1
        while (cursor >= 0 && header[cursor].isWhitespace()) {
            cursor--
        }
        if (cursor < 0 || header[cursor] != '>') {
            return cursor + 1
        }

        var depth = 0
        while (cursor >= 0) {
            if (header[cursor] == '>') {
                depth++
            }
            if (header[cursor] == '<') {
                depth--
                if (depth == 0) {
                    return cursor
                }
            }
            cursor--
        }
        return parenIndex
    }

    /** Index of an assignment `=`, but not of `==`, `=>`, `<=`, `>=`, `!=`. */
    private fun assignmentIndex(header: String): Int {
        for (index in header.indices) {
            if (header[index] != '=') {
                continue
            }
            val previous = header.getOrNull(index - 1)
            val next = header.getOrNull(index + 1)
            val isComparison = previous == '=' || previous == '!' || previous == '<' ||
                previous == '>' || next == '=' || next == '>'
            if (!isComparison) {
                return index
            }
        }
        return -1
    }

    private fun containsAssignment(header: String): Boolean {
        return assignmentIndex(header) >= 0
    }

    private fun skipSpacesIn(header: String, from: Int): Int {
        var cursor = from
        while (cursor < header.length && header[cursor].isWhitespace()) {
            cursor++
        }
        return cursor
    }

    private fun identifierStartAt(header: String, from: Int): Int {
        val cursor = skipSpacesIn(header, from)
        if (cursor >= header.length) {
            return -1
        }
        if (!isIdentifierStart(header[cursor])) {
            return -1
        }
        return cursor
    }

    private fun identifierStartBefore(header: String, endExclusive: Int): Int {
        var cursor = endExclusive - 1
        while (cursor >= 0 && header[cursor].isWhitespace()) {
            cursor--
        }
        if (cursor < 0 || !isIdentifierChar(header[cursor])) {
            return -1
        }
        while (cursor > 0 && isIdentifierChar(header[cursor - 1])) {
            cursor--
        }
        if (!isIdentifierStart(header[cursor])) {
            return -1
        }
        return cursor
    }

    private fun identifierAt(header: String, start: Int): String {
        var end = start
        while (end < header.length && isIdentifierChar(header[end])) {
            end++
        }
        return header.substring(start, end)
    }

    private fun isIdentifierChar(character: Char): Boolean {
        return character.isLetterOrDigit() || character == '_'
    }

    private fun isIdentifierStart(character: Char): Boolean {
        return character.isLetter() || character == '_'
    }

    // ------------------------------------------------------------------ small helpers

    private fun markCode(offset: Int) {
        if (firstCodeOffset < 0) {
            firstCodeOffset = offset
        }
        lastCodeOffset = offset
    }

    private fun skipSpacesFrom(offset: Int): Int {
        var cursor = offset
        while (cursor <= lastCodeOffset && (text[cursor] == ' ' || text[cursor] == '\t')) {
            cursor++
        }
        return cursor
    }

    private fun remember(offsets: MutableList<Int>, offset: Int) {
        if (offsets.size < MAX_TRACKED_OFFSETS) {
            offsets.add(offset)
        }
    }

    /** A leading `}` is not part of the block balance; only count what follows the header. */
    private fun countOffsetsAtOrAfter(offsets: List<Int>, from: Int): Int {
        var count = 0
        for (offset in offsets) {
            if (offset >= from) {
                count++
            }
        }
        return count
    }

    private fun movesLeadingCloseBrace(header: LineHeader): Boolean {
        if (!header.hasLeadingCloseBrace || !options.fullAllman) {
            return false
        }
        return header.firstKeyword in SPLIT_KEYWORDS
    }

    private fun firstOffsetAtOrAfter(offsets: List<Int>, from: Int): Int {
        for (offset in offsets) {
            if (offset >= from) {
                return offset
            }
        }
        return -1
    }

    /** Never jump over a newline, or the line bookkeeping is lost. */
    private fun advanceOverEscape() {
        if (position + 1 < textLength && text[position + 1] != '\n') {
            position += 2
        } else {
            position++
        }
    }

    private fun countRepeated(character: Char, from: Int): Int {
        var count = 0
        while (from + count < textLength && text[from + count] == character) {
            count++
        }
        return count
    }

    private fun charRelative(delta: Int): Char {
        return charAt(position + delta)
    }

    private fun charAt(offset: Int): Char {
        if (offset < 0 || offset >= textLength) {
            return NO_CHARACTER
        }
        return text[offset]
    }

    private fun matchesAt(offset: Int, candidate: String): Boolean {
        if (offset + candidate.length > textLength) {
            return false
        }
        for (index in candidate.indices) {
            if (text[offset + index] != candidate[index]) {
                return false
            }
        }
        return true
    }

    companion object {
        private val SPLIT_KEYWORDS = setOf("else", "catch", "finally")

        /** What the label says for functions: no return type, just `fun Name`. */
        private const val FUNCTION_KEYWORD = "fun"

        private val TYPE_KEYWORDS = setOf("class", "struct", "interface", "enum", "record")

        private val WHERE_KEYWORDS = setOf("where")

        /**
         * These words start anything but a function declaration.
         * Without them `return new Foo() {` and `switch (x) {` would count as functions.
         */
        private val NON_DECLARATION_KEYWORDS = setOf(
            "if", "for", "foreach", "while", "switch", "using", "lock", "fixed",
            "catch", "do", "else", "try", "finally", "return", "throw", "yield",
            "await", "new", "unsafe", "checked", "unchecked",
        )

        /** Keywords a splittable construct can start with. */
        private val HEADER_KEYWORDS = listOf(
            "foreach", "finally", "while", "catch", "fixed", "using", "lock", "else", "for", "if",
        )

        /**
         * Without parentheses these words mean something else:
         * `using System;` is a directive, and `for` never appears without them.
         */
        private val KEYWORDS_REQUIRING_PARENS = setOf(
            "if", "for", "foreach", "while", "using", "lock", "fixed",
        )

        /** How far back the start of a multi-line construct may be searched for. */
        private const val MAX_CONTINUATION_LINES = 40

        /** By the standard the delimiter in `R"delim(` is at most 16 characters. */
        private const val MAX_CPP_RAW_DELIMITER = 16

        /** How many brackets per line are remembered; beyond that the line is not ours. */
        private const val MAX_TRACKED_OFFSETS = 16

        /** Returned instead of a character outside the document. */
        private val NO_CHARACTER = Char.MIN_VALUE
    }
}
