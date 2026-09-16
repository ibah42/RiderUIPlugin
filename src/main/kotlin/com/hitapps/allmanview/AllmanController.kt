package com.hitapps.allmanview

import com.hitapps.allmanview.scan.BraceScanner
import com.hitapps.allmanview.scan.Dialects
import com.hitapps.allmanview.scan.Flavor
import com.hitapps.allmanview.scan.PhantomSite
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

    private val dimHighlighters = ArrayList<RangeHighlighter>()
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

        val sites = BraceScanner(
            editor.document.immutableCharSequence,
            flavor,
            settings.state.fullAllman,
        ).scan()

        if (sites.isEmpty()) {
            return
        }

        val dimAttributes: TextAttributes?
        if (settings.state.dimOriginal) {
            dimAttributes = TextAttributes()
            dimAttributes.foregroundColor = dimColor()
        } else {
            dimAttributes = null
        }

        val documentLength = editor.document.textLength
        for (site in sites) {
            if (site.dimEnd > documentLength || site.anchorOffset > documentLength) {
                continue
            }
            if (dimAttributes != null) {
                dimOriginalText(site, dimAttributes)
            }
            addPhantomLines(site)
        }
    }

    private fun dimOriginalText(site: PhantomSite, attributes: TextAttributes) {
        val highlighter = editor.markupModel.addRangeHighlighter(
            site.dimStart,
            site.dimEnd,
            // выше синтаксической подсветки, иначе цвет перебьют обратно
            HighlighterLayer.LAST + DIM_LAYER_OFFSET,
            attributes,
            HighlighterTargetArea.EXACT_RANGE,
        )
        dimHighlighters.add(highlighter)
    }

    private fun addPhantomLines(site: PhantomSite) {
        val inlay = editor.inlayModel.addBlockElement(
            site.anchorOffset,
            /* relatesToPrecedingText = */ true,
            /* showAbove = */ false,
            /* priority = */ 0,
            PhantomLineRenderer(site.indent, site.phantomLines),
        )
        if (inlay != null) {
            inlays.add(inlay)
        }
    }

    /** Тот же приглушённый цвет, которым платформа рисует подсказки параметров. */
    private fun dimColor(): Color {
        val scheme = editor.colorsScheme
        val hintAttributes = scheme.getAttributes(
            DefaultLanguageHighlighterColors.INLINE_PARAMETER_HINT,
        )
        val hintColor = hintAttributes?.foregroundColor
        if (hintColor != null) {
            return hintColor
        }
        return ColorUtil.mix(scheme.defaultForeground, scheme.defaultBackground, DIM_BALANCE)
    }

    private fun clear() {
        val markupModel = editor.markupModel
        for (highlighter in dimHighlighters) {
            if (highlighter.isValid) {
                markupModel.removeHighlighter(highlighter)
            }
        }
        dimHighlighters.clear()

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

        /** Доля фона в приглушённом цвете, если у схемы нет цвета подсказок. */
        private const val DIM_BALANCE = 0.55

        /** Сканер линейный, но на гигантских файлах полный пересчёт по таймеру ни к чему. */
        private const val MAX_FILE_CHARS = 2_000_000

        val KEY: Key<AllmanController> = Key.create("allman.view.controller")
    }
}
