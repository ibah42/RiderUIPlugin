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
     * last because it is the fallback that also recognises lambdas.
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

    private fun classifyFunctionHeader(header: String, headerStart: Int, lineNumber: Int): OpenBlock {
        val trimmed = header.trimEnd()

        // check the lambda first: `return items.Select(x => {` is a lambda body,
        // even though the line starts with the word return
        if (trimmed.endsWith("=>") || HeaderReader.endsWithWord(trimmed, "delegate")) {
            return lambdaBlock(header, headerStart, lineNumber)
        }
        if (!trimmed.endsWith(")")) {
            return otherBlock(lineNumber)
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
        return namedBlock(BlockKind.FUNCTION, FUNCTION_KEYWORD, header, headerStart, nameIndex, lineNumber)
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
        /** What the label says for functions: no return type, just `fun Name`. */
        const val FUNCTION_KEYWORD = "fun"

        /** What a namespace's label prints, standing in for the keyword a type or function has. */
        const val NAMESPACE_LABEL = "ns"

        val TYPE_KEYWORDS = setOf("class", "struct", "interface", "enum", "record")

        val NAMESPACE_KEYWORDS = setOf("namespace")

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
