package com.hitapps.allmanview

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle

/**
 * Рисует фантомные строки под строкой-владельцем.
 *
 * Это не текст: каретку сюда поставить нельзя, в буфер обмена и в git ничего не попадает.
 * Каретка ходит по реальному тексту, инлей её просто обтекает — как подсказки параметров.
 */
class PhantomLineRenderer(
    private val indent: String,
    private val phantomLines: List<String>,
) : EditorCustomElementRenderer {

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        return inlay.editor.lineHeight * phantomLines.size
    }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val longestLine = phantomLines.maxOfOrNull { it.length } ?: 0
        val columns = indentInColumns(editor) + longestLine + TRAILING_COLUMNS
        return columns * columnWidth(editor)
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

        val editorFont = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        graphics.font = editorFont
        graphics.color = editor.colorsScheme.defaultForeground

        val fontMetrics = graphics.getFontMetrics(editorFont)
        val lineHeight = editor.lineHeight

        // Ширину колонки берём у самого редактора, а не меряем строку шрифтом:
        // так отступ совпадает с реальным независимо от табов, пробелов и настроек шрифта.
        val textX = targetRegion.x + indentInColumns(editor) * columnWidth(editor)

        for ((lineIndex, lineText) in phantomLines.withIndex()) {
            val lineTop = targetRegion.y + lineIndex * lineHeight
            val baseline = lineTop + (lineHeight + fontMetrics.ascent - fontMetrics.descent) / 2
            graphics.drawString(lineText, textX, baseline)
        }
    }

    private fun columnWidth(editor: Editor): Int {
        return EditorUtil.getSpaceWidth(Font.PLAIN, editor)
    }

    /** Отступ строки-владельца в колонках: таб разворачивается до следующей табуляции. */
    private fun indentInColumns(editor: Editor): Int {
        val tabSize = editor.settings.getTabSize(editor.project).coerceAtLeast(1)
        var columns = 0

        for (character in indent) {
            if (character == '\t') {
                columns += tabSize - (columns % tabSize)
            } else {
                columns++
            }
        }
        return columns
    }

    private companion object {
        /** Запас справа, чтобы последний символ не обрезался. */
        const val TRAILING_COLUMNS = 1
    }
}
