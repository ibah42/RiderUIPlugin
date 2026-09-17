package com.hitapps.allmanview

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D

/**
 * Тень под скобкой: копия глифа со сдвигом.
 *
 * Именно тень, а не жирный шрифт: [CustomHighlighterRenderer] по умолчанию рисует
 * над фоном, но **до текста**, поэтому копия ложится под настоящий глиф и добавляет
 * объём, не размывая сам символ. Если бы рисовалось после текста, по краям была бы грязь.
 */
class BraceShadowRenderer(
    private val shadowColor: Color,
    private val offsetX: Int,
    private val offsetY: Int,
) : CustomHighlighterRenderer {

    override fun paint(editor: Editor, highlighter: RangeHighlighter, graphics: Graphics) {
        if (!highlighter.isValid) {
            return
        }

        val start = highlighter.startOffset
        val end = highlighter.endOffset
        if (start >= end || end > editor.document.textLength) {
            return
        }

        val brace = editor.document.immutableCharSequence.subSequence(start, end).toString()
        if (brace.isBlank()) {
            return
        }

        if (graphics is Graphics2D) {
            UISettings.setupAntialiasing(graphics)
        }

        val font = editor.colorsScheme.getFont(EditorFontType.BOLD)
        graphics.font = font
        graphics.color = shadowColor

        val point = editor.offsetToXY(start)
        val metrics = graphics.getFontMetrics(font)
        val lineHeight = editor.lineHeight
        val baseline = point.y + (lineHeight + metrics.ascent - metrics.descent) / 2

        graphics.drawString(brace, point.x + offsetX, baseline + offsetY)
    }
}
