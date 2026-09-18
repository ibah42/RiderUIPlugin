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

/** Colour and font style for a piece of text, character by character. */
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
 * Reads the editor's real highlighting for an arbitrary slice of the document.
 *
 * Two sources, in priority order: the editor's lexer and the document markup. In Rider the C#
 * highlighting arrives from the ReSharper backend as markup, so the lexer alone is not enough.
 *
 * Only `DocumentMarkupModel` is read and never `editor.markupModel`: the latter holds our own
 * dimming highlighters, and a phantom would end up greying itself out.
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

    /**
     * Colour of a single character -- for example the class name a brace takes its colour from.
     *
     * Deliberately not `styleOf(editor, offset, 1)`: this is the most-called thing in the
     * plugin (every accent asks for one or two colours, on every redraw), and going through the
     * run-based path would allocate two arrays and a list and sort that list, all to read one
     * colour. The answer is the same -- the highest-layer markup that actually covers [offset]
     * and carries a foreground, and the lexer's own colour when no markup does -- it is just
     * found by keeping the best candidate instead of sorting every candidate.
     */
    fun foregroundAt(editor: Editor, offset: Int): Color? {
        if (offset < 0 || offset >= editor.document.textLength) {
            return null
        }

        var foreground = lexerForegroundAt(editor, offset)
        val project = editor.project
        if (project == null) {
            return foreground
        }

        val markup = DocumentMarkupModel.forDocument(editor.document, project, false)
        if (markup !is MarkupModelEx) {
            return foreground
        }

        val scheme = editor.colorsScheme
        // `>=`, not `>`: among equal layers the last one wins, which is what sorting by layer
        // and applying in order does in styleOf, since that sort is stable.
        var topLayer = Int.MIN_VALUE
        markup.processRangeHighlightersOverlappingWith(offset, offset + 1) { highlighter ->
            if (covers(highlighter, offset) && highlighter.layer >= topLayer) {
                val candidate = attributesOf(highlighter, scheme)?.foregroundColor
                if (candidate != null) {
                    foreground = candidate
                    topLayer = highlighter.layer
                }
            }
            true
        }
        return foreground
    }

    /**
     * "Overlapping" includes a highlighter that only touches the one-character window without
     * containing [offset] itself. [TextStyleRun.apply] drops those by intersecting the ranges;
     * here the same is done up front.
     */
    private fun covers(highlighter: RangeHighlighterEx, offset: Int): Boolean {
        return highlighter.startOffset <= offset && offset < highlighter.endOffset
    }

    /** The colour the lexer alone would paint at [offset], before any markup is laid over it. */
    private fun lexerForegroundAt(editor: Editor, offset: Int): Color? {
        val editorEx = editor as? EditorEx
        if (editorEx == null) {
            return null
        }

        val iterator = editorEx.highlighter.createIterator(offset)
        if (iterator.atEnd()) {
            return null
        }
        return iterator.textAttributes?.foregroundColor
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

        // The layer decides who wins; iteration order does not guarantee it.
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
