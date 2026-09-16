package com.hitapps.allmanview.scan

/**
 * Диалект для лексера. Нужен только чтобы правильно находить границы строковых литералов —
 * всё остальное у C-подобных языков одинаково.
 */
enum class Flavor {
    /** @"verbatim", """raw""", $"interp" */
    CSHARP,

    /** R"delim(raw)delim", 1'000'000 */
    CPP,

    /** `template ${literals}` */
    GENERIC,
}

/**
 * Одно место, где мы визуально ломаем строку на Allman.
 *
 * @param hideStart  offset первого прячущегося символа
 * @param hideEnd    offset конца прячущегося куска (exclusive)
 * @param anchorOffset offset конца строки-владельца — якорь для block inlay
 * @param indent     ведущий whitespace строки-владельца, как есть (табы/пробелы)
 * @param phantomLines что рисуем фантомными строками, сверху вниз
 */
data class PhantomSite(
    val hideStart: Int,
    val hideEnd: Int,
    val anchorOffset: Int,
    val indent: String,
    val phantomLines: List<String>,
)

private const val NORMAL = 0
private const val LINE_COMMENT = 1
private const val BLOCK_COMMENT = 2
private const val STRING = 3
private const val CHAR = 4
private const val VERBATIM = 5
private const val RAW_CS = 6
private const val RAW_CPP = 7
private const val TEMPLATE = 8

private class Frame(
    val state: Int,
    val dollars: Int,
    val rawQuotes: Int,
    val delim: String,
    val holeDepth: Int,
)

/**
 * Линейный скан документа. Никаких зависимостей от IntelliJ — чистая функция от текста.
 *
 * Однострочные состояния (строка в кавычках, char, //) принудительно сбрасываются на \n:
 * это ограничивает возможный рассинхроном ровно одной строкой, а не всем хвостом файла.
 */
