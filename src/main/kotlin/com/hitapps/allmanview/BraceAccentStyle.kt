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

/** Готовое оформление одной скобки. */
class BraceStyle(
    val attributes: TextAttributes,

    /** null — тень выключена. */
    val shadowColor: Color?,
    val shadowOffsetX: Int,
    val shadowOffsetY: Int,
    val labelText: String,
    val labelColor: Color,
)

/**
 * Считает, каким цветом рисовать скобку типа или функции и её подпись.
 *
 * Базовый цвет берём с самого имени — так подсветка совпадает с тем, что делает
 * ReSharper и текущая цветовая схема, без предположений о ключах. Если на имени
 * цвета нет (бэкенд ещё не ответил), падаем на ключи схемы.
 *
 * Дальше цвет уводим в сторону от фона: на светлой схеме к чёрному, на тёмной к белому.
 * Затемнять всегда к чёрному нельзя — на Darcula скобка утонула бы в фоне.
 */
class BraceAccentStyle(
    private val editor: Editor,
    private val settings: AllmanSettings,
) {
    private val cache = HashMap<String, BraceStyle>()

    /** null — этот вид блоков выключен в настройках. */
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

    /** Подпись нужна только у закрывающей скобки достаточно длинного блока. */
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
     * Тень ведём от фона к серому, а не к чёрному: серый темнее светлого фона и светлее
     * тёмного, поэтому одна настройка работает в обеих темах.
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
