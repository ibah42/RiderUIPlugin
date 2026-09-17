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
 * Draws the phantom lines underneath their owner line.
 *
 * This is not text: the caret cannot be placed here, and nothing reaches the clipboard or git.
 * The caret walks the real text and the inlay simply flows around it, like parameter hints.
 *
 * Phantom highlighting is taken from the real text: every phantom character lives at a known
 * document offset, so its colour and font style can be asked of the editor.
 *
 * @param braceStyles styling of type and function braces, keyed by brace offset. It lives in
 *   `editor.markupModel`, which [EditorColorSampler] does not read, so it has to be passed in.
 *   The phantom brace shadow is drawn here as well — otherwise, in K&R code, only the real brace
 *   would have one, and that brace is not what you see.
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

    /** Drawn before the glyph itself, so it ends up underneath. */
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

        // A type or function brace is painted on top of the ordinary highlighting.
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

    /** Phantom line indent in columns: the owner's indent plus nesting levels. */
    private fun startColumn(editor: Editor, line: PhantomLine): Int {
        return indentInColumns(editor) + line.extraIndentLevels * indentSize(editor)
    }

    private fun indentSize(editor: Editor): Int {
        return editor.settings.getTabSize(editor.project).coerceAtLeast(1)
    }

    /** Owner line indent in columns; a tab expands to the next tab stop. */
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
        /** Spare column on the right so the last character is not clipped. */
        const val TRAILING_COLUMNS = 1
    }
}
