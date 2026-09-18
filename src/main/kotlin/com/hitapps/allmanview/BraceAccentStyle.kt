package com.hitapps.allmanview

import com.hitapps.allmanview.scan.BlockKind
import com.hitapps.allmanview.scan.BraceAccent
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.ColorUtil
import java.awt.Color
import java.awt.Font

/** Everything needed to draw one brace. */
class BraceStyle(
    val attributes: TextAttributes,

    /** null means the shadow is disabled. */
    val shadowColor: Color?,
    val shadowOffsetX: Int,
    val shadowOffsetY: Int,

    /** The bare construct word (`class`, `fun`, `ns`, ...), in the editor's own keyword colour. */
    val keywordText: String,
    val keywordColor: Color,

    /** The symbol's own name, in its own real accent colour -- empty when there is no name. */
    val nameText: String,
    val nameColor: Color,
)

/**
 * Works out the colour of a type or function brace and of its label.
 *
 * The base colour is sampled from the name itself, so it matches whatever ReSharper and the
 * current colour scheme actually do, with no assumptions about attribute keys. When the name
 * carries no colour yet (the backend has not answered), we fall back to the scheme keys.
 *
 * The colour is then pushed away from the background: towards black on a light scheme, towards
 * white on a dark one. Always darkening towards black would sink the brace into a Darcula
 * background.
 */
