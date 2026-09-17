package com.hitapps.allmanview

import com.hitapps.allmanview.scan.BlockKind
import com.hitapps.allmanview.scan.BraceAccent
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
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
    val labelText: String,
    val labelColor: Color,
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

        val cacheKey = accent.kind.name + ":" + accent.nameOffset + ":" + accent.nameLength
        val cached = cache[cacheKey]
        if (cached != null) {
            return cached
        }

        val braceColor = shiftAwayFromBackground(
            baseColor(accent),
            config.lightPercent,
            config.darkPercent,
        )

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
            labelText = labelText(accent),
            labelColor = ColorBalance.towardsGrey(braceColor, config.labelGreyPercent),
        )
        cache[cacheKey] = style
        return style
    }

    /**
     * A nested block always gets a label, however short it is: with the fences gone, the label
     * is the only thing left that names it. A top-level block only earns one once it is long
     * enough that its own opening line has scrolled out of view.
     */
    fun needsLabel(accent: BraceAccent): Boolean {
        if (accent.isOpening) {
            return false
        }
        val config = settings.accentFor(accent.kind)
        if (config == null || !config.showLabel) {
            return false
        }
        if (labelText(accent).isEmpty()) {
            return false
        }
        if (marksAsNested(accent)) {
            return true
        }
        return accent.spannedLines >= config.labelMinLines
    }

    /**
     * The declaration line of a nested block also gets a `nest` marker before it, in the same
     * colour as the prefix on its end-of-block label.
     */
    fun needsNestedMarker(accent: BraceAccent): Boolean {
        if (!accent.isOpening) {
            return false
        }
        if (!marksAsNested(accent)) {
            return false
        }
        val config = settings.accentFor(accent.kind)
        return config != null && config.showLabel
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
        return accent.isNested && !accent.isLambda
    }

    /**
     * Colour of the `nest` marker text itself: the editor's own keyword colour, pushed towards
     * grey by [AllmanSettings.Config.nestedLabelGreyPercent]. Independent of the block's own
     * accent colour, since "nested" names a language construct, not a symbol.
     */
    fun nestedMarkerColor(): Color {
        val scheme = editor.colorsScheme
        val keywordColor = scheme.getAttributes(DefaultLanguageHighlighterColors.KEYWORD)
            ?.foregroundColor
            ?: scheme.defaultForeground
        return ColorBalance.towardsGrey(keywordColor, settings.state.nestedLabelGreyPercent)
    }

    /**
     * A lambda's label shows the lambda symbol instead of `fun`: it has no declaration of its
     * own to be a `fun` of, and lambda calculus already owns the glyph (the Half-Life logo is
     * the same choice, for the same reason).
     */
    private fun labelText(accent: BraceAccent): String {
        if (accent.keyword.isEmpty()) {
            return ""
        }
        val keyword: String
        if (accent.isLambda) {
            keyword = LAMBDA_SYMBOL
        } else {
            keyword = accent.keyword
        }

        if (accent.nameOffset < 0 || accent.nameLength <= 0) {
            return keyword
        }

        val end = accent.nameOffset + accent.nameLength
        if (end > editor.document.textLength) {
            return keyword
        }
        val name = editor.document.immutableCharSequence.subSequence(accent.nameOffset, end)
        return keyword + " " + name
    }

    /**
     * Sampled from the block's own name, so the brace matches whatever the scheme paints that
     * name. A namespace has no name recorded, so the sampler returns null for its offset of -1
     * and it lands on the keyword colour below -- which is the right answer for it anyway:
     * `ns` names a language construct, not a symbol.
     */
    private fun baseColor(accent: BraceAccent): Color {
        val sampled = EditorColorSampler.foregroundAt(editor, accent.nameOffset)
        if (sampled != null) {
            return sampled
        }

        val key: TextAttributesKey
        when (accent.kind) {
            BlockKind.TYPE -> {
                key = DefaultLanguageHighlighterColors.CLASS_NAME
            }
            BlockKind.NAMESPACE -> {
                key = DefaultLanguageHighlighterColors.KEYWORD
            }
            else -> {
                key = DefaultLanguageHighlighterColors.FUNCTION_DECLARATION
            }
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
    }
}
