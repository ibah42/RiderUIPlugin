package com.hitapps.allmanview.scan

/** An open block on the nesting stack. */
internal class OpenBlock(
    val kind: BlockKind,
    val nameOffset: Int,
    val nameLength: Int,
    val keyword: String,
    val openLineNumber: Int,
    val isLambda: Boolean = false,

    /** Where the declaration starts, which for a multi-line signature is not the brace line. */
    val headerOffset: Int = -1,
) {
    /** Filled in when the block is pushed, so the closing brace reports the same value. */
    var isNested: Boolean = false

    /** The block on top of the stack when this one was opened; null at the top of the file. */
    var parent: OpenBlock? = null

    /**
     * 1-based position among [parent]'s type/namespace children, filled in by
     * [BraceScanner.finalizeSiblingOrdinals] once the whole file has been scanned. Stays 0 --
     * meaning "nothing to number" -- until then, and forever if [parent] never grows a second
     * such child.
     */
    var siblingOrdinal: Int = 0
}

/**
 * Decides what a curly block belongs to, given the text of its header.
 *
 * Knows nothing about where the scanner is in the document: the header slice and the line
 * number are handed in, and the answer is a function of those alone. Everything that has to be
 * looked up in scanner state -- where the header starts, whether the brace is the first code on
 * its line -- is settled before this is called, in BraceScanner.classifyBlock.
 */
internal class BlockClassifier(private val options: ScanOptions) {

    /**
     * Order matters: a namespace header matches nothing else, so it goes first; a function is
     * last because it is the fallback that also recognises lambdas, constructors, destructors,
     * property accessors and the property declaration itself.
     */
    fun classify(rawHeader: String, headerStart: Int, lineNumber: Int): OpenBlock {
        val header = HeaderReader.declarationPart(rawHeader)

        if (options.accentNamespaces) {
            val namespaceBlock = classifyNamespaceHeader(header, headerStart, lineNumber)
            if (namespaceBlock != null) {
                return namespaceBlock
            }
        }
        if (options.accentTypes) {
            val typeBlock = classifyTypeHeader(header, headerStart, lineNumber)
            if (typeBlock != null) {
                return typeBlock
            }
        }
        if (options.accentFunctions) {
            return classifyFunctionHeader(header, headerStart, lineNumber)
        }
        return otherBlock(lineNumber)
    }

    /**
     * `namespace Foo.Bar {`.
     *
     * No name is recorded on purpose: the label is the bare [NAMESPACE_LABEL], since the name of
     * a namespace is long, repeated on every file and carries nothing the closing brace needs.
     * With no name to sample, the colour is instead sampled from the `namespace` keyword at
     * [headerStart] -- see BraceAccentStyle.baseColor.
     */
    private fun classifyNamespaceHeader(header: String, headerStart: Int, lineNumber: Int): OpenBlock? {
        if (HeaderReader.findKeyword(header, NAMESPACE_KEYWORDS) < 0) {
            return null
        }
        return OpenBlock(
            BlockKind.NAMESPACE,
            -1,
            0,
            NAMESPACE_LABEL,
            lineNumber,
            headerOffset = headerStart,
        )
    }

    private fun classifyTypeHeader(header: String, headerStart: Int, lineNumber: Int): OpenBlock? {
        val keywordIndex = HeaderReader.findKeyword(header, TYPE_KEYWORDS)
        if (keywordIndex < 0) {
            return null
        }
        val keyword = HeaderReader.keywordAt(header, keywordIndex, TYPE_KEYWORDS) ?: return null

        // `record struct Point`: several keywords can follow one another
        var cursor = keywordIndex
        while (true) {
            val next = HeaderReader.keywordAt(header, cursor, TYPE_KEYWORDS)
            if (next == null) {
                break
            }
            cursor = HeaderReader.skipSpacesIn(header, cursor + next.length)
        }

        val nameIndex = HeaderReader.identifierStartAt(header, cursor)
        if (nameIndex >= 0) {
            return namedBlock(BlockKind.TYPE, keyword, header, headerStart, nameIndex, lineNumber)
        }

        // Go: in `type Point struct {` the name comes before the keyword
        val beforeIndex = HeaderReader.identifierStartBefore(header, keywordIndex)
        return namedBlock(BlockKind.TYPE, keyword, header, headerStart, beforeIndex, lineNumber)
    }

