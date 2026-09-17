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
            labelColor = towardsGrey(braceColor, config.labelGreyPercent),
        )
        cache[cacheKey] = style
        return style
    }

    /** Only the closing brace of a long enough block gets a label. */
    fun needsLabel(accent: BraceAccent): Boolean {
        if (accent.isOpening) {
            return false
        }
        val config = settings.accentFor(accent.kind)
        if (config == null || !config.label) {
            return false
        }
        if (labelText(accent).isEmpty()) {
            return false
        }
        return accent.spannedLines >= config.labelMinLines
    }

    private fun labelText(accent: BraceAccent): String {
        if (accent.keyword.isEmpty()) {
            return ""
        }
        if (accent.nameOffset < 0 || accent.nameLength <= 0) {
            return accent.keyword
        }

        val end = accent.nameOffset + accent.nameLength
        if (end > editor.document.textLength) {
            return accent.keyword
        }
        val name = editor.document.immutableCharSequence.subSequence(accent.nameOffset, end)
        return accent.keyword + " " + name
    }

    private fun baseColor(accent: BraceAccent): Color {
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
        return ColorUtil.mix(base, target, balance(percent))
    }

    /**
     * The shadow runs from the background towards grey rather than towards black: grey is darker
     * than a light background and lighter than a dark one, so one setting works in both themes.
     */
    private fun shadowColor(percent: Int): Color {
        val background = editor.colorsScheme.defaultBackground
        return ColorUtil.mix(background, Color.GRAY, balance(percent))
    }

    private fun towardsGrey(base: Color, percent: Int): Color {
        return ColorUtil.mix(base, Color.GRAY, balance(percent))
    }

    private fun balance(percent: Int): Double {
        return percent.coerceIn(0, MAX_PERCENT) / MAX_PERCENT.toDouble()
    }

    private companion object {
        const val MAX_PERCENT = 100
    }
}
