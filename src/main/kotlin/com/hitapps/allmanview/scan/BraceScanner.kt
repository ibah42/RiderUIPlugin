package com.hitapps.allmanview.scan

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

    /**
     * The [OpenBlock] behind each entry in [foundAccents], index-aligned with it. A
     * [BraceAccent] is built before its block's final [OpenBlock.siblingOrdinal] is known, so
     * this is what lets [finalizeSiblingOrdinals] patch that value in afterwards without
     * re-scanning anything.
     */
    private val accentSourceBlocks = ArrayList<OpenBlock>()

    /**
     * Every container's direct type/namespace children, in the order they open, keyed by the
     * container's own [OpenBlock] -- `null` for the file's top level. Read once, by
     * [finalizeSiblingOrdinals], after the whole file has been scanned: a container's final
     * child count is not known any earlier than that.
     */
    private val siblingsByContainer = HashMap<OpenBlock?, MutableList<OpenBlock>>()

    /** Stack of open `{`: a closing brace uses it to learn whose block it closes. */
    private val blockStack = ArrayDeque<OpenBlock>()

    /** Stateless: it is handed the header slice and answers what kind of block it declares. */
    private val classifier = BlockClassifier(options)

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
     * [statementLineStartOffset] and [statementLineNumber] as they were just before the last
     * time they were reset. A `where` clause or a constructor's `: base(...)`/`: this(...)`
     * sits at `bracketDepth == 0`, exactly like a brand new statement, so by the time
     * [finishLine] notices such a line is really a continuation, the reset for it has already
     * happened -- these two are what let it be undone. See the restore at the top of
     * [finishLine] and its use in [readIndent].
     */
    private var statementStartBeforeContinuation = 0
    private var statementNumberBeforeContinuation = 0

    /**
     * `bracketDepth` saved across a `{ }` block, so a paren left open by an OUTER statement
     * (a lambda passed to a still-unclosed call, `Register(\n    x,\n    () =>\n    {`) does not
     * leak into the block's body. Without this, every line inside such a lambda looks like a
     * continuation of that outer call — `bracketDepth` never returns to 0, so
     * [statementLineStartOffset] freezes at the call's first line for the whole body, and every
     * Allman brace in it reads the call's own header (comments and all) instead of its own.
     */
    private val bracketDepthStack = ArrayDeque<Int>()

    /**
     * Bounds of the previous line that held code.
     *
     * Needed when the `{` sits on its own line, that is, when the code is already Allman. The
     * block header is then on the line above, and without this a class or method is not found.
     */
    private var previousCodeStart = -1
    private var previousCodeEnd = -1

    /**
     * The line [previousCodeStart] is on, kept in step with it. Without this the Allman case --
     * the `{` on its own line, the declaration above it -- has the header's offset but not its
     * line, and a block's length would have to be measured from the brace instead of from the
     * declaration.
     */
    private var previousCodeLineNumber = -1

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
        finalizeSiblingOrdinals()
        return ScanResult(foundSites, foundAccents, blockCounts(foundAccents))
    }

    /** One pass over the finished accents rather than a counter threaded through the scan. */
    private fun blockCounts(accents: List<BraceAccent>): BlockCounts {
        var types = 0
        var namespaces = 0

        for (accent in accents) {
            if (!accent.isOpening) {
                continue
            }
            if (accent.kind == BlockKind.TYPE) {
                types++
            }
            if (accent.kind == BlockKind.NAMESPACE) {
                namespaces++
            }
        }
        return BlockCounts(types, namespaces)
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
        if (lineStartsInCode && firstCodeOffset >= 0 &&
            previousCodeEnd >= 0 && (isWhereConstraintLine() || isColonContinuationLine())
        ) {
            // This line continues the declaration above it -- a `where` clause, or a
            // constructor's `: base(...)`/`: this(...)` -- even though bracketDepth was already
            // back to 0 when it started, which is what made the bottom of this function reset
            // the statement's start to this line. Undo that now, before emitLine (right below)
            // calls readIndent: a brace hanging on this very line must still take its indent
            // from the declaration itself, not from this deeper-indented continuation clause.
            statementLineStartOffset = statementStartBeforeContinuation
            statementLineNumber = statementNumberBeforeContinuation
        }

        emitLine(lineEndOffset)

        if (lineStartsInCode && firstCodeOffset >= 0 && lastCodeOffset >= 0) {
            if (previousCodeEnd >= 0 &&
                (isWhereConstraintLine() || isColonContinuationLine() || followsUnfinishedDeclaration())
            ) {
                // `where T : IFoo` on its own line, after a multi-line parameter list already
                // closed its parentheses, and `: base(...)`/`: this(...)` or a wrapped base-type
                // list on its own line, both continue the declaration above them. bracketDepth is
                // back to 0 by here, so statementLineStartOffset was already reset to this very
                // line -- see the bottom of this function -- and taking it now would truncate the
                // header down to just this one clause, losing the declaration itself (name,
                // modifiers, return type, all of it). The declaration above still owns the
                // header, so only the end is extended.
                //
                // The third case is the same clause running past one line: only its FIRST line
                // starts with the `:`, so the second and later ones are recognised by what the
                // line above them ended with instead.
                previousCodeEnd = lastCodeOffset + 1
            } else {
                previousCodeStart = statementLineStartOffset
                previousCodeEnd = lastCodeOffset + 1
                previousCodeLineNumber = statementLineNumber
            }
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
            statementStartBeforeContinuation = statementLineStartOffset
            statementNumberBeforeContinuation = statementLineNumber
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
     * Indent for the phantom line. When the line continues an unclosed `(` or `[`, or is itself
     * a `where` clause or a constructor's `: base(...)`/`: this(...)`, the indent is taken from
     * the line the construct started on -- not from this line's own, deeper indent. The length
     * limit guards against a desync caused by unbalanced brackets.
     */
    private fun readIndent(): String {
        val isContinuation = (
            bracketDepthAtLineStart > 0 || isWhereConstraintLine() || isColonContinuationLine()
            ) &&
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
        // Classify first, while bracketDepth still belongs to the line this brace sits on --
        // that is the outer statement's own continuation and must stay visible to it. Only
        // once that is done does the block's body get its own fresh bracket scope.
        val block = classifyBlock(braceOffset)
        block.isNested = enclosesKind(block.kind)
        block.parent = blockStack.lastOrNull()
        registerSibling(block)
        blockStack.addLast(block)
        rememberAccent(braceOffset, block, isOpening = true, spannedLines = 0)

        bracketDepthStack.addLast(bracketDepth)
        bracketDepth = 0
    }

    private fun closeBlock(braceOffset: Int) {
        if (blockStack.isEmpty()) {
            // the file is mid-edit, so braces are unbalanced
            return
        }
        val block = blockStack.removeLast()
        // From the declaration, not from the brace: in a source already written in Allman
        // style the two are a line apart, and a multi-line signature puts several between
        // them. Every threshold in the plugin reads this, so the marker that prints it cannot
        // drift from the rule that decided to print it.
        val spannedLines = lineNumber - block.headerLineNumber
        rememberAccent(braceOffset, block, isOpening = false, spannedLines = spannedLines)

        // Back to the outer statement's own bracket nesting, exactly as it was left.
        bracketDepth = bracketDepthStack.removeLast()
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
                isNested = block.isNested,
                isLambda = block.isLambda,
                isAccessor = block.isAccessor,
                headerOffset = block.headerOffset,
            ),
        )
        accentSourceBlocks.add(block)
    }

    /**
     * Whether a block of the same kind is already open around this one.
     *
     * Kind-specific on purpose: a method inside a class is not a nested function, but a local
     * function inside that method is. Depth does not matter — one enclosing block is enough.
     */
    private fun enclosesKind(kind: BlockKind): Boolean {
        if (kind == BlockKind.OTHER) {
            return false
        }
        for (block in blockStack) {
            if (block.kind == kind) {
                return true
            }
        }
        return false
    }

    /**
     * Adds a type or namespace block to its container's sibling list, so
     * [finalizeSiblingOrdinals] can number it once the container's final child count is known.
     * A function, a lambda or anything else in [BlockKind] is never a sibling for this purpose,
     * so it is never added and never counts towards the two-or-more threshold.
     */
    private fun registerSibling(block: OpenBlock) {
        if (block.kind != BlockKind.TYPE && block.kind != BlockKind.NAMESPACE) {
            return
        }
        siblingsByContainer.getOrPut(block.parent) { ArrayList() }.add(block)
    }

    /**
     * Numbers every container's type/namespace children `[1]`, `[2]`, ... in the order they
     * open, but only for a container with two or more of them: a single such child is not a
     * sibling of anything and stays unmarked. Deferred to the very end of [scan], because a
     * container's final child count is not known until its own closing brace -- or, for the
     * file's top level, the end of the file -- has been reached.
     */
    private fun finalizeSiblingOrdinals() {
        for (siblings in siblingsByContainer.values) {
            if (siblings.size < 2) {
                continue
            }
            for (index in siblings.indices) {
                siblings[index].siblingOrdinal = index + 1
            }
        }

        for (index in foundAccents.indices) {
            val ordinal = accentSourceBlocks[index].siblingOrdinal
            if (ordinal > 0) {
                foundAccents[index] = foundAccents[index].copy(siblingOrdinal = ordinal)
            }
        }
    }

    private fun isAccented(kind: BlockKind): Boolean {
        if (kind == BlockKind.TYPE) {
            return options.accentTypes
        }
        if (kind == BlockKind.FUNCTION) {
            return options.accentFunctions
        }
        if (kind == BlockKind.NAMESPACE) {
            return options.accentNamespaces
        }
        return false
    }

    /**
     * Finds the header this brace belongs to and hands it to [BlockClassifier].
     *
     * Only the part that needs scanner state lives here: which slice of the document is the
     * header. Reading that slice is the classifier's job, and it needs nothing else from here.
     */
    private fun classifyBlock(braceOffset: Int): OpenBlock {
        // Nothing to classify into: skip the slicing too, not just the classification.
        if (!options.accentTypes && !options.accentFunctions && !options.accentNamespaces) {
            return classifier.otherBlock(lineNumber)
        }

        var headerStart = headerStartFor(braceOffset)
        var headerEnd = braceOffset
        // The statement's own first line, which a multi-line signature keeps for all of them:
        // exactly the line the name is written on. Same correction as [headerStartFor] makes to
        // the offset, so the two never disagree about where the declaration began.
        var headerLineNumber: Int
        if (continuesDeclarationAbove()) {
            headerLineNumber = statementNumberBeforeContinuation
        } else {
            headerLineNumber = statementLineNumber
        }

        if (firstCodeOffset == braceOffset) {
            // the brace is the first code on the line, so the code is Allman and the header is above
            if (previousCodeEnd <= 0 || previousCodeEnd > braceOffset) {
                return classifier.otherBlock(lineNumber)
            }
            headerStart = previousCodeStart
            headerEnd = previousCodeEnd
            headerLineNumber = previousCodeLineNumber
        }

        if (headerStart >= headerEnd) {
            return classifier.otherBlock(lineNumber)
        }

        val rawHeader = text.subSequence(headerStart, headerEnd).toString()
        return classifier.classify(rawHeader, headerStart, lineNumber, headerLineNumber)
    }


    /**
     * Where this brace's header begins.
     *
     * Usually the start of the line the construct began on, so a multi-line signature is read
     * whole. But when the line already had braces, the header starts after the last of them:
     * otherwise in `class A { void M() {` the second brace would see `class` and pass as a type.
     *
     * And when this line is itself a continuation of the declaration above -- a `where` clause
     * or a constructor's `: base(...)` -- the statement started further up still. [finishLine]
     * undoes the premature reset too, but only once the line is over, which is too late for a
     * brace hanging off the end of that very line:
     * ```
     * public WithBaseCall(int value)
     *     : base() {
     * ```
     * Without this the header is `: base()` alone and the constructor is not seen at all.
     */
    private fun headerStartFor(braceOffset: Int): Int {
        var start = statementLineStartOffset
        if (continuesDeclarationAbove()) {
            start = statementStartBeforeContinuation
        }
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

    // ------------------------------------------------------------------ small helpers

    /**
     * `where T : IFoo` at the start of the line just finished.
     *
     * A generic method's constraint clause sits after the parameter list's closing `)`, so by
     * the time it is its own line, bracketDepth is already back to 0 and it looks exactly like
     * the start of a brand new statement -- see the caller in [finishLine].
     */
    /**
     * Whether the line being scanned right now continues the declaration above it, asked in the
     * middle of that line rather than at its end.
     *
     * [finishLine] asks the same question once the line is over and repairs the bookkeeping
     * then, which is soon enough for a brace on the NEXT line but too late for one hanging off
     * the end of this one. Both callers -- the header's offset and the header's line number --
     * go through here so they cannot disagree about where the declaration started.
     *
     * Deliberately not [followsUnfinishedDeclaration]: a trailing comma also ends every element
     * of a `{ }` initializer list, and `new Point2 {` on the line after one owns its own header.
     */
    private fun continuesDeclarationAbove(): Boolean {
        if (previousCodeEnd < 0 || firstCodeOffset < 0) {
            return false
        }
        return isWhereConstraintLine() || isColonContinuationLine()
    }

    private fun isWhereConstraintLine(): Boolean {
        if (!matchesAt(firstCodeOffset, WHERE_KEYWORD)) {
            return false
        }
        val following = charAt(firstCodeOffset + WHERE_KEYWORD.length)
        return !following.isLetterOrDigit() && following != '_'
    }

    /**
     * `: base(...)`, `: this(...)`, or a base-type list wrapped onto its own line, at the start
     * of the line just finished -- same idea as [isWhereConstraintLine], for the other clause
     * that regularly gets wrapped onto a line of its own. `::` (C++ scope resolution) is
     * excluded, since that starts an expression, not a continuation of the declaration above.
     */
    private fun isColonContinuationLine(): Boolean {
        return charAt(firstCodeOffset) == ':' && charAt(firstCodeOffset + 1) != ':'
    }

    /**
     * Whether the code line above ended in the middle of something, so this line finishes it:
     * a trailing `,` or a trailing `:` (`::` excluded, as in [isColonContinuationLine]).
     *
     * This is what carries a base-type list past its first wrapped line. `: IBar,` is recognised
     * by its own leading colon, but the `IBaz` under it starts with an ordinary identifier and
     * is indistinguishable from a brand new statement -- except that the line above it promised
     * more. Without this the header the classifier sees shrinks to that last line alone and a
     * class with two interfaces over three lines is not recognised as a class at all.
     *
     * Read only for the header bookkeeping, never for the phantom indent: a trailing comma is
     * also how every element of a `{ }` initializer list ends, and those must keep taking their
     * indent from their own line.
     */
    private fun followsUnfinishedDeclaration(): Boolean {
        if (previousCodeEnd <= 0 || previousCodeEnd > textLength) {
            return false
        }
        val last = text[previousCodeEnd - 1]
        if (last == ',') {
            return true
        }
        return last == ':' && charAt(previousCodeEnd - 2) != ':'
    }

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

        /** The one keyword that can continue a declaration's header after its own line closes. */
        private const val WHERE_KEYWORD = "where"

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