class BraceScanner(
    private val text: CharSequence,
    private val flavor: Flavor,
    private val fullAllman: Boolean,
) {
    private val result = ArrayList<PhantomSite>()
    private val n = text.length
    private var i = 0

    // состояние текущей строки
    private var lineStart = 0
    private var lineClean = true
    private var firstCode = -1
    private var lastCode = -1

    // состояние лексера
    private var state = NORMAL
    private var dollars = 0
    private var rawQuotes = 0
    private var cppDelim = ""
    private val stack = ArrayDeque<Frame>()
    private var holeDepth = 0

    fun scan(): List<PhantomSite> {
        while (i < n) {
            val c = text[i]
            if (c == '\n') {
                finishLine(i)
                i++
                continue
            }
            when (state) {
                NORMAL -> stepNormal(c)
                LINE_COMMENT -> i++
                BLOCK_COMMENT -> stepBlockComment(c)
                STRING -> stepString(c)
                CHAR -> stepChar(c)
                VERBATIM -> stepVerbatim(c)
                RAW_CS -> stepRawCs(c)
                RAW_CPP -> stepRawCpp(c)
                TEMPLATE -> stepTemplate(c)
                else -> i++
            }
        }
        finishLine(n)
        return result
    }

    // ---------------------------------------------------------------- состояния

    private fun stepNormal(c: Char) {
        when {
            c == '/' && peek(1) == '/' -> {
                state = LINE_COMMENT; i += 2
            }
            c == '/' && peek(1) == '*' -> {
                state = BLOCK_COMMENT; i += 2
            }
            c == '"' -> openDoubleQuote()
            c == '`' && flavor == Flavor.GENERIC -> {
                markCode(i); state = TEMPLATE; dollars = 1; i++
            }
            c == '\'' -> openSingleQuote()
            c == '{' -> {
                markCode(i); if (stack.isNotEmpty()) holeDepth++; i++
            }
            c == '}' -> {
                if (stack.isNotEmpty() && holeDepth == 0) {
                    closeHole()
                } else {
                    markCode(i); if (stack.isNotEmpty()) holeDepth--; i++
                }
            }
            c == ' ' || c == '\t' || c == '\r' -> i++
            else -> {
                markCode(i); i++
            }
        }
    }

    private fun stepBlockComment(c: Char) {
        if (c == '*' && peek(1) == '/') {
            state = NORMAL; i += 2
        } else i++
    }

    private fun stepString(c: Char) {
        when {
            c == '\\' -> advanceEscape()
            c == '"' -> {
                i++; popString()
            }
            dollars > 0 && c == '{' -> handleInterpOpen()
            else -> i++
        }
    }

    private fun stepChar(c: Char) {
        when {
            c == '\\' -> advanceEscape()
            c == '\'' -> {
                i++; state = NORMAL
            }
            else -> i++
        }
    }

    private fun stepVerbatim(c: Char) {
        when {
            c == '"' && peek(1) == '"' -> i += 2
            c == '"' -> {
                i++; popString()
            }
            dollars > 0 && c == '{' -> handleInterpOpen()
            else -> i++
        }
    }

    private fun stepRawCs(c: Char) {
        when {
            c == '"' -> {
                var q = 0
                while (i + q < n && text[i + q] == '"') q++
                i += q
                if (q >= rawQuotes) popString()
            }
            dollars > 0 && c == '{' -> handleInterpOpen()
            else -> i++
        }
    }

    private fun stepRawCpp(c: Char) {
        if (c == ')' && matchesAt(i + 1, cppDelim) && charAt(i + 1 + cppDelim.length) == '"') {
            i += 1 + cppDelim.length + 1
            popString()
            return
        }
        i++
    }

    private fun stepTemplate(c: Char) {
        when {
            c == '\\' -> advanceEscape()
            c == '`' -> {
                i++; popString()
            }
            c == '$' && peek(1) == '{' -> {
                pushHole(); i += 2
            }
            else -> i++
        }
    }

    // ---------------------------------------------------------------- открытие литералов

    private fun openDoubleQuote() {
        val start = i

        // префикс вплотную к кавычке: $ @ R (не заезжая на предыдущую строку)
        var p = i - 1
        var d = 0
        var verbatim = false
        var cppRaw = false
        while (p >= lineStart) {
            val pc = text[p]
            when {
                pc == '$' -> {
                    d++; p--
                }
                pc == '@' && flavor == Flavor.CSHARP -> {
                    verbatim = true; p--
                }
                pc == 'R' && flavor == Flavor.CPP -> {
                    cppRaw = true; break
                }
                else -> break
            }
        }

        markCode(start)

        if (cppRaw) {
            var j = i + 1
            val sb = StringBuilder()
            while (j < n && text[j] != '(' && text[j] != '\n' && sb.length < 16) {
                sb.append(text[j]); j++
            }
            if (j < n && text[j] == '(') {
                cppDelim = sb.toString()
                state = RAW_CPP
                i = j + 1
                return
            }
            state = STRING; dollars = 0; i++
            return
        }

        var q = 0
        while (i + q < n && text[i + q] == '"') q++

        if (q >= 3) {
            rawQuotes = q; dollars = d; state = RAW_CS; i += q
            return
        }
        if (q == 2) {
            i += 2 // пустой литерал "" / @""
            return
        }
        dollars = d
        state = if (verbatim) VERBATIM else STRING
        i++
    }

    private fun openSingleQuote() {
        // C++: разделитель разрядов 1'000'000 — это не char-литерал
        if (flavor == Flavor.CPP &&
            i > lineStart && text[i - 1].isLetterOrDigit() &&
            i + 1 < n && text[i + 1].isLetterOrDigit()
        ) {
            markCode(i); i++
            return
        }
        markCode(i); state = CHAR; i++
    }

    // ---------------------------------------------------------------- интерполяция

    private fun handleInterpOpen() {
        var run = 0
        while (i + run < n && text[i + run] == '{') run++
        if (dollars in 1..(run / 2)) { // {{ при $ — экранированная скобка
            i += 2 * dollars
            return
        }
        if (run >= dollars) {
            pushHole()
            i += run
            return
        }
        i += run
    }

    private fun pushHole() {
        stack.addLast(Frame(state, dollars, rawQuotes, cppDelim, holeDepth))
        holeDepth = 0
        state = NORMAL
        dollars = 0
        rawQuotes = 0
        cppDelim = ""
    }

    private fun closeHole() {
        val f = stack.removeLast()
        state = f.state
        dollars = f.dollars
        rawQuotes = f.rawQuotes
        cppDelim = f.delim
        holeDepth = f.holeDepth
        i++
    }

    private fun popString() {
        state = NORMAL
        dollars = 0
        rawQuotes = 0
        cppDelim = ""
    }

    // ---------------------------------------------------------------- построчный разбор

    private fun finishLine(end: Int) {
        emitLine(end)
        if (state == LINE_COMMENT || state == STRING || state == CHAR) {
            // незакрытая однострочная конструкция — рассинхрон дальше не тащим
            state = NORMAL
            dollars = 0
        }
        lineStart = end + 1
        lineClean = state == NORMAL
        firstCode = -1
        lastCode = -1
    }

    private fun emitLine(end: Int) {
        if (!lineClean || firstCode < 0 || lastCode < 0) return

        var k = lineStart
        while (k < end && (text[k] == ' ' || text[k] == '\t')) k++
        val indent = text.subSequence(lineStart, k).toString()

        if (text[lastCode] == '{') {
            val braceOffset = lastCode
            if (braceOffset <= firstCode) return // '{' и есть первый код-символ — уже Allman

            val head = text.subSequence(firstCode, braceOffset).toString().trimEnd()
            if (head.isEmpty()) return

            if (fullAllman && head.startsWith("}")) {
                val rest = head.substring(1).trimStart()
                if (isSplitKeyword(rest)) {
                    add(firstCode + 1, braceOffset + 1, end, indent, listOf(rest, "{"))
                    return
                }
            }
            add(firstCode + head.length, braceOffset + 1, end, indent, listOf("{"))
        } else if (fullAllman && text[firstCode] == '}' && lastCode > firstCode) {
            // "} else" без скобки — тоже разносим
            val rest = text.subSequence(firstCode + 1, lastCode + 1).toString().trimStart()
            if (isSplitKeyword(rest)) {
                add(firstCode + 1, lastCode + 1, end, indent, listOf(rest))
            }
        }
    }

    private fun add(hideStart: Int, hideEnd: Int, anchor: Int, indent: String, lines: List<String>) {
        if (hideStart >= hideEnd) return
        result.add(PhantomSite(hideStart, hideEnd, anchor, indent, lines))
    }

    private fun isSplitKeyword(rest: String): Boolean {
        for (kw in SPLIT_KEYWORDS) {
            if (rest.startsWith(kw)) {
                val after = rest.getOrNull(kw.length)
                if (after == null || !(after.isLetterOrDigit() || after == '_')) return true
            }
        }
        return false
    }

    // ---------------------------------------------------------------- мелочи

    private fun markCode(idx: Int) {
        if (firstCode < 0) firstCode = idx
        lastCode = idx
    }

    private fun advanceEscape() {
        // не перепрыгиваем через перевод строки — иначе потеряем разметку строк
        i += if (i + 1 < n && text[i + 1] != '\n') 2 else 1
    }

    private fun peek(d: Int): Char = charAt(i + d)

    private fun charAt(idx: Int): Char = if (idx in 0 until n) text[idx] else ' '

    private fun matchesAt(idx: Int, s: String): Boolean {
        if (idx + s.length > n) return false
        for (k in s.indices) if (text[idx + k] != s[k]) return false
        return true
    }

    companion object {
        private val SPLIT_KEYWORDS = listOf("else", "catch", "finally")
    }
}