    /**
     * Everything that is not a type or a namespace, but still worth accenting, funnels through
     * here: an ordinary method, a lambda, a constructor or destructor, and (below) a property's
     * accessors and its own declaration. All of it shares [BlockKind.FUNCTION] and one toggle
     * ([ScanOptions.accentFunctions]) -- they differ only in [OpenBlock.keyword].
     */
    private fun classifyFunctionHeader(header: String, headerStart: Int, lineNumber: Int): OpenBlock {
        val trimmed = header.trimEnd()

        // check the lambda first: `return items.Select(x => {` is a lambda body,
        // even though the line starts with the word return
        if (trimmed.endsWith("=>") || HeaderReader.endsWithWord(trimmed, "delegate")) {
            return lambdaBlock(header, headerStart, lineNumber)
        }

        if (!trimmed.endsWith(")")) {
            // No parameter list, so this cannot be a method, constructor or destructor: either
            // a property accessor (`get`, `private set`) or the property declaration itself
            // (`public int Foo`) that wraps them.
            return classifyAccessorHeader(header, headerStart, lineNumber)
                ?: classifyPropertyHeader(header, headerStart, lineNumber)
                ?: otherBlock(lineNumber)
        }

        val destructorBlock = classifyDestructorHeader(header, headerStart, lineNumber)
        if (destructorBlock != null) {
            return destructorBlock
        }

        val firstWordIndex = HeaderReader.identifierStartAt(header, 0)
        if (firstWordIndex < 0) {
            return otherBlock(lineNumber)
        }
        val firstWord = HeaderReader.identifierAt(header, firstWordIndex)
        if (firstWord == "delegate") {
            // an anonymous method with a parameter list: `delegate(int x) {`
            return lambdaBlock(header, headerStart, lineNumber)
        }
        if (firstWord in NON_DECLARATION_KEYWORDS) {
            return otherBlock(lineNumber)
        }
        if (HeaderReader.containsAssignment(header)) {
            // `var a = new Foo() {` is an initializer, not a declaration
            return otherBlock(lineNumber)
        }

        val parenIndex = header.indexOf('(')
        if (parenIndex < 0) {
            return otherBlock(lineNumber)
        }

        val nameEnd = HeaderReader.skipGenericsBefore(header, parenIndex)
        val nameIndex = HeaderReader.identifierStartBefore(header, nameEnd)
        if (nameIndex < 0) {
            return otherBlock(lineNumber)
        }

        val modifiers = scanModifiers(header, 0)
        if (modifiers.endOffset == nameIndex) {
            // Nothing but modifiers before the name -- no return type, so this is a
            // constructor rather than an ordinary method.
            val keyword = if (modifiers.hasStatic) STATIC_CONSTRUCTOR_KEYWORD else CONSTRUCTOR_KEYWORD
            return namedBlock(BlockKind.FUNCTION, keyword, header, headerStart, nameIndex, lineNumber)
        }
        return namedBlock(BlockKind.FUNCTION, FUNCTION_KEYWORD, header, headerStart, nameIndex, lineNumber)
    }

    /**
     * `~Foo()`: a destructor has no return type and, in practice, no modifiers either. It is
     * recognised on its own because the ordinary path below rejects a leading `~` outright --
     * it is never the start of an identifier.
     */
    private fun classifyDestructorHeader(header: String, headerStart: Int, lineNumber: Int): OpenBlock? {
        val tildeIndex = HeaderReader.skipSpacesIn(header, 0)
        if (header.getOrNull(tildeIndex) != '~') {
            return null
        }
        val nameIndex = HeaderReader.identifierStartAt(header, tildeIndex + 1)
        if (nameIndex < 0) {
            return null
        }
        return namedBlock(BlockKind.FUNCTION, DESTRUCTOR_KEYWORD, header, headerStart, nameIndex, lineNumber)
    }

    /**
     * `get`, `set`, `init`, each optionally preceded by one access modifier (`private set`).
     * No name of its own -- it belongs to the enclosing property -- so, like a namespace, its
     * colour is sampled from the keyword itself rather than from a name; see
     * BraceAccentStyle.baseColor.
     */
    private fun classifyAccessorHeader(header: String, headerStart: Int, lineNumber: Int): OpenBlock? {
        val trimmedLength = header.trimEnd().length
        val wordIndex = HeaderReader.identifierStartBefore(header, trimmedLength)
        if (wordIndex < 0) {
            return null
        }
        val word = HeaderReader.identifierAt(header, wordIndex)
        if (wordIndex + word.length != trimmedLength || word !in ACCESSOR_KEYWORDS) {
            return null
        }

        val modifiers = scanModifiers(header, 0)
        if (modifiers.endOffset != wordIndex) {
            // anything besides modifiers before the accessor word means this is not one
            return null
        }
        return namedBlock(BlockKind.FUNCTION, word, header, headerStart, -1, lineNumber)
    }

