package com.hitapps.allmanview.scan

/**
 * Диалект для лексера. Нужен только чтобы правильно находить границы строковых литералов —
 * всё остальное у C-подобных языков одинаково.
 */
enum class Flavor {
    /** C#: `@"verbatim"`, `"""raw"""`, `$"interp"` */
    CSHARP,

    /** C/C++/шейдеры: `R"delim(raw)delim"`, `1'000'000` */
    CPP,

    /** Java, Kotlin, Scala, Groovy, Swift, Dart: `"""` текстовые блоки */
    JVM,

    /** JS, TS, Go, PHP: `` `template ${literals}` `` */
    WEB,

    /** Всё остальное: только `"..."` и `'...'`. Безопасный дефолт. */
    GENERIC,
}

/** Что именно разносить. */
data class ScanOptions(
    /** Разносить `} else {` на три строки, а не только висящую `{`. */
    val fullAllman: Boolean = true,

    /** Разносить `if (x) return;` на две строки. */
    val splitStatements: Boolean = true,

    /** Разворачивать `if (x) { Foo(); }` на четыре строки. */
    val expandInlineBlocks: Boolean = true,

    /** Один уровень отступа: четыре пробела, два пробела или таб. */
    val indentUnit: String = "    ",
)

/**
 * Одно место, где мы визуально ломаем строку на Allman.
 *
 * Исходный текст не прячется — он остаётся на месте и гасится в серый,
 * а под строкой дорисовывается фантом обычным цветом.
 *
 * @param dimStart offset первого гасимого символа
 * @param dimEnd offset конца гасимого куска (exclusive)
 * @param anchorOffset offset конца строки-владельца — якорь для block inlay
 * @param indent ведущий whitespace строки-владельца, как есть (табы/пробелы)
 * @param phantomLines что рисуем фантомными строками, сверху вниз; собственный отступ
 *   вложенных строк уже включён в текст
 */
data class PhantomSite(
    val dimStart: Int,
    val dimEnd: Int,
    val anchorOffset: Int,
    val indent: String,
    val phantomLines: List<String>,
)

/** Состояние лексера. */
private enum class LexerState {
    CODE,
    LINE_COMMENT,
    BLOCK_COMMENT,

    /** `"..."` с обратным слешем как экранированием. */
    STRING,

    /** `'x'` — символьный литерал. */
    CHARACTER,

    /** C#: `@"..."`, где кавычка экранируется удвоением. */
    VERBATIM_STRING,

    /** `"""..."""` — C# raw strings, текстовые блоки Java/Kotlin/Swift. */
    TRIPLE_QUOTED_STRING,

    /** C++: `R"delim(...)delim"`. */
    CPP_RAW_STRING,

    /** `` `...` `` с дырками `${...}`. */
    BACKTICK_TEMPLATE,
}

/** Строковый контекст, в который надо вернуться, когда закроется дырка интерполяции. */
private class InterpolationFrame(
    val lexerState: LexerState,
    val interpolationDollars: Int,
    val rawQuoteCount: Int,
    val cppRawDelimiter: String,
    val holeBraceDepth: Int,
)

/** Разобранный префикс строкового литерала: `$`, `@`, `R`. */
private class LiteralPrefix(
    val dollars: Int,
    val isVerbatim: Boolean,
    val isCppRaw: Boolean,
)

/**
 * Заголовок управляющей конструкции в начале строки: `if (x)`, `foreach (var a in b)`,
 * `else`, `} catch (E e)`.
 */
private class LineHeader(
    /** offset начала заголовка — уже после ведущей `}`, если она есть. */
    val headerStart: Int,

    /** offset сразу после заголовка. */
    val headerEnd: Int,

    /** Строка начинается с `}`, которую надо оставить на своём месте. */
    val hasLeadingCloseBrace: Boolean,

    /** Первое ключевое слово заголовка: `if`, `else`, `catch`, `while`. */
    val firstKeyword: String,
)

/**
 * Линейный скан документа. Никаких зависимостей от IntelliJ — чистая функция от текста.
 *
 * Однострочные состояния (строка в кавычках, символьный литерал, `//`) принудительно
 * сбрасываются на переводе строки: это ограничивает возможный рассинхрон ровно одной
 * строкой, а не всем хвостом файла.
 */
