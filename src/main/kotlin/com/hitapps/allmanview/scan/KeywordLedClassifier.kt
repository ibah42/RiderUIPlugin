package com.hitapps.allmanview.scan

/**
 * The words one keyword-led language declares its blocks with.
 *
 * Swift and Rust put the kind of the thing first -- `func`, `struct`, `impl`, `mod` -- where
 * the C family writes the return type first and leaves the kind implied by the shape
 * (`void Foo(int x)`). That is a different grammar, not a dialect of the same one, which is why
 * these languages get a classifier of their own instead of more special cases inside
 * [BlockClassifier]: nothing here has to care about constructors named after their type, about
 * `~` destructors, or about telling a property apart from a field.
 */
internal class LanguageWords(
    /** Words that open a type block, used verbatim as the label: `struct`, `protocol`. */
    val types: Set<String>,

    /** Words that open a namespace-like block; [namespaceLabel] is what its label prints. */
    val namespaces: Set<String>,
    val namespaceLabel: String,

    /**
     * Function-like words mapped to the label they print. The label is the concept, not the
     * word, so Swift's `init` prints `ctor` exactly as a C# constructor does -- a reader who
     * knows the plugin reads the same label in every language.
     */
    val functions: Map<String, String>,

    /** Words that open an accessor body inside a property. */
    val accessors: Set<String>,

    /** Words that declare a property whose body is a block: Swift's `var` and `let`. */
    val properties: Set<String>,

    /** Consumed before the declaring word, never mistaken for it. */
    val modifiers: Set<String>,

    /** Words that start a statement, never a declaration: `if`, `match`, `guard`. */
    val nonDeclarations: Set<String>,

    /** Rust's `impl Display for Point`: the name worth showing is the one after `for`. */
    val implementationForWord: String?,

    /** Whether a call followed by a brace is a closure body worth borrowing a name from. */
    val hasTrailingClosures: Boolean,
)

/**
 * Classifies a block header in a language that leads with the kind of the declaration.
 *
 * Returns null for anything it does not recognise, leaving the caller to record an ordinary
 * block: guessing is worse than saying nothing, because a wrong label is read as a fact.
 */
internal class KeywordLedClassifier(private val words: LanguageWords) {

    fun classify(header: String, headerStart: Int, lineNumber: Int): OpenBlock? {
        val trimmed = header.trimEnd()
        if (trimmed.isEmpty()) {
            return null
        }

        val wordIndex = skipModifiers(trimmed, 0)
        if (wordIndex < 0) {
            return null
        }
        val word = HeaderReader.identifierAt(trimmed, wordIndex)
        if (word.isEmpty() || word in words.nonDeclarations) {
            return null
        }

        val afterWord = wordIndex + word.length

        if (word in words.namespaces) {
            // Like a C# namespace, the name is left off: it is long, it repeats down the whole
            // file, and the closing brace gains nothing by carrying it.
            return OpenBlock(
                BlockKind.NAMESPACE,
                -1,
                0,
                words.namespaceLabel,
                lineNumber,
                headerOffset = headerStart,
            )
        }
        if (word in words.types) {
            return typeBlock(trimmed, headerStart, lineNumber, word, afterWord)
        }
        val functionLabel = words.functions[word]
        if (functionLabel != null) {
            return functionBlock(trimmed, headerStart, lineNumber, word, functionLabel, afterWord)
        }
        if (word in words.accessors) {
            // An accessor is the whole header: `get`, `set`, `didSet`. Anything after it means
            // this is something else that merely starts with the same word.
            if (afterWord != trimmed.length) {
                return null
            }
            return OpenBlock(
                BlockKind.FUNCTION,
                -1,
                0,
                word,
                lineNumber,
                isAccessor = true,
                headerOffset = headerStart,
            )
        }
        if (word in words.properties) {
            return propertyBlock(trimmed, headerStart, lineNumber, afterWord)
        }
        return closureBlock(trimmed, headerStart, lineNumber)
    }

    /**
     * `struct Point`, `protocol Fetching`, `extension URLSession: Fetching`, `impl<'a> Client<'a>`,
     * `impl Display for Point`.
     */
    private fun typeBlock(
        header: String,
        headerStart: Int,
        lineNumber: Int,
        word: String,
        afterWord: Int,
    ): OpenBlock {
        val forWord = words.implementationForWord
        if (forWord != null) {
            val forIndex = separateWordIndex(header, forWord, afterWord)
            if (forIndex >= 0) {
                // `impl Display for Point` is a block about Point, not about Display.
                return named(header, headerStart, lineNumber, word, nameIndexAt(header, forIndex + forWord.length))
            }
        }
        return named(header, headerStart, lineNumber, word, nameIndexAt(header, afterWord))
    }

