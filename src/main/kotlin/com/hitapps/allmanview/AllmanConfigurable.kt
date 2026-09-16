package com.hitapps.allmanview

import com.hitapps.allmanview.scan.Dialects
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows

class AllmanConfigurable : BoundConfigurable("Allman View") {

    override fun createPanel(): DialogPanel {
        val config = AllmanSettings.getInstance().state
        return panel {
            group("Что переносить") {
                row {
                    checkBox("Показывать { на отдельной строке")
                        .bindSelected(config::enabled)
                }
                row {
                    checkBox("Полный Allman: разносить } else { на три строки")
                        .bindSelected(config::fullAllman)
                }
                row {
                    checkBox("Разносить однострочные if / for / foreach / while / using / lock")
                        .bindSelected(config::splitStatements)
                        .comment("if (x) return;  →  if (x) ⏎ return;")
                }
                row {
                    checkBox("Разворачивать однострочный блок в скобках")
                        .bindSelected(config::expandInlineBlocks)
                        .comment("if (x) { Foo(); }  →  if (x) ⏎ { ⏎ Foo(); ⏎ }")
                }
                row {
                    checkBox("Гасить исходный текст серым")
                        .bindSelected(config::dimOriginal)
                        .comment(
                            "Реальный текст остаётся на месте — он просто приглушается, " +
                                "как подсказки параметров.",
                        )
                }
            }

            group("В каких файлах") {
                row {
                    checkBox("Во всех текстовых файлах")
                        .bindSelected(config::allFiles)
                        .comment("Если включено, список расширений ниже игнорируется.")
                }
                row("Расширения:") {
                    textArea()
                        .bindText(
                            { config.extensions ?: Dialects.DEFAULT_EXTENSIONS },
                            { config.extensions = it },
                        )
                        .rows(4)
                        .align(AlignX.FILL)
                        .comment(
                            "Через запятую, пробел или с новой строки. Точку и звёздочку можно не писать.",
                        )
                }
                row {
                    button("Вернуть список по умолчанию") {
                        config.extensions = Dialects.DEFAULT_EXTENSIONS
                        reset()
                    }
                }
            }

            row {
                comment(
                    "Разбор строковых литералов подстраивается под язык: C# (@\"\", \"\"\"\"\"\", ${'$'}\"\"), " +
                        "C/C++ (R\"()\", 1'000'000), JVM и Swift (текстовые блоки \"\"\"\"\"\"), " +
                        "JS/TS/Go (`шаблоны`). Незнакомое расширение разбирается общими правилами — " +
                        "их хватает для любого языка с фигурными блоками.",
                )
            }
            row {
                comment(
                    "Плагин ничего не пишет в файл: фантомная строка — это block inlay, " +
                        "каретка по ней не ходит. Копирование, поиск и git видят реальный текст.",
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

    override fun update(event: AnActionEvent) {
        val isEnabled = AllmanSettings.getInstance().state.enabled
        Toggleable.setSelected(event.presentation, isEnabled)

        if (isEnabled) {
            event.presentation.text = "Скобки: Allman (вкл)"
        } else {
            event.presentation.text = "Скобки: Allman (выкл)"
        }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val config = AllmanSettings.getInstance().state
        config.enabled = !config.enabled
        AllmanService.getInstance().refreshAll()
    }
}
