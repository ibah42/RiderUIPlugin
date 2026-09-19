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

    /**
     * Where the parameter list opens: the first top-level `(...)` group that the declaration
     * ends with -- nothing after it, or a constructor initializer (`: base(...)`) or a `where`
     * clause.
     *
     * Not simply the first `(`, which is what this used to be: a tuple return type puts a pair
     * of parentheses in front of the name -- `public (int, T1 result1) GetResult(short token)` --
     * and taking that one makes the modifier before it, `public`, look like the method's name.
     * Generated code is full of that shape.
     *
     * Not the last `(` either: `public Service(ILogger logger) : base(logger)` would then be
     * named after its base call. What tells the two apart is not position but what follows the
     * group -- a tuple return type is followed by the name it qualifies, a parameter list by
     * the end of the declaration.
     *
     * String and character literals are stepped over, since a default parameter value may
     * legitimately contain a bracket: `void Write(string suffix = ")")`.
     *
     * When no group qualifies the first top-level `(` is returned, which is what this used to
     * do unconditionally. A header can be legitimately unbalanced -- `Run(delegate(int x) {`
     * is sliced from inside a call that has not closed yet -- and there the old answer is
     * still the right one.
     *
     * @return the index, or -1 when the header has no parentheses at all
     */
    fun parameterListStart(header: String): Int {
        var groupStart = -1
        var firstGroupStart = -1
        var depth = 0
        var index = 0
        var inString = false
        var inChar = false

        while (index < header.length) {
            val character = header[index]
            if (inString) {
                if (character == '\\') {
                    index++
                } else if (character == '"') {
                    inString = false
                }
            } else if (inChar) {
                if (character == '\\') {
                    index++
                } else if (character == '\'') {
                    inChar = false
                }
            } else if (character == '"') {
                inString = true
            } else if (character == '\'') {
                inChar = true
            } else if (character == '(') {
                if (depth == 0) {
                    groupStart = index
                    if (firstGroupStart < 0) {
                        firstGroupStart = index
                    }
                }
                depth++
            } else if (character == ')' && depth > 0) {
                depth--
                if (depth == 0 && endsTheDeclaration(header, index + 1)) {
                    return groupStart
                }
            }
            index++
        }
        return firstGroupStart
    }

    /** Whether the declaration is over at [index], bar a constructor initializer or a `where`. */
    private fun endsTheDeclaration(header: String, index: Int): Boolean {
        var cursor = index
        while (cursor < header.length && header[cursor].isWhitespace()) {
            cursor++
        }
        if (cursor >= header.length) {
            return true
        }
        if (header[cursor] == ':') {
            return true
        }
        return identifierAt(header, cursor) in WHERE_KEYWORDS
    }

    /**
     * Start of the last identifier ending before [endExclusive], stepping back over whatever
     * separates them -- a colon, a dot, a bracket, spaces.
     *
     * [identifierStartBefore] needs an identifier character right at the boundary; this is for
     * the callers that only know the identifier is somewhere to the left. `animations:^` and
     * `.main.async ` both answer with the word a reader would name the block after.
     */
    fun lastIdentifierStartBefore(header: String, endExclusive: Int): Int {
        var cursor = minOf(endExclusive, header.length)
        while (cursor > 0 && !isIdentifierChar(header[cursor - 1])) {
            cursor--
        }
        if (cursor <= 0) {
            return -1
        }
        return identifierStartBefore(header, cursor)
    }

    /** Index just past the `)` that closes the group opening at [from]; the end if unbalanced. */
    fun skipBalancedParens(header: String, from: Int): Int {
        var depth = 0
        var cursor = from

        while (cursor < header.length) {
            if (header[cursor] == '(') {
                depth++
            }
            if (header[cursor] == ')') {
                depth--
                if (depth == 0) {
                    return cursor + 1
                }
            }
            cursor++
        }
        return header.length
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
     * would see the word `class` and pass as a type declaration. Literals are cut for the same
     * reason: `if (Check("class")) {` must not read as a type either. Safe only for a keyword
     * search (a namespace's or a type's own keyword) -- see [declarationPartKeepingLiterals] for
     * anything that needs the rest of the header intact, such as a default parameter value.
     */
    fun declarationPart(header: String): String {
        val withoutLiterals = header.substring(0, quoteLimit(header))
        val whereIndex = whereClauseIndex(header)
        if (whereIndex < 0) {
            return withoutLiterals
        }
        return withoutLiterals.substring(0, whereIndex)
    }

    /**
     * The header without its constraint clause, but otherwise untouched -- a string literal
     * that legitimately belongs to it, such as a default parameter value (`string reason = ""`),
     * survives. [findKeyword] for `where` still only searches up to the first quote, exactly
     * like [declarationPart], so a stray `where` inside a literal cannot be mistaken for a real
     * constraint clause either.
     *
     * Function, constructor, destructor and property classification all use this instead of
     * [declarationPart]: none of them does a free keyword search over the whole header, so none
     * of them needs literals cut, and cutting them would truncate a real declaration that simply
     * happens to have a string in it.
     */
    fun declarationPartKeepingLiterals(header: String): String {
        val whereIndex = whereClauseIndex(header)
        if (whereIndex < 0) {
            return header
        }
        return header.substring(0, whereIndex)
    }

    /**
     * Where a `where ...` constraint clause starts, searched only up to the header's first
     * quote so a keyword-shaped word inside a string literal is never mistaken for one. -1 when
     * there is no such clause.
     */
    private fun whereClauseIndex(header: String): Int {
        return findKeyword(header.substring(0, quoteLimit(header)), WHERE_KEYWORDS)
    }

    /** The one constraint keyword worth cutting: what follows it is no longer the declaration. */
    private val WHERE_KEYWORDS = setOf("where")
}