    /** `func get() -> String`, `init(name: String)`, `deinit`, `fn longest<'a>(...) -> &'a str`. */
    private fun functionBlock(
        header: String,
        headerStart: Int,
        lineNumber: Int,
        word: String,
        label: String,
        afterWord: Int,
    ): OpenBlock {
        val nameIndex = nameIndexAt(header, afterWord)
        if (nameIndex < 0) {
            // `init`, `deinit`, `subscript`: the concept is the whole name. The label alone says
            // it, so nothing is recorded for the name rather than repeating the word twice.
            return OpenBlock(BlockKind.FUNCTION, -1, 0, label, lineNumber, headerOffset = headerStart)
        }
        return named(header, headerStart, lineNumber, label, nameIndex)
    }

    /**
     * Swift's `var name: String {` and `let name: String {`.
     *
     * Only a declaration whose body really is a block: `var x = 1` never reaches here, since a
     * header is only classified when a brace follows it.
     */
    private fun propertyBlock(
        header: String,
        headerStart: Int,
        lineNumber: Int,
        afterWord: Int,
    ): OpenBlock? {
        val nameIndex = nameIndexAt(header, afterWord)
        if (nameIndex < 0) {
            return null
        }
        if (HeaderReader.containsAssignment(header)) {
            // `let handler: () -> Void = { ... }` is a closure held in a property, not a
            // property with a body: the brace belongs to the value. It still takes the
            // property's name rather than the generic closure rule's, which would reach for
            // the last identifier in the header and come back with the return type.
            return named(header, headerStart, lineNumber, FUNCTION_LABEL, nameIndex, isLambda = true)
        }
        return named(header, headerStart, lineNumber, PROPERTY_KEYWORD, nameIndex)
    }

    /**
     * A trailing closure: `DispatchQueue.main.async {`, `items.map {`, `sink {`.
     *
     * Named after the call it is passed to, exactly as a C# lambda is -- a closure has no name
     * of its own, and the call is what a reader recognises. Recognised only when the header
     * really looks like a call, so that a statement this classifier simply does not know stays
     * unlabelled instead of being guessed at.
     */
    private fun closureBlock(header: String, headerStart: Int, lineNumber: Int): OpenBlock? {
        if (!words.hasTrailingClosures) {
            return null
        }
        if (header.indexOf('(') < 0 && header.indexOf('.') < 0) {
            return null
        }
        val nameIndex = lastIdentifierIndex(header)
        if (nameIndex < 0) {
            return null
        }
        return named(header, headerStart, lineNumber, FUNCTION_LABEL, nameIndex, isLambda = true)
    }

