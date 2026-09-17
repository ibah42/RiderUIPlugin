package com.hitapps.allmanview.scan

/**
 * Reads the pieces of a declaration header: where a keyword sits, where an identifier starts
 * and ends, where the parentheses and the generic arguments are.
 *
 * Every function here is a pure function of the string it is given -- no scanner state, no
 * document, no position. That is what makes the classification above it testable on its own,
 * and it is why this is an object rather than part of the scanner.
 */
internal object HeaderReader {

    fun lastUnclosedParen(header: String): Int {
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
    fun quoteLimit(header: String): Int {
        val quoteIndex = header.indexOf('"')
        if (quoteIndex < 0) {
            return header.length
        }
        return quoteIndex
    }

    fun findKeyword(header: String, keywords: Set<String>): Int {
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

    fun keywordAt(header: String, index: Int, keywords: Set<String>): String? {
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

    fun endsWithWord(header: String, word: String): Boolean {
        if (!header.endsWith(word)) {
            return false
        }
        val before = header.getOrNull(header.length - word.length - 1)
        return before == null || !isIdentifierChar(before)
    }

    /** In `Foo<T>(` the name precedes the generic parameters, so `<...>` is rewound. */
    fun skipGenericsBefore(header: String, parenIndex: Int): Int {
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
    fun assignmentIndex(header: String): Int {
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

    fun containsAssignment(header: String): Boolean {
        return assignmentIndex(header) >= 0
    }

    fun skipSpacesIn(header: String, from: Int): Int {
        var cursor = from
        while (cursor < header.length && header[cursor].isWhitespace()) {
            cursor++
        }
        return cursor
    }

    fun identifierStartAt(header: String, from: Int): Int {
        val cursor = skipSpacesIn(header, from)
        if (cursor >= header.length) {
            return -1
        }
        if (!isIdentifierStart(header[cursor])) {
            return -1
        }
        return cursor
    }

    fun identifierStartBefore(header: String, endExclusive: Int): Int {
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

    fun identifierAt(header: String, start: Int): String {
        var end = start
        while (end < header.length && isIdentifierChar(header[end])) {
            end++
        }
        return header.substring(start, end)
    }

    fun isIdentifierChar(character: Char): Boolean {
        return character.isLetterOrDigit() || character == '_'
    }

    fun isIdentifierStart(character: Char): Boolean {
        return character.isLetter() || character == '_'
    }

    /**
     * The header without literals and without constraints.
     *
     * Constraints are cut before classification, otherwise `void Bind<T>(T v) where T : class {`
     * would see the word `class` and pass as a type declaration.
     */
    fun declarationPart(header: String): String {
        val withoutLiterals = header.substring(0, quoteLimit(header))
        val whereIndex = findKeyword(withoutLiterals, WHERE_KEYWORDS)
        if (whereIndex < 0) {
            return withoutLiterals
        }
        return withoutLiterals.substring(0, whereIndex)
    }

    /** The one constraint keyword worth cutting: what follows it is no longer the declaration. */
    private val WHERE_KEYWORDS = setOf("where")
}
