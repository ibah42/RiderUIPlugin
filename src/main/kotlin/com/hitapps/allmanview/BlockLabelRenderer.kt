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
 * Inline text drawn without touching the document, as one or more differently-coloured runs.
 *
 * Three call sites share this renderer: the declaration-line marker (`[N] nest`, one run), the
 * end-of-block label after `}` (up to three runs -- the `[N] nest` marker, the bare construct
 * word `class`/`struct`/`fun`/`ns`/... in the editor's own keyword colour, and the symbol's own
 * name in its own real accent colour, so a phantom label never paints `class` in the colour of
 * a class name just because they sit in the same label).
 *
 * @param segments the runs to draw, left to right, each in its own colour
 * @param leadingSpaces gap before the text, in columns
 * @param trailingSpaces gap after the text, in columns, so it never sticks to the real code
 */
class BlockLabelRenderer(
    private val segments: List<Segment>,
    private val leadingSpaces: Int,
    private val trailingSpaces: Int,
) : EditorCustomElementRenderer {

    /** One coloured run of text within the label. */
    data class Segment(val text: String, val color: Color)

    /**
     * Measured with the very font [paint] draws with, not by counting columns: the label is
     * italic, and an italic advance is not promised to match the width of a plain space even
     * in a monospaced scheme. Counting columns would leave the tail clipped by a few pixels.
     */
    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val metrics = editor.contentComponent.getFontMetrics(labelFont(editor))
        val gapWidth = (leadingSpaces + trailingSpaces) * EditorUtil.getSpaceWidth(Font.PLAIN, editor)

        var textWidth = 0
        for (segment in segments) {
            textWidth += metrics.stringWidth(segment.text)
        }
        return gapWidth + textWidth
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
        for (segment in segments) {
            if (segment.text.isEmpty()) {
                continue
            }
            graphics.color = segment.color
            graphics.drawString(segment.text, currentX, baseline)
            currentX += metrics.stringWidth(segment.text)
        }
    }

    /** Italic, so a label never reads as part of the code it sits next to. */
    private fun labelFont(editor: Editor): Font {
        return editor.colorsScheme.getFont(EditorFontType.ITALIC)
    }
}
