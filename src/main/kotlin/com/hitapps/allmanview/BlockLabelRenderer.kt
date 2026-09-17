package com.hitapps.allmanview

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle

/**
 * Подпись после закрывающей скобки длинного блока: `class IosHttpClient`, `fun Update`.
 *
 * Inline-инлей, а не текст: в файл ничего не пишется. Стоит сразу после `}`, поэтому
 * ничего не сдвигает — правее скобки на строке обычно пусто.
 */
class BlockLabelRenderer(
    private val labelText: String,
    private val labelColor: Color,
) : EditorCustomElementRenderer {

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val columnWidth = EditorUtil.getSpaceWidth(Font.PLAIN, inlay.editor)
        return (LEADING_SPACES + labelText.length) * columnWidth
    }

    override fun paint(
        inlay: Inlay<*>,
        graphics: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes,
    ) {
        val editor = inlay.editor
        if (graphics is Graphics2D) {
            UISettings.setupAntialiasing(graphics)
        }

        val font = editor.colorsScheme.getFont(EditorFontType.ITALIC)
        graphics.font = font
        graphics.color = labelColor

        val metrics = graphics.getFontMetrics(font)
        val lineHeight = editor.lineHeight
        val baseline = targetRegion.y + (lineHeight + metrics.ascent - metrics.descent) / 2
        val columnWidth = EditorUtil.getSpaceWidth(Font.PLAIN, editor)

        graphics.drawString(labelText, targetRegion.x + LEADING_SPACES * columnWidth, baseline)
    }

    private companion object {
        /** Отступ от скобки, чтобы подпись не липла к `}`. */
        const val LEADING_SPACES = 2
    }
}
