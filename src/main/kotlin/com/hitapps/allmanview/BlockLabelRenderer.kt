package com.hitapps.allmanview

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.editor.Editor
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
 * Inline text drawn without touching the document.
 *
 * Two call sites share this renderer: the end-of-block label after `}` (`class IosHttpClient`,
 * optionally preceded by a dimmed `nest `), and the `nest` marker before a nested block's
 * own declaration line. [prefixText] is empty for the plain, non-nested label.
 *
 * @param leadingSpaces gap before the text, in columns
 * @param trailingSpaces gap after the text, in columns, so it never sticks to the real code
 */
class BlockLabelRenderer(
    private val prefixText: String,
    private val prefixColor: Color,
    private val labelText: String,
    private val labelColor: Color,
    private val leadingSpaces: Int,
    private val trailingSpaces: Int,
) : EditorCustomElementRenderer {

    /**
     * Measured with the very font [paint] draws with, not by counting columns: the label is
     * italic, and an italic advance is not promised to match the width of a plain space even
     * in a monospaced scheme. Counting columns would leave the tail clipped by a few pixels.
     */
    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val metrics = editor.contentComponent.getFontMetrics(labelFont(editor))
        val gapWidth = (leadingSpaces + trailingSpaces) * EditorUtil.getSpaceWidth(Font.PLAIN, editor)

        return gapWidth + metrics.stringWidth(prefixText) + metrics.stringWidth(labelText)
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

        val font = labelFont(editor)
        graphics.font = font

        val metrics = graphics.getFontMetrics(font)
        val lineHeight = editor.lineHeight
        val baseline = targetRegion.y + (lineHeight + metrics.ascent - metrics.descent) / 2
        val columnWidth = EditorUtil.getSpaceWidth(Font.PLAIN, editor)

        var currentX = targetRegion.x + leadingSpaces * columnWidth
        if (prefixText.isNotEmpty()) {
            graphics.color = prefixColor
            graphics.drawString(prefixText, currentX, baseline)
            currentX += metrics.stringWidth(prefixText)
        }

        graphics.color = labelColor
        graphics.drawString(labelText, currentX, baseline)
    }

    /** Italic, so a label never reads as part of the code it sits next to. */
    private fun labelFont(editor: Editor): Font {
        return editor.colorsScheme.getFont(EditorFontType.ITALIC)
    }
}
