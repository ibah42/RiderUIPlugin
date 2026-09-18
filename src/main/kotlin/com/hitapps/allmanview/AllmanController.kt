package com.hitapps.allmanview

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
 *
 * The two mechanics redraw on two separate timers, not one: see [scheduleMove], [scheduleAccent]
 * and the class doc on [MOVE_REFRESH_DELAY_MS] for why, and [moveHighlighters]/[accentHighlighters]
 * for how each keeps its own decorations so redrawing one never touches the other's.
 */
class AllmanController(private val editor: Editor) : Disposable {

    /** The move mechanic's own highlighters (the dimming of text that visually moved down). */
    private val moveHighlighters = ArrayList<RangeHighlighter>()

    /** The move mechanic's own inlays (the phantom lines themselves). */
    private val moveInlays = ArrayList<Inlay<*>>()

    /** The colour mechanic's own highlighters (brace colour and shadow). */
    private val accentHighlighters = ArrayList<RangeHighlighter>()

    /** The colour mechanic's own inlays (declaration-line markers and end-of-block labels). */
    private val accentInlays = ArrayList<Inlay<*>>()

    /**
     * See [scanResult]. Kept until the text changes or the editor is disposed, which trades a
     * scan's worth of memory per open editor for not scanning the same text twice.
     */
    private var cachedScan: CachedScan? = null

