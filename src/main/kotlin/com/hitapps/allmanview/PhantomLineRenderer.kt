package com.hitapps.allmanview

import com.hitapps.allmanview.scan.PhantomLine
import com.intellij.ide.ui.UISettings
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
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
 */
class PhantomLineRenderer(
    private val indent: String,
    private val phantomLines: List<PhantomLine>,
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
            val x = targetRegion.x + startColumn(editor, line) * columnWidth(editor)

            paintLine(editor, graphics, line, x, baseline, plainFont, defaultForeground)
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
        val highlighting = collectHighlighting(editor, line)
        var x = startX
        var runStart = 0

        while (runStart < line.text.length) {
            var runEnd = runStart + 1
            while (runEnd < line.text.length && highlighting.sameRun(runStart, runEnd)) {
                runEnd++
            }

            val chunk = line.text.substring(runStart, runEnd)
            val font = plainFont.deriveFont(highlighting.fontStyles[runStart])
            graphics.font = font
            graphics.color = highlighting.colors[runStart] ?: defaultForeground
            graphics.drawString(chunk, x, baseline)

            x += graphics.getFontMetrics(font).stringWidth(chunk)
            runStart = runEnd
        }
    }

    /**
     * Цвет и начертание для каждого символа фантома.
     *
     * Два источника, в порядке приоритета: лексер редактора и разметка документа.
     * В Rider подсветка C# приходит из бэкенда ReSharper именно разметкой, поэтому
     * одного лексера здесь мало.
     */
    private fun collectHighlighting(editor: Editor, line: PhantomLine): LineHighlighting {
        val length = line.text.length
        val result = LineHighlighting(arrayOfNulls(length), IntArray(length) { Font.PLAIN })

        if (line.sourceOffset < 0) {
            return result
        }

        val start = line.sourceOffset
        val end = start + length
        if (end > editor.document.textLength) {
            return result
        }

        val scheme = editor.colorsScheme
        applyLexerAttributes(editor, result, start, end)
        applyMarkupAttributes(editor, result, start, end, scheme)
        return result
    }

    private fun applyLexerAttributes(
        editor: Editor,
        target: LineHighlighting,
        start: Int,
        end: Int,
    ) {
        val editorEx = editor as? EditorEx
        if (editorEx == null) {
            return
        }

        val iterator = editorEx.highlighter.createIterator(start)
        while (!iterator.atEnd() && iterator.start < end) {
            target.apply(start, end, iterator.start, iterator.end, iterator.textAttributes)
            iterator.advance()
        }
    }

    private fun applyMarkupAttributes(
        editor: Editor,
        target: LineHighlighting,
        start: Int,
        end: Int,
        scheme: EditorColorsScheme,
    ) {
        val project = editor.project
        if (project == null) {
            return
        }

        val markup = DocumentMarkupModel.forDocument(editor.document, project, false)
        if (markup !is MarkupModelEx) {
            return
        }

        val overlapping = ArrayList<RangeHighlighterEx>()
        markup.processRangeHighlightersOverlappingWith(start, end) { highlighter ->
            overlapping.add(highlighter)
            true
        }

        // Слой определяет, кто кого перекрывает, а порядок обхода его не гарантирует.
        overlapping.sortBy { it.layer }
        for (highlighter in overlapping) {
            val attributes = attributesOf(highlighter, scheme)
            target.apply(start, end, highlighter.startOffset, highlighter.endOffset, attributes)
        }
    }

    private fun attributesOf(
        highlighter: RangeHighlighter,
        scheme: EditorColorsScheme,
    ): TextAttributes? {
        val key = highlighter.textAttributesKey
        if (key != null) {
            val fromKey = scheme.getAttributes(key)
            if (fromKey != null) {
                return fromKey
            }
        }
        return highlighter.getTextAttributes(scheme)
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

    /** Подсветка фантомной строки посимвольно. */
    private class LineHighlighting(
        val colors: Array<Color?>,
        val fontStyles: IntArray,
    ) {
        fun apply(
            rangeStart: Int,
            rangeEnd: Int,
            attributeStart: Int,
            attributeEnd: Int,
            attributes: TextAttributes?,
        ) {
            if (attributes == null) {
                return
            }
            val foreground = attributes.foregroundColor
            val fontStyle = attributes.fontType
            if (foreground == null && fontStyle == Font.PLAIN) {
                return
            }

            val from = maxOf(attributeStart, rangeStart)
            val to = minOf(attributeEnd, rangeEnd)
            for (offset in from until to) {
                val index = offset - rangeStart
                if (foreground != null) {
                    colors[index] = foreground
                }
                if (fontStyle != Font.PLAIN) {
                    fontStyles[index] = fontStyle
                }
            }
        }

        fun sameRun(first: Int, second: Int): Boolean {
            return colors[first] == colors[second] && fontStyles[first] == fontStyles[second]
        }
    }

    private companion object {
        /** Запас справа, чтобы последний символ не обрезался. */
        const val TRAILING_COLUMNS = 1
    }
}
