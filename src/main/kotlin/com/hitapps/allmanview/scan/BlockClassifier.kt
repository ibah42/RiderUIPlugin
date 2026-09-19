package com.hitapps.allmanview.scan

/** An open block on the nesting stack. */
internal class OpenBlock(
    val kind: BlockKind,
    val nameOffset: Int,
    val nameLength: Int,
    val keyword: String,
    val openLineNumber: Int,
    val isLambda: Boolean = false,

    /** See [BraceAccent.isAccessor]. */
    val isAccessor: Boolean = false,

    /** Where the declaration starts, which for a multi-line signature is not the brace line. */
    val headerOffset: Int = -1,
) {
    /**
     * The line the declaration starts on -- the line carrying the name -- as opposed to
     * [openLineNumber], which is the line the `{` is on. They differ by one in a source already
     * written in Allman style, and by more when the signature spans lines.
     *
     * Attributes on their own lines above the declaration are deliberately not included: the
     * scanner remembers one previous line of code, and walking further up would mean deciding
     * what else belongs -- doc comments, blank lines, `#if` -- for a marker that is cosmetic.
     *
     * Defaults to [openLineNumber] for a block that never reached [BlockClassifier.classify].
     */
    var headerLineNumber: Int = openLineNumber

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
internal class BlockClassifier(
    private val options: ScanOptions,
    flavor: Flavor = Flavor.GENERIC,
) {

    /**
     * Set for the languages that lead with the kind of the declaration; null for the C family,
     * whose shape-led grammar everything else in this file is about. See [KeywordLedClassifier].
     */
    /**
     * Objective-C rides on the C++ dialect, whose literals are already right for it, and only
     * needs its two shapes that C++ has none of: the `- (void)doThing:(int)x` method header and
     * the `^{ }` block literal. Neither is valid C or C++, so recognising them in that dialect
     * costs the C and C++ files sharing it nothing.
     */
    private val supportsObjectiveCShapes = flavor == Flavor.C_FAMILY

    private val keywordLed: KeywordLedClassifier? = when (flavor) {
        Flavor.SWIFT -> KeywordLedClassifier(KeywordLedClassifier.SWIFT)
        Flavor.RUST -> KeywordLedClassifier(KeywordLedClassifier.RUST)
        else -> null
    }


    /**
     * Order matters: a namespace header matches nothing else, so it goes first; a function is
     * last because it is the fallback that also recognises lambdas, constructors, destructors,
     * property accessors and the property declaration itself.
     */
    fun classify(
        rawHeader: String,
        headerStart: Int,
        lineNumber: Int,
        headerLineNumber: Int,
    ): OpenBlock {
        val block = classifyHeader(rawHeader, headerStart, lineNumber)
        block.headerLineNumber = headerLineNumber
        return block
    }

    /** The classification itself; [classify] only records where the declaration began. */
    private fun classifyHeader(rawHeader: String, headerStart: Int, lineNumber: Int): OpenBlock {
        val header = HeaderReader.declarationPart(rawHeader)

        if (keywordLed != null) {
            val block = keywordLed.classify(header, headerStart, lineNumber)
            if (block == null || !wantsKind(block)) {
                return otherBlock(lineNumber)
            }
            return block
        }

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
            // Unlike the namespace/type search above, none of this does a free keyword search,
            // so a string literal that legitimately belongs to the declaration -- a default
            // parameter value such as `string reason = ""` -- must survive intact here.
            val functionHeader = HeaderReader.declarationPartKeepingLiterals(rawHeader)
            val functionBlock = classifyObjectiveCHeader(functionHeader, headerStart, lineNumber)
                ?: classifyFunctionHeader(functionHeader, headerStart, lineNumber)
            if (wantsFunctionKind(functionBlock)) {
                return functionBlock
            }
        }
        return otherBlock(lineNumber)
    }

    /**
     * Whether the settings still want this particular kind of function block.
     *
     * Asked once, of the finished block, rather than inside each of the classifiers below: the
     * keyword is what tells the sub-kinds apart and it is only settled at the very end. A block
     * turned down here becomes an ordinary [otherBlock], so the plugin does not merely stop
     * labelling it -- it stops seeing it: no colour, no shadow, and no place in the nesting or
     * sibling bookkeeping either.
     */
    /**
     * The kind switches, asked of a finished block whatever classified it. The C-family path
     * below only ever needs the function half, since it consults [ScanOptions.accentTypes] and
     * friends before it starts; the keyword-led path produces every kind in one call and so
     * needs all of them here.
     */
    private fun wantsKind(block: OpenBlock): Boolean {
        if (block.kind == BlockKind.TYPE) {
            return options.accentTypes
        }
        if (block.kind == BlockKind.NAMESPACE) {
            return options.accentNamespaces
        }
        if (block.kind == BlockKind.FUNCTION && !options.accentFunctions) {
            return false
        }
        return wantsFunctionKind(block)
    }

    private fun wantsFunctionKind(block: OpenBlock): Boolean {
        if (block.kind != BlockKind.FUNCTION) {
            return true
        }
        if (block.isLambda) {
            return options.accentLambdas
        }
        if (block.keyword in CONSTRUCTOR_KEYWORDS) {
            return options.accentConstructors
        }
        if (block.keyword == PROPERTY_KEYWORD) {
            return options.accentProperties
        }
        // An operator falls through to the methods switch below: it carries its own label
        // ([OPERATOR_KEYWORD] plus the operator itself), but it is a method in every other
        // respect and is turned on and off with one.
        if (block.keyword in ACCESSOR_KEYWORDS) {
            return options.accentAccessors
        }
        return options.accentMethods
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
        // A type's own keyword always stands in front of any parameter list the header has --
        // a positional record's `(int X, int Y)`, or a primary constructor's. One found INSIDE
        // a parameter list is something else entirely: `record` is contextual in C#, so
        // `void Save(Record record)` is an ordinary method with an ordinarily named parameter,
        // and searching the whole header made it a type declaration called `Record`.
        val parameterList = HeaderReader.parameterListStart(header)
        val searchable: String
        if (parameterList < 0) {
            searchable = header
        } else {
            searchable = header.substring(0, parameterList)
        }

        // Indices into `searchable` are indices into `header`: it is a prefix of it, so
        // everything below goes on reading the whole header from here.
        val keywordIndex = HeaderReader.findKeyword(searchable, TYPE_KEYWORDS)
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

        val parenIndex = HeaderReader.parameterListStart(header)
        if (parenIndex < 0) {
            return otherBlock(lineNumber)
        }

        // Before the assignment test below, not after: `operator +=` and `operator ==` both put
        // an `=` in front of the parameter list without being assignments to anything.
        val operatorBlock = classifyOperatorHeader(header, headerStart, lineNumber, parenIndex)
        if (operatorBlock != null) {
            return operatorBlock
        }

        if (declaresATypeOrNamespace(header, parenIndex)) {
            // `public record Point(int X)` and `public class Node(int id)` -- a positional
            // record and a primary constructor both end in a parameter list, so unlike a plain
            // `class Foo` they reach this far. Getting here at all means accentTypes is off (a
            // type header is claimed above otherwise), and off has to mean invisible, not
            // relabelled `fun Point`. The same rule [classifyPropertyHeader] already applies to
            // the other fallback.
            return otherBlock(lineNumber)
        }

        if (HeaderReader.containsAssignment(header.substring(0, parenIndex))) {
            // `var a = new Foo() {` is an initializer, not a declaration. Only the part before
            // the parameter list is checked: `void Foo(int x = 5) {` has an `=` too, and that
            // one is a default parameter value, not an assignment the declaration is a target of.
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
     * Whether a type or namespace keyword stands in front of the parameter list.
     *
     * Only the part before the list is searched, which is what keeps an ordinary method safe:
     * `void Foo(int record)` names a parameter with a contextual keyword and is none of this
     * function's business, while `public record Point(int X)` puts the word where a return type
     * would go. [HeaderReader.findKeyword] matches whole words only, so `void RecordEvent()`
     * does not count either.
     */
    private fun declaresATypeOrNamespace(header: String, parenIndex: Int): Boolean {
        val beforeParameters = header.substring(0, parenIndex)
        return HeaderReader.findKeyword(beforeParameters, TYPE_KEYWORDS) >= 0 ||
            HeaderReader.findKeyword(beforeParameters, NAMESPACE_KEYWORDS) >= 0
    }

    /**
     * `public static Foo operator +(Foo a, Foo b)` and
     * `public static implicit operator int(Foo a)`.
     *
     * Both were invisible before: what stands in front of the parameter list is `+`, or a type
     * keyword like `int`, and the ordinary path looks for an identifier there -- it finds none
     * in the first case and, in the second, reads the conversion's target type as if it were the
     * method's name (`fun int`).
     *
     * The name is whatever the source writes between `operator` and the parameter list, verbatim
     * and without being required to be an identifier: `op +`, `op ==`, `op int`. That is the
     * only thing that tells two operators of the same type apart, and it is also where the
     * colour is sampled, so the label matches what the editor paints there.
     */
    private fun classifyOperatorHeader(
        header: String,
        headerStart: Int,
        lineNumber: Int,
        parenIndex: Int,
    ): OpenBlock? {
        val keywordIndex = HeaderReader.findKeyword(header.substring(0, parenIndex), OPERATOR_KEYWORDS)
        if (keywordIndex < 0) {
            return null
        }

        val nameStart = HeaderReader.skipSpacesIn(header, keywordIndex + OPERATOR_WORD.length)
        var nameEnd = parenIndex
        while (nameEnd > nameStart && header[nameEnd - 1].isWhitespace()) {
            nameEnd--
        }
        if (nameStart >= nameEnd) {
            // `operator(` with nothing between the two: not a declaration this understands
            return null
        }

        return spannedBlock(
            BlockKind.FUNCTION,
            OPERATOR_KEYWORD,
            headerStart,
            nameStart,
            nameEnd - nameStart,
            lineNumber,
        )
    }

    /**
     * Objective-C's own two shapes, or null when the header is neither.
     *
     *  - `- (void)doThing:(int)x` and `+ (instancetype)shared`: the sign and the parenthesised
     *    return type come first, and the name is the first piece of the selector after them.
     *  - `^{` and `^(NSInteger index) {`: a block literal, which has no name of its own and so
     *    borrows the argument it is being passed as, exactly as a lambda does elsewhere --
     *    `animateWithDuration:0.3 animations:^{` reads as `animations`.
     */
    private fun classifyObjectiveCHeader(
        header: String,
        headerStart: Int,
        lineNumber: Int,
    ): OpenBlock? {
        if (!supportsObjectiveCShapes) {
            return null
        }
        val blockLiteral = classifyObjectiveCBlockLiteral(header, headerStart, lineNumber)
        if (blockLiteral != null) {
            return blockLiteral
        }

        val signIndex = HeaderReader.skipSpacesIn(header, 0)
        if (signIndex >= header.length) {
            return null
        }
        val sign = header[signIndex]
        if (sign != '-' && sign != '+') {
            return null
        }
        val typeIndex = HeaderReader.skipSpacesIn(header, signIndex + 1)
        if (typeIndex >= header.length || header[typeIndex] != '(') {
            return null
        }
        val afterType = HeaderReader.skipBalancedParens(header, typeIndex)
        val nameIndex = HeaderReader.identifierStartAt(header, HeaderReader.skipSpacesIn(header, afterType))
        if (nameIndex < 0) {
            return null
        }
        return namedBlock(
            BlockKind.FUNCTION,
            FUNCTION_KEYWORD,
            header,
            headerStart,
            nameIndex,
            lineNumber,
        )
    }

    /** The `^` of a block literal, and the last identifier before it to name the block after. */
    private fun classifyObjectiveCBlockLiteral(
        header: String,
        headerStart: Int,
        lineNumber: Int,
    ): OpenBlock? {
        val trimmed = header.trimEnd()
        if (trimmed.isEmpty()) {
            return null
        }

        var caretIndex = trimmed.length - 1
        if (trimmed[caretIndex] == ')') {
            // `^(NSInteger index)`: rewind over the parameter list to the caret in front of it.
            val open = HeaderReader.lastUnclosedParen(trimmed.substring(0, caretIndex))
            if (open < 0) {
                return null
            }
            caretIndex = open - 1
        }
        if (caretIndex < 0 || trimmed[caretIndex] != '^') {
            return null
        }

        val nameIndex = HeaderReader.lastIdentifierStartBefore(trimmed, caretIndex)
        return namedBlock(
            BlockKind.FUNCTION,
            FUNCTION_KEYWORD,
            trimmed,
            headerStart,
            nameIndex,
            lineNumber,
            isLambda = true,
        )
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
        return namedBlock(
            BlockKind.FUNCTION,
            word,
            header,
            headerStart,
            -1,
            lineNumber,
            isAccessor = true,
        )
    }

    /**
     * `public int Foo`: a type and a name, nothing else -- no parameter list is what tells it
     * apart from a function, and that is already guaranteed by the caller. Kept under the same
     * "functions" umbrella as an ordinary method: same toggle, same label-length threshold,
     * same colour mechanism, just its own keyword ([PROPERTY_KEYWORD]).
     */
    private fun classifyPropertyHeader(header: String, headerStart: Int, lineNumber: Int): OpenBlock? {
        // An indexer is checked before anything else here: its bracketed parameter list may
        // hold a default value, whose `=` the assignment test below would read as an
        // initializer, and it ends with `]` rather than with its own name.
        val indexerIndex = indexerNameIndex(header)
        if (indexerIndex >= 0) {
            return namedBlock(
                BlockKind.FUNCTION,
                PROPERTY_KEYWORD,
                header,
                headerStart,
                indexerIndex,
                lineNumber,
            )
        }

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
     * Where an indexer's `this` keyword starts, or -1 when this header declares no indexer.
     *
     * `public int this[int i]` and `int IFoo.this[int i]` both count. An indexer is a property
     * that takes arguments, so it is labelled as one -- and `this` is its name, exactly as the
     * source writes it, which is also what the brace colour is then sampled from.
     *
     * The bracket alone is not enough to go on: an attribute line (`[Test]`) and an array
     * initializer (`var x = new[]`) both end with `]` too, which is why the word immediately
     * before the bracket has to be `this`.
     */
    private fun indexerNameIndex(header: String): Int {
        val trimmed = header.trimEnd()
        if (!trimmed.endsWith("]")) {
            return -1
        }

        val bracketIndex = trimmed.indexOf('[')
        if (bracketIndex < 0) {
            return -1
        }

        val nameIndex = HeaderReader.identifierStartBefore(trimmed, bracketIndex)
        if (nameIndex < 0) {
            return -1
        }
        if (HeaderReader.identifierAt(trimmed, nameIndex) != INDEXER_KEYWORD) {
            return -1
        }
        return nameIndex
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
        isAccessor: Boolean = false,
    ): OpenBlock {
        if (nameIndex < 0) {
            return OpenBlock(
                kind,
                -1,
                0,
                keyword,
                lineNumber,
                isLambda = isLambda,
                isAccessor = isAccessor,
                headerOffset = headerStart,
            )
        }
        val name = HeaderReader.identifierAt(header, nameIndex)
        return spannedBlock(kind, keyword, headerStart, nameIndex, name.length, lineNumber, isLambda)
    }

    /**
     * A block whose name is an explicit span of the header rather than an identifier read from
     * it. [namedBlock] covers everything whose name is a plain identifier; this is for the one
     * thing that is not -- an operator's `+`, `==`, `int`.
     */
    private fun spannedBlock(
        kind: BlockKind,
        keyword: String,
        headerStart: Int,
        nameIndex: Int,
        nameLength: Int,
        lineNumber: Int,
        isLambda: Boolean = false,
    ): OpenBlock {
        return OpenBlock(
            kind,
            headerStart + nameIndex,
            nameLength,
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

        /** An indexer's own name, as the source writes it: `public int this[int i]`. */
        const val INDEXER_KEYWORD = "this"

        /** The word the source spells it with, and the set [HeaderReader.findKeyword] wants. */
        const val OPERATOR_WORD = "operator"
        val OPERATOR_KEYWORDS = setOf(OPERATOR_WORD)

        /** What a namespace's label prints, standing in for the keyword a type or function has. */
        const val NAMESPACE_LABEL = "ns"

        val TYPE_KEYWORDS = setOf("class", "struct", "interface", "enum", "record")

        val NAMESPACE_KEYWORDS = setOf("namespace")

        /** A property accessor's own bare keyword; there is one block kind per word. */
        // `willSet` and `didSet` are Swift's; they can only ever reach here from the
        // keyword-led classifier, so listing them costs the C# path nothing.
        val ACCESSOR_KEYWORDS = setOf("get", "set", "init", "willSet", "didSet")

        /** Everything the "constructors" switch covers: they are one concept to a reader. */
        val CONSTRUCTOR_KEYWORDS = setOf(
            CONSTRUCTOR_KEYWORD, STATIC_CONSTRUCTOR_KEYWORD, DESTRUCTOR_KEYWORD,
        )

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
