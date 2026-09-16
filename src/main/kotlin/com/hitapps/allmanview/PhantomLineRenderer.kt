package com.hitapps.allmanview

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle

/**
 * Рисует фантомные строки под строкой-владельцем.
 * Это не текст — каретку сюда поставить нельзя, в буфер обмена и в git ничего не попадает.
 */
class PhantomLineRenderer(
    private val indent: String,
    private val lines: List<String>,
) : EditorCustomElementRenderer {

    override fun calcHeightInPixels(inlay: Inlay<*>): Int =
        inlay.editor.lineHeight * lines.size

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val fm = editor.contentComponent.getFontMetrics(font(editor))
        val prefix = fm.stringWidth(expandedIndent(editor))
        return prefix + (lines.maxOfOrNull { fm.stringWidth(it) } ?: 0) + fm.charWidth(' ')
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, target: Rectangle, textAttributes: TextAttributes) {
        val editor = inlay.editor
        (g as? Graphics2D)?.let { UISettings.setupAntialiasing(it) }

        val f = font(editor)
        g.font = f
        val fm: FontMetrics = g.getFontMetrics(f)
        g.color = editor.colorsScheme
            .getAttributes(DefaultLanguageHighlighterColors.BRACES)
            ?.foregroundColor
            ?: editor.colorsScheme.defaultForeground

        val lineHeight = editor.lineHeight
        val x = target.x + fm.stringWidth(expandedIndent(editor))
        for ((idx, s) in lines.withIndex()) {
            val top = target.y + idx * lineHeight
            val baseline = top + (lineHeight + fm.ascent - fm.descent) / 2
            g.drawString(s, x, baseline)
        }
    }

    private fun font(editor: Editor): Font = editor.colorsScheme.getFont(EditorFontType.PLAIN)

    /** Табы разворачиваем в пробелы, иначе измерение ширины врёт. */
    private fun expandedIndent(editor: Editor): String {
        if ('\t' !in indent) return indent
        val tab = editor.settings.getTabSize(editor.project).coerceAtLeast(1)
        val sb = StringBuilder()
        for (c in indent) {
            if (c == '\t') {
                val pad = tab - (sb.length % tab)
                repeat(pad) { sb.append(' ') }
            } else sb.append(c)
        }
        return sb.toString()
    }
}
