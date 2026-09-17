package com.hitapps.allmanview

import com.hitapps.allmanview.scan.PhantomLine
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
 * Рисует фантомные строки под строкой-владельцем.
 *
 * Это не текст: каретку сюда поставить нельзя, в буфер обмена и в git ничего не попадает.
 * Каретка ходит по реальному тексту, инлей её просто обтекает — как подсказки параметров.
 *
 * Подсветка фантома берётся у настоящего текста: каждый символ фантома лежит в документе
 * по известному offset-у, поэтому цвет и начертание можно спросить у редактора.
 *
 * @param braceStyles оформление скобок типов и функций, по offset-у скобки. Оно живёт
 *   в `editor.markupModel`, куда [EditorColorSampler] не смотрит, поэтому его приходится
 *   передавать отдельно. Тень фантомной скобки рисуется здесь же — иначе в K&R-коде
 *   она была бы только у реальной скобки, которой не видно.
 */
class PhantomLineRenderer(
    private val indent: String,
    private val phantomLines: List<PhantomLine>,
    private val braceStyles: Map<Int, BraceStyle>,
) : EditorCustomElementRenderer {

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        return inlay.editor.lineHeight * phantomLines.size
    }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        var widest = 0

        for (line in phantomLines) {
            val columns = startColumn(editor, line) + line.text.length
            if (columns > widest) {
                widest = columns
            }
        }
        return (widest + TRAILING_COLUMNS) * columnWidth(editor)
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

        val plainFont = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        val lineHeight = editor.lineHeight
        val fontMetrics = graphics.getFontMetrics(plainFont)
        val defaultForeground = editor.colorsScheme.defaultForeground

        for ((lineIndex, line) in phantomLines.withIndex()) {
            val lineTop = targetRegion.y + lineIndex * lineHeight
            val baseline = lineTop + (lineHeight + fontMetrics.ascent - fontMetrics.descent) / 2
            val lineX = targetRegion.x + startColumn(editor, line) * columnWidth(editor)

            paintLine(editor, graphics, line, lineX, baseline, plainFont, defaultForeground)
        }
    }

    private fun paintLine(
        editor: Editor,
        graphics: Graphics,
        line: PhantomLine,
        startX: Int,
        baseline: Int,
        plainFont: Font,
        defaultForeground: Color,
    ) {
        val style = styleOf(editor, line)
        var currentX = startX
        var runStart = 0

        while (runStart < line.text.length) {
            var runEnd = runStart + 1
            while (runEnd < line.text.length && style.sameStyle(runStart, runEnd)) {
                runEnd++
            }

            val chunk = line.text.substring(runStart, runEnd)
            val font = plainFont.deriveFont(style.fontStyles[runStart])
            graphics.font = font

            paintShadow(graphics, chunk, line.sourceOffset + runStart, currentX, baseline)

            graphics.color = style.colors[runStart] ?: defaultForeground
            graphics.drawString(chunk, currentX, baseline)

            currentX += graphics.getFontMetrics(font).stringWidth(chunk)
            runStart = runEnd
        }
    }

    /** Тень рисуется до самого глифа, поэтому ложится под него. */
    private fun paintShadow(
        graphics: Graphics,
        chunk: String,
        sourceOffset: Int,
        currentX: Int,
        baseline: Int,
    ) {
        val braceStyle = braceStyles[sourceOffset]
        if (braceStyle == null) {
            return
        }
        val shadowColor = braceStyle.shadowColor
        if (shadowColor == null) {
            return
        }

        graphics.color = shadowColor
        graphics.drawString(
            chunk,
            currentX + braceStyle.shadowOffsetX,
            baseline + braceStyle.shadowOffsetY,
        )
    }

    private fun styleOf(editor: Editor, line: PhantomLine): TextStyleRun {
        val style = EditorColorSampler.styleOf(editor, line.sourceOffset, line.text.length)

        // Скобка типа или функции красится поверх обычной подсветки.
        for (index in line.text.indices) {
            val braceStyle = braceStyles[line.sourceOffset + index]
            if (braceStyle != null) {
                style.apply(0, line.text.length, index, index + 1, braceStyle.attributes)
            }
        }
        return style
    }

    private fun columnWidth(editor: Editor): Int {
        return EditorUtil.getSpaceWidth(Font.PLAIN, editor)
    }

    /** Отступ фантомной строки в колонках: отступ владельца плюс уровни вложенности. */
    private fun startColumn(editor: Editor, line: PhantomLine): Int {
        return indentInColumns(editor) + line.extraIndentLevels * indentSize(editor)
    }

    private fun indentSize(editor: Editor): Int {
        return editor.settings.getTabSize(editor.project).coerceAtLeast(1)
    }

    /** Отступ строки-владельца в колонках: таб разворачивается до следующей табуляции. */
    private fun indentInColumns(editor: Editor): Int {
        val tabSize = indentSize(editor)
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
