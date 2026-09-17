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
import com.intellij.ui.ColorUtil
import com.intellij.util.Alarm
import java.awt.Color

/**
 * Живёт на один редактор.
 *
 * Документ не трогаем и фолдингом ничего не прячем: исходный текст остаётся на месте
 * и гасится подсветкой, а фантомные строки — block inlay. За счёт этого нет ни конфликтов
 * с фолдингом ReSharper, ни выталкивания каретки при наборе.
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
        val braceStyles = buildAccents(accentStyle, result)
        paintSites(settings, result, braceStyles)
        paintRealBraces(accentStyle, result, braceStyles)
    }

    private fun scanOptions(settings: AllmanSettings): ScanOptions {
        return ScanOptions(
            fullAllman = settings.state.fullAllman,
            splitStatements = settings.state.splitStatements,
            expandInlineBlocks = settings.state.expandInlineBlocks,
            accentTypes = settings.state.accentTypes,
            accentFunctions = settings.state.accentFunctions,
        )
    }

    /** Offset скобки → как её оформлять. */
    private fun buildAccents(
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

    private fun paintSites(
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
     * Реальные `{` и `}` типов и функций: цвет, тень и подпись длинного блока.
     *
     * Скобку, которая уже погашена как уехавшая вниз, не красим: там усиление
     * противоречило бы гашению. Её роль играет фантом — он красится в рендерере.
     * Подпись и тень при этом ставятся всё равно: они привязаны к реальной скобке.
     */
    private fun paintRealBraces(
        style: BraceAccentStyle,
        result: ScanResult,
        braceStyles: Map<Int, BraceStyle>,
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

            if (!isDimmed(result, accent)) {
                addHighlighter(
                    accent.offset,
                    accent.offset + 1,
                    ACCENT_LAYER_OFFSET,
                    braceStyle.attributes,
                )
                addShadow(accent.offset, braceStyle)
            }

            if (style.needsLabel(accent)) {
                addLabel(accent.offset, braceStyle)
            }
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
        )
        ownHighlighters.add(highlighter)
    }

    private fun addLabel(braceOffset: Int, braceStyle: BraceStyle) {
        val inlay = editor.inlayModel.addInlineElement(
            braceOffset + 1,
            /* relatesToPrecedingText = */ true,
            BlockLabelRenderer(braceStyle.labelText, braceStyle.labelColor),
        )
        if (inlay != null) {
            inlays.add(inlay)
        }
    }

    private fun isDimmed(result: ScanResult, accent: BraceAccent): Boolean {
        if (!AllmanSettings.getInstance().state.dimOriginal) {
            return false
        }
        for (site in result.sites) {
            if (accent.offset >= site.dimStart && accent.offset < site.dimEnd) {
                return true
            }
        }
        return false
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
            // выше синтаксической подсветки, иначе цвет перебьют обратно
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

    /** По умолчанию — тот же приглушённый цвет, которым платформа рисует подсказки параметров. */
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

        val balance = settings.state.dimPercent.coerceIn(0, MAX_PERCENT) / MAX_PERCENT.toDouble()
        return ColorUtil.mix(scheme.defaultForeground, scheme.defaultBackground, balance)
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

        // Диалект нужен только для границ строковых литералов; незнакомое
        // расширение разбирается как GENERIC и на любом C-подобном языке работает.
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
        private const val IMMEDIATE_DELAY_MS = 0

        /** Насколько выше синтаксической подсветки ставим гасящий хайлайтер. */
        private const val DIM_LAYER_OFFSET = 100

        /** Усиление скобок — тоже выше синтаксиса, но ниже гашения. */
        private const val ACCENT_LAYER_OFFSET = 90

        /** Тень рисуется до текста, слой нужен лишь чтобы не спорить с чужими рендерерами. */
        private const val SHADOW_LAYER_OFFSET = 80

        private const val MAX_PERCENT = 100

        /** Сканер линейный, но на гигантских файлах полный пересчёт по таймеру ни к чему. */
        private const val MAX_FILE_CHARS = 2_000_000

        val KEY: Key<AllmanController> = Key.create("allman.view.controller")
    }
}
