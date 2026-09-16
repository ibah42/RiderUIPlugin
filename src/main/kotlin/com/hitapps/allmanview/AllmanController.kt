package com.hitapps.allmanview

import com.hitapps.allmanview.scan.BraceScanner
import com.hitapps.allmanview.scan.Flavor
import com.hitapps.allmanview.scan.PhantomSite
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.FoldingModelEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.util.Alarm

/**
 * Живёт на один редактор. Держит созданные fold-регионы и инлеи, пересобирает их по таймеру.
 */
class AllmanController(private val editor: Editor) : Disposable {

    private val folds = ArrayList<FoldRegion>()
    private val inlays = ArrayList<Inlay<*>>()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    init {
        editor.putUserData(KEY, this)
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = schedule()
        }, this)
        editor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) = schedule()
        }, this)
        schedule()
    }

    fun schedule(delayMs: Int = DELAY_MS) {
        if (editor.isDisposed) return
        alarm.cancelAllRequests()
        alarm.addRequest({ refresh() }, delayMs)
    }

    private fun refresh() {
        if (editor.isDisposed) return

        val settings = AllmanSettings.getInstance()
        val flavor = flavorFor()
        val sites: List<PhantomSite> =
            if (settings.state.enabled && flavor != null) {
                val raw = BraceScanner(
                    editor.document.immutableCharSequence,
                    flavor,
                    settings.state.fullAllman,
                ).scan()
                if (settings.state.skipCaretLine) raw.filterNot { onCaretLine(it) } else raw
            } else {
                emptyList()
            }

        val foldingModel = editor.foldingModel as? FoldingModelEx ?: return
        val accepted = ArrayList<PhantomSite>(sites.size)

        foldingModel.runBatchFoldingOperation {
            for (region in folds) {
                if (region.isValid) foldingModel.removeFoldRegion(region)
            }
            folds.clear()

            for (site in sites) {
                // null означает пересечение с чужим регионом (например, фолдингом ReSharper) —
                // тогда просто не трогаем эту строку, чтобы не показать скобку дважды.
                val region = foldingModel.createFoldRegion(
                    site.hideStart, site.hideEnd, "", null, true,
                ) ?: continue
                region.setGutterMarkEnabledForSingleLine(false)
                region.isExpanded = false
                folds += region
                accepted += site
            }
        }

        for (inlay in inlays) Disposer.dispose(inlay)
        inlays.clear()

        val inlayModel = editor.inlayModel
        val textLength = editor.document.textLength
        for (site in accepted) {
            if (site.anchorOffset > textLength) continue
            val inlay = inlayModel.addBlockElement(
                site.anchorOffset,
                /* relatesToPrecedingText = */ true,
                /* showAbove = */ false,
                /* priority = */ 0,
                PhantomLineRenderer(site.indent, site.phantomLines),
            ) ?: continue
            inlays += inlay
        }
    }

    /**
     * Строку под кареткой не трогаем: иначе скобка уезжает прямо в момент набора,
     * а каретку выталкивает из схлопнутого региона.
     */
    private fun onCaretLine(site: PhantomSite): Boolean {
        val document = editor.document
        if (site.hideStart > document.textLength) return true
        val line = document.getLineNumber(site.hideStart)
        return editor.caretModel.allCarets.any { it.logicalPosition.line == line }
    }

    private fun flavorFor(): Flavor? {
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
        val ext = file.extension?.lowercase() ?: return null
        if (ext !in AllmanSettings.getInstance().extensionSet()) return null
        return when (ext) {
            "cs" -> Flavor.CSHARP
            "cpp", "cc", "cxx", "c", "h", "hpp", "hlsl", "cginc", "compute", "shader" -> Flavor.CPP
            else -> Flavor.GENERIC
        }
    }

    override fun dispose() {
        editor.putUserData(KEY, null)
        if (!editor.isDisposed) {
            val foldingModel = editor.foldingModel as? FoldingModelEx
            foldingModel?.runBatchFoldingOperation {
                for (region in folds) if (region.isValid) foldingModel.removeFoldRegion(region)
            }
        }
        folds.clear()
        for (inlay in inlays) Disposer.dispose(inlay)
        inlays.clear()
    }

    companion object {
        private const val DELAY_MS = 200
        val KEY: Key<AllmanController> = Key.create("allman.view.controller")
    }
}