    /**
     * `public int Foo`: a type and a name, nothing else -- no parameter list is what tells it
     * apart from a function, and that is already guaranteed by the caller. Kept under the same
     * "functions" umbrella as an ordinary method: same toggle, same label-length threshold,
     * same colour mechanism, just its own keyword ([PROPERTY_KEYWORD]).
     */
    private fun classifyPropertyHeader(header: String, headerStart: Int, lineNumber: Int): OpenBlock? {
        if (header.indexOf('(') >= 0 || HeaderReader.containsAssignment(header)) {
            // a parameter list or an assignment means this is not a bare "type name" header
            return null
        }
        if (HeaderReader.findKeyword(header, TYPE_KEYWORDS) >= 0 ||
            HeaderReader.findKeyword(header, NAMESPACE_KEYWORDS) >= 0
        ) {
            // `class Foo`/`namespace Foo` reaching here means accentTypes/accentNamespaces is
            // off; that must leave them invisible, not relabel them as a property.
            return null
        }

        val firstWordIndex = HeaderReader.identifierStartAt(header, 0)
        if (firstWordIndex < 0) {
            return null
        }
        val firstWord = HeaderReader.identifierAt(header, firstWordIndex)
        if (firstWord in NON_DECLARATION_KEYWORDS) {
            return null
        }

        val trimmedLength = header.trimEnd().length
        val nameIndex = HeaderReader.identifierStartBefore(header, trimmedLength)
        if (nameIndex < 0 || nameIndex + HeaderReader.identifierAt(header, nameIndex).length != trimmedLength) {
            return null
        }

        val modifiers = scanModifiers(header, 0)
        if (modifiers.endOffset >= nameIndex || !isTypeLikeSpan(header, modifiers.endOffset, nameIndex)) {
            // nothing, or nothing type-shaped, sits between the modifiers and the name
            return null
        }

        return namedBlock(BlockKind.FUNCTION, PROPERTY_KEYWORD, header, headerStart, nameIndex, lineNumber)
    }

    /**
     * Walks past any of [MODIFIER_KEYWORDS] from [from], noting whether `static` was among
     * them. What is left afterwards is either just a name (a constructor, or an accessor word)
     * or a type token followed by a name (an ordinary method, or a property) -- see the callers,
     * which tell those two shapes apart by comparing [ModifierScan.endOffset] to where the name
     * itself starts.
     */
    private fun scanModifiers(header: String, from: Int): ModifierScan {
        var cursor = from
        var hasStatic = false
        while (true) {
            val wordIndex = HeaderReader.identifierStartAt(header, cursor)
            if (wordIndex < 0) {
                break
            }
            val word = HeaderReader.identifierAt(header, wordIndex)
            if (word !in MODIFIER_KEYWORDS) {
                break
            }
            if (word == "static") {
                hasStatic = true
            }
            cursor = wordIndex + word.length
        }
        return ModifierScan(HeaderReader.skipSpacesIn(header, cursor), hasStatic)
    }

    private class ModifierScan(val endOffset: Int, val hasStatic: Boolean)

    /**
     * Whether `header[start until end]` could plausibly be a type reference: identifier
     * characters, generics, arrays, qualified names, tuple commas, and whitespace -- nothing
     * else. Guards [classifyPropertyHeader] against a two-word header that happens to end in an
     * identifier without actually being a declaration.
     */
    private fun isTypeLikeSpan(header: String, start: Int, end: Int): Boolean {
        if (start >= end) {
            return false
        }
        for (index in start until end) {
            val character = header[index]
            val isTypeCharacter = character.isLetterOrDigit() || character == '_' || character == '.' ||
                character == '<' || character == '>' || character == '[' || character == ']' ||
                character == ',' || character.isWhitespace()
            if (!isTypeCharacter) {
                return false
            }
        }
        return true
    }