    private val moveAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val accentAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    init {
        editor.putUserData(KEY, this)
        editor.document.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    // The move mechanic is what keeps the file reading as valid Allman style
                    // while typing, so it stays on the short timer. The colour mechanic draws
                    // strictly more decorations per accent on top of that -- colour, shadow,
                    // label, the nest and sibling-ordinal markers -- so it is the one worth
                    // delaying: a longer pause before it redraws means fewer full rebuilds of
                    // the more expensive half while someone is still actively typing.
                    scheduleMove(MOVE_REFRESH_DELAY_MS)
                    scheduleAccent(ACCENT_REFRESH_DELAY_MS)
                }
            },
            this,
        )
        schedule(IMMEDIATE_DELAY_MS)
    }

    /**
     * Refreshes both mechanics at the same delay. For an external trigger -- a settings change,
     * a newly opened editor -- where they should show up together instead of staggered. Typing
     * itself never calls this; the document listener above schedules the two halves separately.
     */
    fun schedule(delayMs: Int = MOVE_REFRESH_DELAY_MS) {
        scheduleMove(delayMs)
        scheduleAccent(delayMs)
    }

    private fun scheduleMove(delayMs: Int) {
        if (editor.isDisposed) {
            return
        }
        moveAlarm.cancelAllRequests()
        moveAlarm.addRequest({ refreshMove() }, delayMs)
    }

    private fun scheduleAccent(delayMs: Int) {
        if (editor.isDisposed) {
            return
        }
        accentAlarm.cancelAllRequests()
        accentAlarm.addRequest({ refreshAccent() }, delayMs)
    }

    /** The phantom lines and the dimming of the text they stand in for. */
    private fun refreshMove() {
        if (editor.isDisposed) {
            return
        }
        clearMove()

        val settings = AllmanSettings.getInstance()
        if (!settings.state.enabled || !settings.state.moveBraces) {
            return
        }
        val flavor = flavorFor() ?: return
        if (editor.document.textLength > MAX_FILE_CHARS) {
            return
        }

        val result = scanResult(settings, flavor)
        val accentStyle = BraceAccentStyle(editor, settings)
        paintMovedBraces(settings, result, phantomBraceStyles(accentStyle, result))
    }

    /** Brace colour and shadow, the end-of-block label, and the declaration-line markers. */
    private fun refreshAccent() {
        if (editor.isDisposed) {
            return
        }
        clearAccent()

        val settings = AllmanSettings.getInstance()
        if (!settings.state.enabled) {
            return
        }
        val flavor = flavorFor() ?: return
        if (editor.document.textLength > MAX_FILE_CHARS) {
            return
        }

        val result = scanResult(settings, flavor)
        val accentStyle = BraceAccentStyle(editor, settings)

        paintDeclarationLineMarkers(accentStyle, result)
        paintRealBraces(
            accentStyle,
            result,
            buildBraceStyles(accentStyle, result),
            dimmedBraceOffsets(settings, result),
        )
    }

    /**
     * The scan both halves read, reused when they ask for the same text twice.
     *
     * The two timers fire at different moments but almost always over the very same document:
     * type once, and the move half scans at 200ms while the colour half scans again at 600ms.
     * Scanning is linear but not free -- about 90ms at the [MAX_FILE_CHARS] ceiling -- and
     * doing it twice put that on the EDT twice for one keystroke.
     *
     * Only the [ScanResult] is cached, never the styling built from it: a scan is a pure
     * function of the text and the options, while a colour is sampled from the editor and can
     * change with no edit at all -- the ReSharper backend answering late, or the colour scheme
     * switching. Caching those too would freeze stale colours until the next keystroke.
     */
    private fun scanResult(settings: AllmanSettings, flavor: Flavor): ScanResult {
        val options = scanOptions(settings)
        val modificationStamp = editor.document.modificationStamp

        val cached = cachedScan
        if (cached != null && cached.modificationStamp == modificationStamp && cached.options == options) {
            return cached.result
        }

        val result = BraceScanner(
            editor.document.immutableCharSequence,
            flavor,
            options,
        ).scan()
        cachedScan = CachedScan(modificationStamp, options, result)
        return result
    }

    private class CachedScan(
        val modificationStamp: Long,
        val options: ScanOptions,
        val result: ScanResult,
    )

    /**
     * Styles for the braces a phantom line actually redraws, and no others.
     *
     * [PhantomLineRenderer] looks a style up by document offset for every character it paints,
     * and nothing else in this half uses one -- so styling every accent, as the colour half
     * must, would sample a colour per accent and then throw nearly all of them away. In a file
     * already written in Allman style there are no phantom lines at all, and every one of those
     * samples would be wasted.
     */
    private fun phantomBraceStyles(
        style: BraceAccentStyle,
        result: ScanResult,
    ): Map<Int, BraceStyle> {
        if (result.sites.isEmpty() || result.accents.isEmpty()) {
            return emptyMap()
        }

        val painted = HashSet<Int>()
        for (site in result.sites) {
            for (line in site.phantomLines) {
                for (offset in line.sourceOffset until line.sourceOffset + line.text.length) {
                    painted.add(offset)
                }
            }
        }

        val styles = HashMap<Int, BraceStyle>()
        for (accent in result.accents) {
            if (accent.offset !in painted) {
                continue
            }
            val braceStyle = style.styleFor(accent)
            if (braceStyle != null) {
                styles[accent.offset] = braceStyle
            }
        }
        return styles
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
                addHighlighter(site.dimStart, site.dimEnd, DIM_LAYER_OFFSET, dimAttributes, moveHighlighters)
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
                    accentHighlighters,
                )
                addShadow(accent.offset, braceStyle)
            }

            if (style.needsLabel(accent)) {
                addLabel(accent, style, braceStyle)
            }
        }
    }

    /**
     * The declaration-line markers in front of a block's own header: the sibling ordinal
     * `[N]`, the `nest` marker, or both together with `[N]` first, in the same colour as the
     * prefix on the block's end-of-block label. Placed right after the line's indent, so the
     * real declaration is pushed right rather than the marker landing in the margin.
     */
    private fun paintDeclarationLineMarkers(style: BraceAccentStyle, result: ScanResult) {
        val documentLength = editor.document.textLength

        for (accent in result.accents) {
            // Only the opening side has a declaration line to sit in front of; both accents of
            // a numbered block otherwise carry the same ordinal, which would draw it twice.
            if (!accent.isOpening) {
                continue
            }
            val markerText = declarationLineMarkerText(style, accent)
            if (markerText.isEmpty()) {
                continue
            }
            val headerOffset = headerOffsetOf(accent)
            if (headerOffset >= documentLength) {
                continue
            }
            val contentOffset = contentStartOffset(headerOffset)
            addDeclarationLineMarker(contentOffset, markerText, style.nestedMarkerColor(contentOffset))
        }
    }

    /**
     * `"[N] nest"`, `"[N]"`, `"nest"`, or `""`: the sibling ordinal and the `nest` marker, in
     * that order, for the declaration line -- the ordinal is shown even when the block is not
     * nested, since a top-level namespace or type can still have numbered siblings.
     */
    private fun declarationLineMarkerText(style: BraceAccentStyle, accent: BraceAccent): String {
        val ordinalText = style.siblingOrdinalText(accent)
        val nestText = if (style.needsNestedMarker(accent)) BraceAccentStyle.NESTED_MARKER_TEXT else ""
        if (ordinalText.isEmpty()) {
            return nestText
        }
        if (nestText.isEmpty()) {
            return ordinalText
        }
        return "$ordinalText $nestText"
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

    private fun addDeclarationLineMarker(offset: Int, text: String, color: Color) {
        val inlay = editor.inlayModel.addInlineElement(
            offset,
            /* relatesToPrecedingText = */ false,
            BlockLabelRenderer(
                segments = listOf(BlockLabelRenderer.Segment(text, color)),
                leadingSpaces = 0,
                trailingSpaces = 1,
            ),
        )
        if (inlay != null) {
            accentInlays.add(inlay)
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
        accentHighlighters.add(highlighter)
    }

    private fun addLabel(accent: BraceAccent, style: BraceAccentStyle, braceStyle: BraceStyle) {
        val inlay = editor.inlayModel.addInlineElement(
            accent.offset + 1,
            /* relatesToPrecedingText = */ true,
            BlockLabelRenderer(
                segments = endOfBlockLabelSegments(style, accent, braceStyle),
                leadingSpaces = LABEL_LEADING_SPACES,
                trailingSpaces = 0,
            ),
        )
        if (inlay != null) {
            accentInlays.add(inlay)
        }
    }

    /**
     * Up to three runs, left to right: the `[N] nest` marker (its own colour, shared with the
     * declaration-line marker), the bare construct word (`class`, `fun`, `ns`, ...) in the
     * editor's own keyword colour, and the symbol's own name in its own real accent colour --
     * so the label never paints `class` in the colour of a class name just because they sit
     * side by side in the same phantom text.
     */
    private fun endOfBlockLabelSegments(
        style: BraceAccentStyle,
        accent: BraceAccent,
        braceStyle: BraceStyle,
    ): List<BlockLabelRenderer.Segment> {
        val segments = ArrayList<BlockLabelRenderer.Segment>(LABEL_SEGMENT_CAPACITY)

        val markerText = endOfBlockConstructMarkerText(style, accent)
        if (markerText.isNotEmpty()) {
            // Sampled at the brace itself, right before the label: a closing brace inside a
            // disabled #if branch or unreachable code is already painted grey there, and the
            // marker should read the same way.
            segments.add(BlockLabelRenderer.Segment(markerText + " ", style.nestedMarkerColor(accent.offset)))
        }

        if (braceStyle.keywordText.isNotEmpty()) {
            val keywordText: String
            if (braceStyle.nameText.isEmpty()) {
                keywordText = braceStyle.keywordText
            } else {
                keywordText = braceStyle.keywordText + " "
            }
            segments.add(BlockLabelRenderer.Segment(keywordText, braceStyle.keywordColor))
        }

        if (braceStyle.nameText.isNotEmpty()) {
            segments.add(BlockLabelRenderer.Segment(braceStyle.nameText, braceStyle.nameColor))
        }

        return segments
    }

    /**
     * `"[N] nest"`, `"[N]"`, `"nest"`, or `""`: the sibling ordinal and the `nest` marker, in
     * that order -- the same ordering as [declarationLineMarkerText] uses on the opening side,
     * so a block reads the same number in both places.
     */
    private fun endOfBlockConstructMarkerText(style: BraceAccentStyle, accent: BraceAccent): String {
        val ordinalText = style.siblingOrdinalText(accent)
        val nestText = if (style.marksAsNested(accent)) BraceAccentStyle.NESTED_MARKER_TEXT else ""
        if (ordinalText.isEmpty()) {
            return nestText
        }
        if (nestText.isEmpty()) {
            return ordinalText
        }
        return "$ordinalText $nestText"
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
        highlighters: MutableList<RangeHighlighter>,
    ) {
        val highlighter = editor.markupModel.addRangeHighlighter(
            start,
            end,
            // above the syntax highlighting, otherwise the colour is overridden back
            HighlighterLayer.LAST + layerOffset,
            attributes,
            HighlighterTargetArea.EXACT_RANGE,
        )
        highlighters.add(highlighter)
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
            moveInlays.add(inlay)
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

    private fun clearMove() {
        removeHighlighters(moveHighlighters)
        disposeInlays(moveInlays)
    }

    private fun clearAccent() {
        removeHighlighters(accentHighlighters)
        disposeInlays(accentInlays)
    }

    private fun removeHighlighters(highlighters: MutableList<RangeHighlighter>) {
        val markupModel = editor.markupModel
        for (highlighter in highlighters) {
            if (highlighter.isValid) {
                markupModel.removeHighlighter(highlighter)
            }
        }
        highlighters.clear()
    }

    private fun disposeInlays(inlays: MutableList<Inlay<*>>) {
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
        cachedScan = null
        if (!editor.isDisposed) {
            clearMove()
            clearAccent()
        }
    }

    companion object {
        /**
         * How long a pause in typing has to be before the move mechanic redraws. Short, because
         * this is the mechanic that keeps the file reading as Allman style at all -- the file
         * would otherwise flash back to its real, non-Allman brace placement while someone is
         * still typing.
         */
        private const val MOVE_REFRESH_DELAY_MS = 200

        /**
         * How long a pause before the colour mechanic redraws -- noticeably longer than
         * [MOVE_REFRESH_DELAY_MS]. It is pure decoration on top of what the move mechanic
         * already drew, and it touches strictly more highlighters and inlays per accent
         * (colour, shadow, the end-of-block label, the nest and sibling-ordinal markers), so it
         * is the more expensive half to rebuild on every short pause. Delaying it further means
         * a fast typing burst rebuilds it only once it actually stops, instead of once per
         * pause in the middle of it.
         */
        private const val ACCENT_REFRESH_DELAY_MS = 600

        /** Also used by the service when a settings change has to show up at once, for both. */
        const val IMMEDIATE_DELAY_MS = 0

        /** How far above the syntax highlighting the dimming highlighter sits. */
        private const val DIM_LAYER_OFFSET = 100

        /** Brace accents also sit above syntax, but below the dimming. */
        private const val ACCENT_LAYER_OFFSET = 90

        /** The shadow paints before the text; the layer only keeps it out of others' way. */
        private const val SHADOW_LAYER_OFFSET = 80

        /** Gap after the brace so the end-of-block label does not stick to it. */
        private const val LABEL_LEADING_SPACES = 2

        /** The most segments an end-of-block label ever draws: the marker, the keyword, the name. */
        private const val LABEL_SEGMENT_CAPACITY = 3

        /** The scanner is linear, but a full timed rescan of a huge file is pointless. */
        private const val MAX_FILE_CHARS = 2_000_000

        val KEY: Key<AllmanController> = Key.create("allman.view.controller")
    }
}
