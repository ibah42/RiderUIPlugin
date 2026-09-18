package com.hitapps.allmanview

import com.hitapps.allmanview.scan.BlockKind
import com.hitapps.allmanview.scan.BraceAccent
import com.hitapps.allmanview.scan.BraceScanner
import com.hitapps.allmanview.scan.Dialects
import com.hitapps.allmanview.scan.Flavor
import com.hitapps.allmanview.scan.PhantomSite
import com.hitapps.allmanview.scan.ScanOptions
import com.hitapps.allmanview.scan.ScanResult
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.util.Alarm
import java.awt.Color

/**
 * One instance per editor.
 *
 * The document is never touched and nothing is hidden by folding: the original text stays
 * where it is and is dimmed by highlighting, while phantom lines are block inlays. That is
 * why there are no clashes with ReSharper's folding and no caret being pushed around while
 * typing.
 */
class AllmanController(private val editor: Editor) : Disposable {

    private val ownHighlighters = ArrayList<RangeHighlighter>()
    private val inlays = ArrayList<Inlay<*>>()
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    init {
        editor.putUserData(KEY, this)
        editor.document.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    schedule()
                }
            },
            this,
        )
        schedule(IMMEDIATE_DELAY_MS)
    }

    fun schedule(delayMs: Int = REFRESH_DELAY_MS) {
        if (editor.isDisposed) {
            return
        }
        refreshAlarm.cancelAllRequests()
        refreshAlarm.addRequest({ refresh() }, delayMs)
    }

    private fun refresh() {
        if (editor.isDisposed) {
            return
        }
        clear()

        val settings = AllmanSettings.getInstance()
        if (!settings.state.enabled) {
            return
        }

        val flavor = flavorFor()
        if (flavor == null) {
            return
        }
        if (editor.document.textLength > MAX_FILE_CHARS) {
            return
        }

        val result = BraceScanner(
            editor.document.immutableCharSequence,
            flavor,
            scanOptions(settings),
        ).scan()

        val accentStyle = BraceAccentStyle(editor, settings)
        val braceStyles = buildBraceStyles(accentStyle, result)

        // The mechanics are independent: any one of them can be off while the others work.
        if (settings.state.moveBraces) {
            paintMovedBraces(settings, result, braceStyles)
        }
        paintNestedMarkers(accentStyle, result)
        paintRealBraces(accentStyle, result, braceStyles, dimmedBraceOffsets(settings, result))
    }

    private fun scanOptions(settings: AllmanSettings): ScanOptions {
        return ScanOptions(
            fullAllman = settings.state.fullAllman,
            splitStatements = settings.state.splitStatements,
            expandInlineBlocks = settings.state.expandInlineBlocks,
            accentTypes = settings.state.accentBraces && settings.state.accentTypes,
            accentFunctions = settings.state.accentBraces && settings.state.accentFunctions,
            accentNamespaces = settings.state.accentBraces && settings.state.accentNamespaces,
        )
    }

    /** Brace offset to its styling. */
    private fun buildBraceStyles(
        style: BraceAccentStyle,
        result: ScanResult,
    ): Map<Int, BraceStyle> {
        if (result.accents.isEmpty()) {
            return emptyMap()
        }

        val styles = HashMap<Int, BraceStyle>(result.accents.size)
        for (accent in result.accents) {
            val braceStyle = style.styleFor(accent)
            if (braceStyle != null) {
                styles[accent.offset] = braceStyle
            }
        }
        return styles
    }

    private fun paintMovedBraces(
        settings: AllmanSettings,
        result: ScanResult,
        braceStyles: Map<Int, BraceStyle>,
    ) {
        val dimAttributes: TextAttributes?
        if (settings.state.dimOriginal) {
            dimAttributes = TextAttributes()
            dimAttributes.foregroundColor = dimColor(settings)
        } else {
            dimAttributes = null
        }

        val documentLength = editor.document.textLength
        for (site in result.sites) {
            if (site.dimEnd > documentLength || site.anchorOffset > documentLength) {
                continue
            }
            if (dimAttributes != null) {
                addHighlighter(site.dimStart, site.dimEnd, DIM_LAYER_OFFSET, dimAttributes)
            }
            addPhantomLines(site, braceStyles)
        }
    }

    /**
     * Real `{` and `}` of types and functions: colour, shadow and the end-of-block label.
     *
     * A brace already dimmed as "moved down" is left alone, because accenting would contradict
     * the dimming. Its role is played by the phantom, which the renderer colours. The label and
     * the shadow are still attached, since they belong to the real brace.
     */
    private fun paintRealBraces(
        style: BraceAccentStyle,
        result: ScanResult,
        braceStyles: Map<Int, BraceStyle>,
        dimmedBraces: Set<Int>,
    ) {
        val documentLength = editor.document.textLength

        for (accent in result.accents) {
            if (accent.offset >= documentLength) {
                continue
            }
            val braceStyle = braceStyles[accent.offset]
            if (braceStyle == null) {
                continue
            }

            if (accent.offset !in dimmedBraces) {
                addHighlighter(
                    accent.offset,
                    accent.offset + 1,
                    ACCENT_LAYER_OFFSET,
                    braceStyle.attributes,
                )
                addShadow(accent.offset, braceStyle)
            }

            if (style.needsLabel(accent)) {
                addLabel(accent, style, braceStyle)
            }
        }
    }

    /**
     * The `nest` marker before a nested block's own declaration line, in the same colour as
     * the prefix on its end-of-block label. Placed right after the line's indent, so the real
     * declaration is pushed right rather than the marker landing in the margin.
     */
    private fun paintNestedMarkers(style: BraceAccentStyle, result: ScanResult) {
        val documentLength = editor.document.textLength

        for (accent in result.accents) {
            if (!style.needsNestedMarker(accent)) {
                continue
            }
            val headerOffset = headerOffsetOf(accent)
            if (headerOffset >= documentLength) {
                continue
            }
            val contentOffset = contentStartOffset(headerOffset)
            addNestedMarker(contentOffset, style.nestedMarkerColor(contentOffset))
        }
    }

    /** The declaration line, not the brace line: a multi-line signature starts well above it. */
    private fun headerOffsetOf(opening: BraceAccent): Int {
        if (opening.headerOffset in 0 until editor.document.textLength) {
            return opening.headerOffset
        }
        return opening.offset
    }

    /** First non-blank character of the line holding this offset, past its indent. */
    private fun contentStartOffset(offset: Int): Int {
        val document = editor.document
        val lineStart = document.getLineStartOffset(document.getLineNumber(offset))
        val characters = document.immutableCharSequence

        var end = lineStart
        while (end < characters.length && (characters[end] == ' ' || characters[end] == '\t')) {
            end++
        }
        return end
    }

    private fun addNestedMarker(offset: Int, color: Color) {
        val inlay = editor.inlayModel.addInlineElement(
            offset,
            /* relatesToPrecedingText = */ false,
            BlockLabelRenderer(
                prefixText = "",
                prefixColor = color,
                labelText = BraceAccentStyle.NESTED_MARKER_TEXT,
                labelColor = color,
                leadingSpaces = 0,
                trailingSpaces = 1,
            ),
        )
        if (inlay != null) {
            inlays.add(inlay)
        }
    }

    private fun addShadow(braceOffset: Int, braceStyle: BraceStyle) {
        val shadowColor = braceStyle.shadowColor
        if (shadowColor == null) {
            return
        }

        val highlighter = editor.markupModel.addRangeHighlighter(
            braceOffset,
            braceOffset + 1,
            HighlighterLayer.LAST + SHADOW_LAYER_OFFSET,
            null,
            HighlighterTargetArea.EXACT_RANGE,
        )
        highlighter.customRenderer = BraceShadowRenderer(
            shadowColor,
            braceStyle.shadowOffsetX,
            braceStyle.shadowOffsetY,
            braceStyle.attributes.fontType,
        )
        ownHighlighters.add(highlighter)
    }

    private fun addLabel(accent: BraceAccent, style: BraceAccentStyle, braceStyle: BraceStyle) {
        val prefixText: String
        if (style.marksAsNested(accent)) {
            prefixText = BraceAccentStyle.NESTED_MARKER_TEXT + " "
        } else {
            prefixText = ""
        }

        val inlay = editor.inlayModel.addInlineElement(
            accent.offset + 1,
            /* relatesToPrecedingText = */ true,
            BlockLabelRenderer(
                prefixText = prefixText,
                // Sampled at the brace itself, right before the label: a closing brace inside a
                // disabled #if branch or unreachable code is already painted grey there, and the
                // marker should read the same way.
                prefixColor = style.nestedMarkerColor(accent.offset),
                labelText = braceStyle.labelText,
                labelColor = braceStyle.labelColor,
                leadingSpaces = LABEL_LEADING_SPACES,
                trailingSpaces = 0,
            ),
        )
        if (inlay != null) {
            inlays.add(inlay)
        }
    }

    /**
     * The braces the move mechanic dimmed, so accenting can leave them alone.
     *
     * Collected once per refresh rather than asked per brace: walking every site for every
     * accent is O(accents x sites), which on a large file is millions of comparisons on the
     * EDT for a single keystroke. Only brace offsets are kept, so the set stays small however
     * much text is dimmed.
     */
    private fun dimmedBraceOffsets(settings: AllmanSettings, result: ScanResult): Set<Int> {
        if (!settings.state.moveBraces) {
            return emptySet()
        }
        if (!settings.state.dimOriginal) {
            return emptySet()
        }

        val braceOffsets = HashSet<Int>(result.accents.size)
        for (accent in result.accents) {
            braceOffsets.add(accent.offset)
        }

        val dimmed = HashSet<Int>()
        for (site in result.sites) {
            for (offset in site.dimStart until site.dimEnd) {
                if (braceOffsets.contains(offset)) {
                    dimmed.add(offset)
                }
            }
        }
        return dimmed
    }

    private fun addHighlighter(
        start: Int,
        end: Int,
        layerOffset: Int,
        attributes: TextAttributes,
    ) {
        val highlighter = editor.markupModel.addRangeHighlighter(
            start,
            end,
            // above the syntax highlighting, otherwise the colour is overridden back
            HighlighterLayer.LAST + layerOffset,
            attributes,
            HighlighterTargetArea.EXACT_RANGE,
        )
        ownHighlighters.add(highlighter)
    }

    private fun addPhantomLines(site: PhantomSite, braceStyles: Map<Int, BraceStyle>) {
        val inlay = editor.inlayModel.addBlockElement(
            site.anchorOffset,
            /* relatesToPrecedingText = */ true,
            /* showAbove = */ false,
            /* priority = */ 0,
            PhantomLineRenderer(site.indent, site.phantomLines, braceStyles),
        )
        if (inlay != null) {
            inlays.add(inlay)
        }
    }

    /** By default the same muted colour the platform uses for parameter hints. */
    private fun dimColor(settings: AllmanSettings): Color {
        val scheme = editor.colorsScheme

        if (settings.state.dimUseHintColor) {
            val hintAttributes = scheme.getAttributes(
                DefaultLanguageHighlighterColors.INLINE_PARAMETER_HINT,
            )
            val hintColor = hintAttributes?.foregroundColor
            if (hintColor != null) {
                return hintColor
            }
        }

        return ColorBalance.mix(
            scheme.defaultForeground,
            scheme.defaultBackground,
            settings.state.dimPercent,
        )
    }

    private fun clear() {
        val markupModel = editor.markupModel
        for (highlighter in ownHighlighters) {
            if (highlighter.isValid) {
                markupModel.removeHighlighter(highlighter)
            }
        }
        ownHighlighters.clear()

        for (inlay in inlays) {
            Disposer.dispose(inlay)
        }
        inlays.clear()
    }

    private fun flavorFor(): Flavor? {
        val file = FileDocumentManager.getInstance().getFile(editor.document)
        if (file == null) {
            return null
        }
        if (file.fileType.isBinary) {
            return null
        }

        val extension = file.extension
        if (!AllmanSettings.getInstance().appliesTo(extension)) {
            return null
        }

        // The dialect only matters for string literal boundaries; an unknown extension is
        // parsed as GENERIC, which works for any C-like language.
        return Dialects.forExtension(extension ?: "")
    }

    override fun dispose() {
        editor.putUserData(KEY, null)
        if (!editor.isDisposed) {
            clear()
        }
    }

    companion object {
        private const val REFRESH_DELAY_MS = 200
        /** Also used by the service when a settings change has to show up at once. */
        const val IMMEDIATE_DELAY_MS = 0

        /** How far above the syntax highlighting the dimming highlighter sits. */
        private const val DIM_LAYER_OFFSET = 100

        /** Brace accents also sit above syntax, but below the dimming. */
        private const val ACCENT_LAYER_OFFSET = 90

        /** The shadow paints before the text; the layer only keeps it out of others' way. */
        private const val SHADOW_LAYER_OFFSET = 80

        /** Gap after the brace so the end-of-block label does not stick to it. */
        private const val LABEL_LEADING_SPACES = 2

        /** The scanner is linear, but a full timed rescan of a huge file is pointless. */
        private const val MAX_FILE_CHARS = 2_000_000

        val KEY: Key<AllmanController> = Key.create("allman.view.controller")
    }
}