    /**
     * A lambda or an anonymous method.
     *
     * They have no name of their own, so the nearest meaningful one is used: the assignment
     * target (`Action handler = () => {`) or the method the lambda is passed to
     * (`Run(() => {`). The colour comes from the same place.
     */
    private fun lambdaBlock(header: String, headerStart: Int, lineNumber: Int): OpenBlock {
        // The call the lambda is passed to comes first: in `var r = items.Select(y => {`
        // the meaningful name is Select, not the variable on the left.
        val openIndex = HeaderReader.lastUnclosedParen(header)
        if (openIndex >= 0) {
            val nameEnd = HeaderReader.skipGenericsBefore(header, openIndex)
            val nameIndex = HeaderReader.identifierStartBefore(header, nameEnd)
            if (nameIndex >= 0) {
                return namedBlock(
                    BlockKind.FUNCTION,
                    FUNCTION_KEYWORD,
                    header,
                    headerStart,
                    nameIndex,
                    lineNumber,
                    isLambda = true,
                )
            }
        }

        // No parentheses means the lambda is simply assigned: `Action handler = () => {`
        val assignIndex = HeaderReader.assignmentIndex(header)
        if (assignIndex >= 0) {
            val nameIndex = HeaderReader.identifierStartBefore(header, assignIndex)
            if (nameIndex >= 0) {
                return namedBlock(
                    BlockKind.FUNCTION,
                    FUNCTION_KEYWORD,
                    header,
                    headerStart,
                    nameIndex,
                    lineNumber,
                    isLambda = true,
                )
            }
        }
        return namedBlock(
            BlockKind.FUNCTION,
            FUNCTION_KEYWORD,
            header,
            headerStart,
            -1,
            lineNumber,
            isLambda = true,
        )
    }

    private fun namedBlock(
        kind: BlockKind,
        keyword: String,
        header: String,
        headerStart: Int,
        nameIndex: Int,
        lineNumber: Int,
        isLambda: Boolean = false,
    ): OpenBlock {
        if (nameIndex < 0) {
            return OpenBlock(
                kind,
                -1,
                0,
                keyword,
                lineNumber,
                isLambda = isLambda,
                headerOffset = headerStart,
            )
        }
        val name = HeaderReader.identifierAt(header, nameIndex)
        return OpenBlock(
            kind,
            headerStart + nameIndex,
            name.length,
            keyword,
            lineNumber,
            isLambda = isLambda,
            headerOffset = headerStart,
        )
    }

    fun otherBlock(lineNumber: Int): OpenBlock {
        return OpenBlock(BlockKind.OTHER, -1, 0, "", lineNumber)
    }

    private companion object {
        /** What the label says for an ordinary method: no return type, just `fun Name`. */
        const val FUNCTION_KEYWORD = "fun"

        /** A constructor: same name as its type, but no return type precedes it. */
        const val CONSTRUCTOR_KEYWORD = "ctor"

        /** `static Foo() { }`: a constructor whose modifiers include `static`. */
        const val STATIC_CONSTRUCTOR_KEYWORD = "static ctor"

        /** `~Foo() { }`. */
        const val DESTRUCTOR_KEYWORD = "dtor"

        /** The property declaration itself, wrapping its accessors. */
        const val PROPERTY_KEYWORD = "prop"

        /** What a namespace's label prints, standing in for the keyword a type or function has. */
        const val NAMESPACE_LABEL = "ns"

        val TYPE_KEYWORDS = setOf("class", "struct", "interface", "enum", "record")

        val NAMESPACE_KEYWORDS = setOf("namespace")

        /** A property accessor's own bare keyword; there is one block kind per word. */
        val ACCESSOR_KEYWORDS = setOf("get", "set", "init")

        /**
         * Consumed on sight before a constructor's, a property's or an accessor's own name --
         * never mistaken for a return type or the name itself. `new` (member hiding) is left
         * out on purpose: a header that starts with it is already rejected by
         * [NON_DECLARATION_KEYWORDS] before [scanModifiers] ever runs, and elsewhere it can
         * only appear where the ordinary "there is a token before the name" case already
         * classifies things correctly as a function.
         */
        val MODIFIER_KEYWORDS = setOf(
            "public", "private", "protected", "internal", "static", "sealed",
            "abstract", "virtual", "override", "async", "unsafe", "extern",
            "partial", "readonly",
        )

        /**
         * These words start anything but a function declaration.
         * Without them `return new Foo() {` and `switch (x) {` would count as functions.
         */
        val NON_DECLARATION_KEYWORDS = setOf(
            "if", "for", "foreach", "while", "switch", "using", "lock", "fixed",
            "catch", "do", "else", "try", "finally", "return", "throw", "yield",
            "await", "new", "unsafe", "checked", "unchecked",
        )
    }
}