class BraceAccentStyle(
    private val editor: Editor,
    private val settings: AllmanSettings,
) {
    private val cache = HashMap<String, BraceStyle>()

    /** null means this kind of block is turned off in the settings. */
    fun styleFor(accent: BraceAccent): BraceStyle? {
        val config = settings.accentFor(accent.kind)
        if (config == null) {
            return null
        }

        val cacheKey = accent.kind.name + ":" + accent.nameOffset + ":" + accent.nameLength +
            ":" + accent.headerOffset
        val cached = cache[cacheKey]
        if (cached != null) {
            return cached
        }

        val base = baseColor(accent)
        val braceColor = shiftAwayFromBackground(base, config.lightPercent, config.darkPercent)

        // The keyword word (`class`, `fun`, `ns`, ...) is always the editor's own keyword
        // colour, sampled the same way `nest` and the sibling-ordinal markers already are --
        // never the name's colour, so a phantom label never tints `class` with the colour of a
        // class name just because they share one label.
        val keywordBase = keywordColorAt(accent.headerOffset)

        val attributes = TextAttributes()
        attributes.foregroundColor = braceColor
        if (config.bold) {
            attributes.fontType = Font.BOLD
        }

        val shadowColor: Color?
        if (config.shadow) {
            shadowColor = shadowColor(config.shadowPercent)
        } else {
            shadowColor = null
        }

        val style = BraceStyle(
            attributes = attributes,
            shadowColor = shadowColor,
            shadowOffsetX = config.shadowOffsetX,
            shadowOffsetY = config.shadowOffsetY,
            keywordText = keywordLabelText(accent),
            keywordColor = ColorBalance.towardsGrey(keywordBase, config.labelGreyPercent),
            nameText = nameText(accent),
            nameColor = ColorBalance.towardsGrey(braceColor, config.labelGreyPercent),
        )
        cache[cacheKey] = style
        return style
    }

    /**
     * Whether the closing brace says what it closes. There are three separate reasons for it
     * to, each with its own switch, and any one of them is enough:
     *
     *  - the block is **long**, so its own declaration has scrolled out of view
     *    ([AccentConfig.labelMinLines], per kind);
     *  - the block is **nested** inside one of its own kind, which is the hardest declaration
     *    of all to find by scrolling ([AllmanSettings.Config.nestedLabelAlways]);
     *  - the block is **numbered**, and the number is being repeated down here
     *    ([numbersEndOfBlock]) -- `[3]` on its own says nothing, so whatever makes the ordinal
     *    worth repeating makes the name worth repeating with it;
     *  - the block is long enough to **report its own span** ([showsBlockSpan]), for the same
     *    reason: a span with no name attached says how far you scrolled but not past what.
     *
     * The per-kind "label this kind at all" switch sits above all three: with it off the
     * closing brace stays bare however the block qualifies.
     */
    fun needsLabel(accent: BraceAccent): Boolean {
        if (accent.isOpening) {
            return false
        }
        val config = settings.accentFor(accent.kind)
        if (config == null || !config.showLabel) {
            return false
        }
        if (accent.keyword.isEmpty()) {
            return false
        }
        // Deliberately not marksAsNested: the `nest` word and "name a nested block at all"
        // are two switches, not one. A nested block's own declaration is the hardest to find by
        // scrolling, which is why it may be named whatever its length -- with or without the
        // word `nest` in front of it.
        if (settings.state.nestedLabelAlways && isNestedBlock(accent)) {
            return true
        }
        if (numbersEndOfBlock(accent)) {
            return true
        }
        if (showsBlockSpan(accent)) {
            return true
        }
        return accent.spannedLines >= config.labelMinLines
    }

    /**
     * True for a block nested inside another of the same kind, except a lambda: lambdas are
     * common and short, so marking every one of them would be noise the ordinary label already
     * handles through [AccentConfig.labelMinLines]. Also false outright when the marker is
     * switched off, so both call sites (the label prefix and the declaration-line marker) go
     * quiet together.
     */
    fun marksAsNested(accent: BraceAccent): Boolean {
        if (!settings.state.nestedMarkerEnabled) {
            return false
        }
        return isNestedBlock(accent)
    }

    /**
     * A block nested inside another of its own kind, lambdas aside -- the shape of the thing,
     * with no setting in it. [marksAsNested] adds the marker's own switch on top; [needsLabel]
     * deliberately does not, so the two can be turned off independently.
     */
    private fun isNestedBlock(accent: BraceAccent): Boolean {
        if (accent.isLambda || accent.isAccessor) {
            // Both are inside something by definition -- a lambda inside whatever takes it, an
            // accessor inside its property -- so saying "nested" about them adds nothing and
            // would put the word on every `get` and `set` in the file.
            return false
        }
        return accent.isNested
    }

    /** Whether the braces themselves are decorated -- separate from whether the block is named. */
    fun showsBraces(accent: BraceAccent): Boolean {
        val config = settings.accentFor(accent.kind)
        return config != null && config.showBraces
    }

    /** The `[N]` ordinal's own colour: the keyword colour, with its own distance to grey. */
    fun siblingOrdinalColor(offset: Int): Color {
        return ColorBalance.towardsGrey(keywordColorAt(offset), settings.state.siblingGreyPercent)
    }

    /**
     * Colour of the `nest` marker text itself: the colour the editor actually paints at
     * [offset] -- normally its own keyword colour, but the grey the IDE applies when that
     * position sits in a disabled #if branch or unreachable code -- pushed further towards
     * grey by [AllmanSettings.Config.nestedLabelGreyPercent]. Independent of the block's own
     * accent colour, since "nested" names a language construct, not a symbol.
     */
    fun nestedMarkerColor(offset: Int): Color {
        return ColorBalance.towardsGrey(keywordColorAt(offset), settings.state.nestedLabelGreyPercent)
    }

    /**
     * `"[N]"` when [accent] has a sibling ordinal to show, `""` otherwise. No trailing space:
     * both call sites -- the declaration-line marker and the end-of-block label's prefix --
     * join it with whatever `nest` text follows, exactly as they already join that text with
     * the real label.
     *
     * The two sides do not share a rule. On the declaration line the ordinal is always shown,
     * because the real declaration is right next to it and explains it. On the closing brace
     * it has to earn its place, since by then the declaration may be far above -- see
     * [numbersEndOfBlock].
     */
    fun siblingOrdinalText(accent: BraceAccent): String {
        if (!settings.state.siblingNumberingEnabled) {
            return ""
        }
        if (accent.siblingOrdinal <= 0) {
            return ""
        }
        if (!accent.isOpening && !numbersEndOfBlock(accent)) {
            return ""
        }
        return "[" + accent.siblingOrdinal + "]"
    }

    /**
     * Whether a numbered block repeats its `[N]` on the closing brace: it has an ordinal, the
     * numbering is on, and the block is at least
     * [AllmanSettings.Config.siblingNumberingEndOfBlockMinLines] lines long. Below that the
     * opening line is still on screen, so the reader can see for themselves which sibling this
     * is, and the copy would be pure noise.
     *
     * Also the third reason [needsLabel] fires, which is what keeps `[3]` from ever standing
     * on a closing brace with nothing after it.
     */
    private fun numbersEndOfBlock(accent: BraceAccent): Boolean {
        if (!settings.state.siblingNumberingEnabled) {
            return false
        }
        if (accent.siblingOrdinal <= 0) {
            return false
        }
        return accent.spannedLines >= settings.state.siblingNumberingEndOfBlockMinLines
    }

    /**
     * Whether a block is long enough to report its own span at the closing brace. End-only:
     * see [AllmanSettings.Config.blockSpanMarkerEnabled] for why there is nothing to draw on
     * the declaration line.
     */
    fun showsBlockSpan(accent: BraceAccent): Boolean {
        if (!settings.state.blockSpanMarkerEnabled) {
            return false
        }
        if (accent.isOpening) {
            return false
        }
        return accent.spannedLines >= settings.state.blockSpanMarkerMinLines
    }

    /**
     * `{: 920  Δ: 143` -- the line the block's `{` is on, then how many lines below it the `}`
     * sits. The two are derived from one another rather than measured separately, so the pair
     * always adds up to the line the reader is looking at: start + delta is this very line.
     * That is what makes the marker checkable at a glance instead of being two numbers to
     * trust.
     *
     * Lines are 1-based, matching the gutter. [BraceAccent.spannedLines] is already a delta --
     * the scanner counts it from the opening brace's line -- so no second measurement is taken
     * here and the marker cannot drift from the length every threshold in the plugin uses.
     */
    fun blockSpanText(accent: BraceAccent): String {
        val closingLine = editor.document.getLineNumber(clampToDocument(accent.offset)) + 1
        val openingLine = closingLine - accent.spannedLines
        return BLOCK_SPAN_START_PREFIX + openingLine + BLOCK_SPAN_DELTA_PREFIX + accent.spannedLines
    }

    /**
     * The span marker's own colour, and the one marker not built on the keyword colour: it is
     * built on the editor's line-number colour instead, then pushed towards grey by
     * [AllmanSettings.Config.blockSpanMarkerGreyPercent] like every other marker.
     *
     * Deliberate -- the marker is line numbers. It says nothing about the language, only where
     * in the file you are, which is exactly what the gutter beside it already says, so it
     * should read as an extension of the gutter rather than as another word in the block's
     * title. Nothing is sampled from the document either: the gutter does not dim inside a
     * disabled `#if` branch, so neither does this.
     */
    fun blockSpanColor(): Color {
        return ColorBalance.towardsGrey(lineNumberColor(), settings.state.blockSpanMarkerGreyPercent)
    }

    /** The editor's own line-number colour, falling back to the scheme's plain foreground. */
    private fun lineNumberColor(): Color {
        val scheme = editor.colorsScheme
        val fromScheme = scheme.getColor(EditorColors.LINE_NUMBERS_COLOR)
        if (fromScheme != null) {
            return fromScheme
        }
        return scheme.defaultForeground
    }

    /** Guards [com.intellij.openapi.editor.Document.getLineNumber], which throws out of range. */
    private fun clampToDocument(offset: Int): Int {
        if (offset < 0) {
            return 0
        }
        if (offset > editor.document.textLength) {
            return editor.document.textLength
        }
        return offset
    }

    /**
     * The colour actually painted at [offset], falling back to the scheme's plain keyword
     * colour when there is no real position to sample or nothing was sampled there. Sampling
     * first is what makes a marker or a namespace label follow the editor's own dimming of a
     * disabled #if branch or unreachable code, instead of always showing the undimmed colour.
     */
    private fun keywordColorAt(offset: Int): Color {
        if (offset in 0 until editor.document.textLength) {
            val sampled = EditorColorSampler.foregroundAt(editor, firstNonBlankOffset(offset))
            if (sampled != null) {
                return sampled
            }
        }
        return keywordColor()
    }

    /** First non-space, non-tab character at or after [offset]. */
    private fun firstNonBlankOffset(offset: Int): Int {
        val characters = editor.document.immutableCharSequence
        var end = offset
        while (end < characters.length && (characters[end] == ' ' || characters[end] == '\t')) {
            end++
        }
        return end
    }

    /** The editor's own keyword colour: the fallback for every marker that names a construct. */
    private fun keywordColor(): Color {
        val scheme = editor.colorsScheme
        val fromScheme = scheme.getAttributes(DefaultLanguageHighlighterColors.KEYWORD)
            ?.foregroundColor
        if (fromScheme != null) {
            return fromScheme
        }
        return scheme.defaultForeground
    }

    /**
     * A lambda's label shows the lambda symbol instead of `fun`: it has no declaration of its
     * own to be a `fun` of, and lambda calculus already owns the glyph (the Half-Life logo is
     * the same choice, for the same reason).
     */
    private fun keywordLabelText(accent: BraceAccent): String {
        if (accent.keyword.isEmpty()) {
            return ""
        }
        if (accent.isLambda) {
            if (settings.state.lambdaSymbolEnabled) {
                return LAMBDA_SYMBOL
            }
            return ""
        }
        return accent.keyword
    }

    /** The symbol's own name, exactly as written -- empty when the accent names no symbol. */
    private fun nameText(accent: BraceAccent): String {
        if (accent.isLambda && !settings.state.lambdaNameEnabled) {
            return ""
        }
        if (accent.nameOffset < 0 || accent.nameLength <= 0) {
            return ""
        }
        val end = accent.nameOffset + accent.nameLength
        if (end > editor.document.textLength) {
            return ""
        }
        return editor.document.immutableCharSequence.subSequence(accent.nameOffset, end).toString()
    }

    /**
     * Sampled from the block's own name, so the brace matches whatever the scheme paints that
     * name. A namespace, and a property accessor (`get`/`set`/`init`), have no name recorded,
     * so each is sampled from its own keyword instead -- which is the right answer for both:
     * neither names a symbol, only a language construct.
     */
    private fun baseColor(accent: BraceAccent): Color {
        // No name recorded -- true for every namespace, and now also for a property accessor
        // (`get`, `set`, `init`), which belongs to the property rather than naming a symbol of
        // its own. Sample the construct's own keyword instead of always reading the scheme's
        // keyword colour, so a disabled #if branch or unreachable code greys this out exactly
        // like everything else the editor paints there.
        if (accent.nameOffset < 0 || accent.nameLength <= 0) {
            return keywordColorAt(accent.headerOffset)
        }

        val sampled = EditorColorSampler.foregroundAt(editor, accent.nameOffset)
        if (sampled != null) {
            return sampled
        }

        val key: TextAttributesKey
        if (accent.kind == BlockKind.TYPE) {
            key = DefaultLanguageHighlighterColors.CLASS_NAME
        } else {
            key = DefaultLanguageHighlighterColors.FUNCTION_DECLARATION
        }

        val scheme = editor.colorsScheme
        val fromScheme = scheme.getAttributes(key)?.foregroundColor
        if (fromScheme != null) {
            return fromScheme
        }
        return scheme.defaultForeground
    }

    private fun shiftAwayFromBackground(base: Color, lightPercent: Int, darkPercent: Int): Color {
        val target: Color
        val percent: Int
        if (ColorUtil.isDark(editor.colorsScheme.defaultBackground)) {
            target = Color.WHITE
            percent = darkPercent
        } else {
            target = Color.BLACK
            percent = lightPercent
        }
        return ColorBalance.mix(base, target, percent)
    }

    /** The shadow starts from the background, so it reads as depth rather than as a second glyph. */
    private fun shadowColor(percent: Int): Color {
        return ColorBalance.towardsGrey(editor.colorsScheme.defaultBackground, percent)
    }

    companion object {
        /**
         * What a nested block is marked with, before its declaration and on its own label.
         * It lives here rather than with the painter: the decision to show it is made here too.
         */
        const val NESTED_MARKER_TEXT = "nest"

        /** Greek lowercase lambda, standing in for `fun` on a lambda's own label. */
        private const val LAMBDA_SYMBOL = "λ"

        /** Opens the span marker with the character the marker is about: `{: 920`. */
        private const val BLOCK_SPAN_START_PREFIX = "{: "

        /**
         * Greek capital delta, the usual sign for a difference, between the two halves of the
         * span marker. Two spaces before it, so the pair reads as two facts rather than one.
         */
        private const val BLOCK_SPAN_DELTA_PREFIX = "  Δ: "
    }
}
