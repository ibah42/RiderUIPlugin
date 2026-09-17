package com.hitapps.allmanview

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Color
import java.awt.Font

/** Цвет и начертание для куска текста, посимвольно. */
class TextStyleRun(
    val colors: Array<Color?>,
    val fontStyles: IntArray,
) {
    fun sameStyle(first: Int, second: Int): Boolean {
        return colors[first] == colors[second] && fontStyles[first] == fontStyles[second]
    }

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
}

/**
 * Достаёт настоящую подсветку редактора для произвольного куска документа.
 *
 * Источников два, в порядке приоритета: лексер редактора и разметка документа.
 * В Rider подсветка C# приходит из бэкенда ReSharper именно разметкой, поэтому
 * одного лексера здесь мало.
 *
 * Читаем только `DocumentMarkupModel` и не трогаем `editor.markupModel` — там лежат
 * наши собственные гасящие хайлайтеры, и фантом посерел бы от самого себя.
 */
object EditorColorSampler {

    fun styleOf(editor: Editor, start: Int, length: Int): TextStyleRun {
        val result = TextStyleRun(arrayOfNulls(length), IntArray(length) { Font.PLAIN })
        if (start < 0 || length <= 0) {
            return result
        }

        val end = start + length
        if (end > editor.document.textLength) {
            return result
        }

        applyLexerAttributes(editor, result, start, end)
        applyMarkupAttributes(editor, result, start, end)
        return result
    }

    /** Цвет одного символа — например имени класса, с которого берём цвет для скобки. */
    fun foregroundAt(editor: Editor, offset: Int): Color? {
        if (offset < 0 || offset >= editor.document.textLength) {
            return null
        }
        return styleOf(editor, offset, 1).colors[0]
    }

    private fun applyLexerAttributes(
        editor: Editor,
        target: TextStyleRun,
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
        target: TextStyleRun,
        start: Int,
        end: Int,
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
        overlapping.sortBy { highlighter -> highlighter.layer }
        val scheme = editor.colorsScheme
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
}