    private fun named(
        header: String,
        headerStart: Int,
        lineNumber: Int,
        keyword: String,
        nameIndex: Int,
        isLambda: Boolean = false,
    ): OpenBlock {
        if (nameIndex < 0) {
            return OpenBlock(
                BlockKind.FUNCTION.takeIf { isLambda } ?: BlockKind.TYPE,
                -1,
                0,
                keyword,
                lineNumber,
                isLambda = isLambda,
                headerOffset = headerStart,
            )
        }
        val name = HeaderReader.identifierAt(header, nameIndex)
        val kind = if (keyword in words.types) BlockKind.TYPE else BlockKind.FUNCTION
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

    /**
     * Steps over leading modifiers and returns where the declaring word starts.
     *
     * A modifier is only consumed when something follows it, which is what keeps Rust's
     * `unsafe {` -- a block in its own right -- from being read as the modifier in `unsafe fn`.
     * A modifier may carry a parenthesised argument (`pub(crate)`, `@available(iOS 15, *)`),
     * and that group is stepped over with it.
     */
    private fun skipModifiers(header: String, from: Int): Int {
        var cursor = HeaderReader.skipSpacesIn(header, from)

        while (cursor < header.length) {
            if (header[cursor] == '@') {
                // A Swift attribute: `@MainActor`, `@objc`, `@available(iOS 15, *)`.
                cursor = skipAttribute(header, cursor)
                continue
            }
            val start = HeaderReader.identifierStartAt(header, cursor)
            if (start != cursor) {
                return if (start < 0) -1 else start
            }
            val word = HeaderReader.identifierAt(header, cursor)
            if (word.isEmpty() || word !in words.modifiers) {
                return cursor
            }
            if (declaresABlock(word) && !declaresABlock(wordAfter(header, cursor + word.length))) {
                // Swift's `class` is both: a modifier in `class func reset()` and the
                // declaration itself in `final class Client`. Only the first of those has
                // another declaring word behind it, which is what tells them apart.
                return cursor
            }
            var next = cursor + word.length
            if (next < header.length && header[next] == '(') {
                next = HeaderReader.skipBalancedParens(header, next)
            }
            val afterSpaces = HeaderReader.skipSpacesIn(header, next)
            if (afterSpaces >= header.length) {
                // Nothing follows, so the word was not a modifier after all -- it is the header.
                return cursor
            }
            cursor = afterSpaces
        }
        return -1
    }

    /** Whether this word opens a block of its own, rather than qualifying one that follows. */
    private fun declaresABlock(word: String): Boolean {
        return word in words.types ||
            word in words.namespaces ||
            word in words.functions ||
            word in words.accessors ||
            word in words.properties
    }

    /** The next word after [from], or an empty string when the header ends there. */
    private fun wordAfter(header: String, from: Int): String {
        val start = HeaderReader.identifierStartAt(header, HeaderReader.skipSpacesIn(header, from))
        if (start < 0) {
            return ""
        }
        return HeaderReader.identifierAt(header, start)
    }

    private fun skipAttribute(header: String, from: Int): Int {
        var cursor = from + 1
        while (cursor < header.length && HeaderReader.isIdentifierChar(header[cursor])) {
            cursor++
        }
        if (cursor < header.length && header[cursor] == '(') {
            cursor = HeaderReader.skipBalancedParens(header, cursor)
        }
        return HeaderReader.skipSpacesIn(header, cursor)
    }

    /** The identifier after [from], stepping over spaces and a generic parameter list. */
    private fun nameIndexAt(header: String, from: Int): Int {
        var cursor = HeaderReader.skipSpacesIn(header, from)
        if (cursor < header.length && header[cursor] == '<') {
            cursor = HeaderReader.skipSpacesIn(header, skipBalancedAngles(header, cursor))
        }
        if (cursor >= header.length || !HeaderReader.isIdentifierStart(header[cursor])) {
            return -1
        }
        return cursor
    }

    private fun skipBalancedAngles(header: String, from: Int): Int {
        var depth = 0
        var cursor = from

        while (cursor < header.length) {
            if (header[cursor] == '<') {
                depth++
            }
            if (header[cursor] == '>') {
                depth--
                if (depth == 0) {
                    return cursor + 1
                }
            }
            cursor++
        }
        return header.length
    }

    /** Index of [word] where it stands alone, at or after [from]; -1 if it does not. */
    private fun separateWordIndex(header: String, word: String, from: Int): Int {
        var cursor = from

        while (cursor < header.length) {
            val index = header.indexOf(word, cursor)
            if (index < 0) {
                return -1
            }
            val before = header.getOrNull(index - 1)
            val after = header.getOrNull(index + word.length)
            val standsAlone = (before == null || !HeaderReader.isIdentifierChar(before)) &&
                (after == null || !HeaderReader.isIdentifierChar(after))
            if (standsAlone) {
                return index
            }
            cursor = index + word.length
        }
        return -1
    }

    /** The last identifier in the header, which for a call chain is the method being called. */
    private fun lastIdentifierIndex(header: String): Int {
        return HeaderReader.lastIdentifierStartBefore(header, header.trimEnd().length)
    }

    companion object {
        /** The label every plain function prints, whatever the source word for it is. */
        const val FUNCTION_LABEL = "fun"

        val SWIFT = LanguageWords(
            types = setOf("class", "struct", "enum", "protocol", "extension", "actor"),
            namespaces = emptySet(),
            namespaceLabel = "",
            functions = mapOf(
                "func" to FUNCTION_LABEL,
                "init" to "ctor",
                "deinit" to "dtor",
                "subscript" to PROPERTY_KEYWORD,
            ),
            accessors = setOf("get", "set", "willSet", "didSet"),
            properties = setOf("var", "let"),
            modifiers = setOf(
                "public", "private", "fileprivate", "internal", "open", "static", "final",
                "override", "required", "convenience", "mutating", "nonmutating", "lazy",
                "weak", "unowned", "indirect", "dynamic", "class", "nonisolated", "package",
            ),
            nonDeclarations = setOf(
                "if", "else", "guard", "for", "while", "repeat", "switch", "case", "default",
                "do", "catch", "defer", "return", "throw", "in", "where",
            ),
            implementationForWord = null,
            hasTrailingClosures = true,
        )

        val RUST = LanguageWords(
            types = setOf("struct", "enum", "union", "trait", "impl"),
            namespaces = setOf("mod"),
            namespaceLabel = "mod",
            functions = mapOf("fn" to FUNCTION_LABEL),
            accessors = emptySet(),
            properties = emptySet(),
            modifiers = setOf("pub", "async", "unsafe", "const", "extern", "default", "crate"),
            nonDeclarations = setOf(
                "if", "else", "for", "while", "loop", "match", "return", "move", "where",
            ),
            implementationForWord = "for",
            hasTrailingClosures = false,
        )
    }
}