class BraceScanner(
    private val text: CharSequence,
    private val flavor: Flavor,
    private val options: ScanOptions = ScanOptions(),
) {
    // Возможности диалекта. Держим флагами, а не сравнениями с enum по всему коду:
    // языков много, а различаются они ровно тем, как устроены строковые литералы.
    private val supportsVerbatimStrings = flavor == Flavor.CSHARP
    private val supportsTripleQuotedStrings = flavor == Flavor.CSHARP || flavor == Flavor.JVM
    private val supportsCppRawStrings = flavor == Flavor.CPP
    private val supportsDigitSeparatorQuote = flavor == Flavor.CPP
    private val supportsBacktickTemplates = flavor == Flavor.WEB

    private val foundSites = ArrayList<PhantomSite>()
    private val textLength = text.length

    /** Курсор по документу. */
    private var position = 0

    // --- состояние текущей строки ---

    private var lineStartOffset = 0
    private var lineNumber = 0

    /** Строка начинается в коде, а не внутри многострочного литерала или комментария. */
    private var lineStartsInCode = true

    private var firstCodeOffset = -1
    private var lastCodeOffset = -1

    /**
     * Offsets круглых скобок и фигурных скобок, закрывшихся на верхнем уровне этой строки.
     * Позволяют резать строку по offset-ам, а не разбором подстроки: подстрока может
     * содержать литерал со скобками внутри, а эти offsets лексер уже отфильтровал.
     */
    private val lineParenCloseOffsets = ArrayList<Int>()
    private val lineBraceOpenOffsets = ArrayList<Int>()
    private val lineBraceCloseOffsets = ArrayList<Int>()

    // --- многострочные конструкции ---

    /**
     * Глубина `(` и `[`. Нужна, чтобы у многострочной сигнатуры
     * ```
     * void Foo(
     *     int a,
     *     int b) {
     * ```
     * фантомная скобка встала под `void`, а не под `int b`.
     */
    private var bracketDepth = 0
    private var bracketDepthAtLineStart = 0
    private var statementLineStartOffset = 0
    private var statementLineNumber = 0

    // --- состояние лексера ---

    private var lexerState = LexerState.CODE

    /** Сколько `$` стоит перед кавычкой: `$"..."` → 1, `$$"""..."""` → 2, не интерполяция → 0. */
    private var interpolationDollars = 0

    /** Длина открывающей серии кавычек для [LexerState.TRIPLE_QUOTED_STRING]. */
    private var rawQuoteCount = 0

    /** Разделитель из `R"delim(`. */
    private var cppRawDelimiter = ""

    private val interpolationStack = ArrayDeque<InterpolationFrame>()

    /** Глубина `{}` внутри текущей дырки интерполяции. */
    private var holeBraceDepth = 0

    fun scan(): List<PhantomSite> {
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
        return foundSites
    }

    // ---------------------------------------------------------------- состояния лексера

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
                // в verbatim-строке кавычка экранируется удвоением, а не слешем
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

    /** `{` внутри строки: либо открывает дырку интерполяции, либо просто содержимое. */
    private fun stepInterpolationBraceOrSkip() {
        if (interpolationDollars > 0) {
            handleInterpolationBrace()
        } else {
            position++
        }
    }

    // ---------------------------------------------------------------- открытие литералов

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
            // пустой литерал "" или @""
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

    /** Префикс вплотную к кавычке: `$`, `@`, `R`. За предыдущую строку не заезжаем. */
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

        // на raw string не похоже — считаем обычной строкой
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

    /** C++: в `1'000'000` апостроф разделяет разряды, а не открывает символьный литерал. */
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

    // ---------------------------------------------------------------- интерполяция

    private fun handleInterpolationBrace() {
        val braceRun = countRepeated('{', position)

        // при одном $ последовательность {{ — это экранированная скобка, а не дырка
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
     * Закрывает текущий литерал. Если мы внутри дырки интерполяции, стек не трогаем:
     * вернуться в объемлющую строку должна закрывающая `}`, а не эта кавычка.
     */
    private fun closeStringLiteral() {
        lexerState = LexerState.CODE
        interpolationDollars = 0
        rawQuoteCount = 0
        cppRawDelimiter = ""
    }

    // ---------------------------------------------------------------- построчный разбор

    private fun finishLine(lineEndOffset: Int) {
        emitLine(lineEndOffset)

        val isUnterminatedSingleLine = lexerState == LexerState.LINE_COMMENT ||
            lexerState == LexerState.STRING ||
            lexerState == LexerState.CHARACTER

        if (isUnterminatedSingleLine) {
            // рассинхрон дальше не тащим
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
            // `{` и есть первый код-символ — строка уже в Allman
            return
        }

        val head = text.subSequence(firstCodeOffset, braceOffset)
            .toString()
            .trimEnd()

        if (head.isEmpty()) {
            return
        }

        if (options.fullAllman && head.startsWith("}")) {
            val tail = head.substring(1).trimStart()
            if (isSplitKeyword(tail)) {
                // гасим всё, что уехало вниз: " else {"
                addSite(
                    dimStart = firstCodeOffset + 1,
                    dimEnd = braceOffset + 1,
                    anchorOffset = lineEndOffset,
                    indent = indent,
                    phantomLines = listOf(tail, "{"),
                )
                return
            }
        }

        // гасим только саму скобку — пробелы перед ней и так не видно
        addSite(
            dimStart = braceOffset,
            dimEnd = braceOffset + 1,
            anchorOffset = lineEndOffset,
            indent = indent,
            phantomLines = listOf("{"),
        )
    }

    /**
     * `if (x) { Foo(); }` → заголовок остаётся, а блок разворачивается на три строки.
     *
     * Заголовок обязан быть управляющей конструкцией, иначе под правило попало бы
     * автосвойство `public int X { get; set; }`, которое разносить не надо.
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

        val inner = text.subSequence(openOffset + 1, lastCodeOffset).toString().trim()
        val blockLines = ArrayList<String>()
        blockLines.add("{")
        if (inner.isNotEmpty()) {
            blockLines.add(options.indentUnit + inner)
        }
        blockLines.add("}")

        if (movesLeadingCloseBrace(header)) {
            val headerText = text.subSequence(header.headerStart, header.headerEnd).toString().trim()
            val phantomLines = ArrayList<String>()
            phantomLines.add(headerText)
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
     * `if (x) return;` → заголовок остаётся, инструкция уезжает вниз с отступом.
     * Сюда же попадает `} else` без инструкции — он разносится по правилам полного Allman.
     */
    private fun emitStatementSplit(lineEndOffset: Int, indent: String) {
        val header = parseLineHeader()
        if (header == null) {
            return
        }

        val statementStart = skipSpacesFrom(header.headerEnd)
        val statement = text.subSequence(header.headerEnd, lastCodeOffset + 1).toString().trim()
        val headerText = text.subSequence(header.headerStart, header.headerEnd).toString().trim()

        // пустая инструкция `while (x);` и голое `{` — не наш случай
        val hasStatement = statement.isNotEmpty() &&
            statement != ";" &&
            !statement.startsWith("{") &&
            options.splitStatements

        if (movesLeadingCloseBrace(header)) {
            if (headerText.isEmpty()) {
                return
            }
            val phantomLines = ArrayList<String>()
            phantomLines.add(headerText)
            if (hasStatement) {
                phantomLines.add(options.indentUnit + statement)
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
            phantomLines = listOf(options.indentUnit + statement),
        )
    }

    /**
     * Разбирает заголовок управляющей конструкции в начале строки.
     *
     * Режем по offset-ам, которые проставил лексер, а не по подстроке: в `if (Check(")")) Foo();`
     * наивный поиск закрывающей скобки нашёл бы её внутри литерала.
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
                // `using System.Text;` — директива, а не конструкция со скобками
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
     * Отступ для фантомной строки. Если строка — продолжение незакрытой `(` или `[`,
     * берём отступ у строки, с которой конструкция началась. Ограничение по длине —
     * страховка от рассинхрона на несбалансированных скобках.
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
        phantomLines: List<String>,
    ) {
        if (dimStart >= dimEnd) {
            return
        }
        if (phantomLines.isEmpty()) {
            return
        }
        foundSites.add(PhantomSite(dimStart, dimEnd, anchorOffset, indent, phantomLines))
    }

    private fun isSplitKeyword(tail: String): Boolean {
        for (keyword in SPLIT_KEYWORDS) {
            if (!tail.startsWith(keyword)) {
                continue
            }
            val following = tail.getOrNull(keyword.length)
            if (following == null) {
                return true
            }
            if (!following.isLetterOrDigit() && following != '_') {
                return true
            }
        }
        return false
    }

    // ---------------------------------------------------------------- мелкие помощники

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

    /** Ведущая `}` строки в баланс блока не входит — считаем только то, что после заголовка. */
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

    /** Не перепрыгиваем через перевод строки — иначе потеряем разметку строк. */
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

        /** Ключевые слова, с которых может начинаться разносимая конструкция. */
        private val HEADER_KEYWORDS = listOf(
            "foreach", "finally", "while", "catch", "fixed", "using", "lock", "else", "for", "if",
        )

        /**
         * Без круглых скобок эти слова означают что-то другое:
         * `using System;` — директива, `for` без скобок не бывает вовсе.
         */
        private val KEYWORDS_REQUIRING_PARENS = setOf(
            "if", "for", "foreach", "while", "using", "lock", "fixed",
        )

        /** Насколько далеко назад разрешено искать начало многострочной конструкции. */
        private const val MAX_CONTINUATION_LINES = 40

        /** По стандарту разделитель в `R"delim(` не длиннее 16 символов. */
        private const val MAX_CPP_RAW_DELIMITER = 16

        /** Сколько скобок на строке запоминаем; дальше строка явно не про нас. */
        private const val MAX_TRACKED_OFFSETS = 16

        /** Возвращается вместо символа за границами документа. */
        private val NO_CHARACTER = Char.MIN_VALUE
    }
}
