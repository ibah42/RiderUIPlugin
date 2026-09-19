package com.hitapps.allmanview.scan

/**
 * Which set of block-span settings a block answers to. Coarser than the accent switches on
 * purpose: the span marker asks "is this block long", and properties are the only sub-kind
 * whose sense of "long" differs from a method's by enough to be worth its own number.
 */
enum class BlockSpanGroup {
    TYPE,
    FUNCTION,
    PROPERTY,
    NAMESPACE,
}

/** How the block-span marker behaves for one [BlockSpanGroup]; null everywhere means "off". */
data class BlockSpanConfig(
    val minLines: Int,

    /** Stay quiet unless the file holds more than one block of this kind. */
    val onlyWithSeveral: Boolean,
)

/**
 * Which markers a brace draws, and what they say.
 *
 * Every rule here used to live in BraceAccentStyle, where it was welded to an `Editor` and to
 * the settings component and could therefore only be checked by opening a file and looking at
 * it. Everything in this file is a pure function of a [BraceAccent], a couple of numbers and a
 * couple of switches -- so "why did a 112-line class in a 15-class file not print its span"
 * is a unit test rather than a screenshot. BraceAccentStyle keeps the colours, which genuinely
 * do need the editor, and asks this for the decisions.
 */
object LabelPolicy {

    /**
     * Whether a block reports its own span at the closing brace: its group is switched on
     * ([config] non-null), any restriction that group carries is satisfied, and the block is at
     * least that group's length. End-only -- the declaration line has nothing to say, since
     * standing on it you can see where the block starts.
     */
    fun showsBlockSpan(accent: BraceAccent, config: BlockSpanConfig?, counts: BlockCounts): Boolean {
        if (accent.isOpening) {
            return false
        }
        if (config == null) {
            return false
        }
        if (config.onlyWithSeveral && !hasCompetition(blockSpanGroupOf(accent), counts)) {
            return false
        }
        return accent.spannedLines >= config.minLines
    }

    /**
     * Whether the file holds more than one block of this kind, so that saying which one just
     * ended is worth the ink. Only types and namespaces ask; functions and properties have the
     * restriction switched off for good and never reach this.
     */
    fun hasCompetition(group: BlockSpanGroup, counts: BlockCounts): Boolean {
        if (group == BlockSpanGroup.TYPE) {
            return counts.types > 1
        }
        if (group == BlockSpanGroup.NAMESPACE) {
            return counts.namespaces > 1
        }
        return true
    }

    /**
     * Which set of span settings a block answers to. Properties and their accessors are pulled
     * out of the function group because their sense of "long" is not a method's: a forty-line
     * property is remarkable, a forty-line method is a Tuesday.
     */
    fun blockSpanGroupOf(accent: BraceAccent): BlockSpanGroup {
        if (accent.kind == BlockKind.NAMESPACE) {
            return BlockSpanGroup.NAMESPACE
        }
        if (accent.kind == BlockKind.TYPE) {
            return BlockSpanGroup.TYPE
        }
        if (accent.isAccessor || accent.keyword == PROPERTY_KEYWORD) {
            return BlockSpanGroup.PROPERTY
        }
        return BlockSpanGroup.FUNCTION
    }

    /**
     * Whether the closing brace says what it closes. Three separate reasons, any one enough:
     * the block is long enough for its kind ([labelMinLines]); it is nested inside one of its
     * own kind, the hardest declaration of all to find by scrolling ([nestedLabelAlways]); or
     * it is numbered and the number is being repeated down here, since `[3]` alone says
     * nothing. [showLabel] -- the per-kind "label this kind at all" switch -- sits above all
     * three.
     *
     * The span marker is deliberately not among the reasons: it reads perfectly well on its
     * own, so it draws itself and asks for nothing. See [showsBlockSpan].
     */
    fun needsLabel(
        accent: BraceAccent,
        showLabel: Boolean,
        labelMinLines: Int,
        nestedLabelAlways: Boolean,
        numberingEnabled: Boolean,
        numberingMinLines: Int,
    ): Boolean {
        if (accent.isOpening) {
            return false
        }
        if (!showLabel) {
            return false
        }
        if (accent.keyword.isEmpty()) {
            return false
        }
        // Deliberately not the `nest` marker's own switch: the word `nest` and "name a nested
        // block at all" are two switches, not one.
        if (nestedLabelAlways && isNestedBlock(accent)) {
            return true
        }
        if (numbersEndOfBlock(accent, numberingEnabled, numberingMinLines)) {
            return true
        }
        return accent.spannedLines >= labelMinLines
    }

    /**
     * A block nested inside another of its own kind, lambdas and accessors aside -- both are
     * inside something by definition, so saying "nested" about them adds nothing and would put
     * the word on every `get` and `set` in the file.
     */
    fun isNestedBlock(accent: BraceAccent): Boolean {
        if (accent.isLambda || accent.isAccessor) {
            return false
        }
        return accent.isNested
    }

    /**
     * Whether a numbered block repeats its `[N]` on the closing brace. Below [minLines] the
     * opening line is still on screen, so the reader can see which sibling this is and the copy
     * would be pure noise.
     */
    fun numbersEndOfBlock(accent: BraceAccent, numberingEnabled: Boolean, minLines: Int): Boolean {
        if (!numberingEnabled) {
            return false
        }
        if (accent.siblingOrdinal <= 0) {
            return false
        }
        return accent.spannedLines >= minLines
    }

    /** `"[N]"`, or `""` when there is no ordinal to show on this side of the block. */
    fun siblingOrdinalText(
        accent: BraceAccent,
        numberingEnabled: Boolean,
        endOfBlockMinLines: Int,
    ): String {
        if (!numberingEnabled) {
            return ""
        }
        if (accent.siblingOrdinal <= 0) {
            return ""
        }
        // On the declaration line the ordinal is always shown, because the real declaration is
        // right next to it and explains it. On the closing brace it has to earn its place.
        if (!accent.isOpening && !numbersEndOfBlock(accent, numberingEnabled, endOfBlockMinLines)) {
            return ""
        }
        return "[" + accent.siblingOrdinal + "]"
    }

    /**
     * `↑: 17  Δ: 112` -- the line the block is **declared** on, then how many lines below it
     * the `}` sits. The two are derived from one another rather than measured separately, so
     * the pair always adds up to the line the reader is looking at: 17 + 112 = 129, this very
     * line. That is what makes the marker checkable at a glance instead of being two numbers to
     * trust.
     *
     * The arrow, not a `{`, is the point of the first number. Until 1.11.0 this read `{: 17`,
     * which was a lie by one line in Allman style and by a whole signature when the signature
     * wrapped: the number has been the declaration's line since the span started being measured
     * from the declaration, and the glyph now says so.
     *
     * @param closingLine the 1-based line the closing brace is on, matching the gutter
     */
    fun blockSpanText(closingLine: Int, spannedLines: Int): String {
        return SPAN_START_PREFIX + declarationLine(closingLine, spannedLines) +
            SPAN_DELTA_PREFIX + spannedLines
    }

    /** The 1-based line the block's declaration is on, given where its closing brace landed. */
    fun declarationLine(closingLine: Int, spannedLines: Int): Int {
        return closingLine - spannedLines
    }

    /** Where the block started, pointing up the file at it. */
    const val SPAN_START_PREFIX = "↑: "

    /**
     * Greek capital delta, the usual sign for a difference, between the two halves of the span
     * marker. Two spaces before it, so the pair reads as two facts rather than one.
     */
    const val SPAN_DELTA_PREFIX = "  Δ: "
}
