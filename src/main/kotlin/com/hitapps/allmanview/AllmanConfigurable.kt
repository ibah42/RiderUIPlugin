package com.hitapps.allmanview

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel

class AllmanConfigurable : BoundConfigurable("Allman View") {

    override fun createPanel(): DialogPanel {
        val settings = AllmanSettings.getInstance()
        val config = settings.state
        return panel {
            row {
                checkBox("Показывать { на отдельной строке")
                    .bindSelected(config::enabled)
            }
            row {
                checkBox("Полный Allman: разносить } else { на три строки")
                    .bindSelected(config::fullAllman)
            }
            row {
                checkBox("Не трогать строку под кареткой")
                    .bindSelected(config::skipCaretLine)
                    .comment("Пока печатаешь в строке, скобка остаётся на месте — так каретку не выталкивает.")
            }
            row("Расширения файлов:") {
                textField()
                    .bindText({ config.extensions ?: AllmanSettings.DEFAULT_EXTENSIONS },
                        { config.extensions = it })
                    .columns(50)
            }
            row {
                comment(
                    "Плагин ничего не пишет в файл: исходная { прячется фолдингом, а новая строка — " +
                        "это block inlay. Копирование, поиск и git видят реальный текст.",
                )
            }
        }
    }

    override fun apply() {
        super.apply()
        AllmanService.getInstance().refreshAll()
    }
}

class ToggleAllmanAction : AnAction(), Toggleable {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val on = AllmanSettings.getInstance().state.enabled
        Toggleable.setSelected(e.presentation, on)
        e.presentation.text = if (on) "Скобки: Allman (вкл)" else "Скобки: Allman (выкл)"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val config = AllmanSettings.getInstance().state
        config.enabled = !config.enabled
        AllmanService.getInstance().refreshAll()
    }
}
