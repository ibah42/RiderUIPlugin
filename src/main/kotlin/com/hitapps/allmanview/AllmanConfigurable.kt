package com.hitapps.allmanview

import com.hitapps.allmanview.scan.Dialects
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntValue
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
                            "Реальный текст остаётся на месте — он просто приглушается.",
                        )
                }
                row {
                    checkBox("Цветом подсказок IDE")
                        .bindSelected(config::dimUseHintColor)
                        .comment("Выключи, чтобы задать степень гашения вручную.")
                }
                row("Гасить к фону, %:") {
                    spinner(0..100, 5).bindIntValue(config::dimPercent)
                }
            }

            group("Скобки типов: class, struct, interface, enum, record") {
                row {
                    checkBox("Выделять")
                        .bindSelected(config::accentTypes)
                        .comment(
                            "Цвет берётся с имени самого типа и уводится в сторону от фона.",
                        )
                }
                row("К чёрному на светлой схеме, %:") {
                    spinner(0..100, 5).bindIntValue(config::typeLightPercent)
                }
                row("К белому на тёмной схеме, %:") {
                    spinner(0..100, 5).bindIntValue(config::typeDarkPercent)
                }
                row {
                    checkBox("Жирным").bindSelected(config::typeBold)
                    checkBox("С тенью").bindSelected(config::typeShadow)
                }
                row("Насыщенность тени, %:") {
                    spinner(0..100, 5).bindIntValue(config::typeShadowPercent)
                        .comment("0 — тени не видно, 100 — сплошной серый.")
                }
                row("Сдвиг тени, px:") {
                    spinner(-4..4, 1).bindIntValue(config::typeShadowOffsetX)
                    label("по X")
                    spinner(-4..4, 1).bindIntValue(config::typeShadowOffsetY)
                    label("по Y")
                        .comment("X=1, Y=0 даёт псевдо-жирный вместо объёмной тени.")
                }
                row {
                    checkBox("Подписывать конец блока")
                        .bindSelected(config::typeLabel)
                        .comment("}  class IosHttpClient")
                }
                row("Начиная с длины блока, строк:") {
                    spinner(1..2000, 5).bindIntValue(config::typeLabelMinLines)
                }
                row("Подпись к серому, %:") {
                    spinner(0..100, 5).bindIntValue(config::typeLabelGreyPercent)
                }
            }

            group("Скобки функций, методов и лямбд") {
                row {
                    checkBox("Выделять")
                        .bindSelected(config::accentFunctions)
                        .comment(
                            "У лямбды своего имени нет — цвет и подпись берутся у метода, " +
                                "которому она передана, либо у цели присваивания.",
                        )
                }
                row("К чёрному на светлой схеме, %:") {
                    spinner(0..100, 5).bindIntValue(config::functionLightPercent)
                }
                row("К белому на тёмной схеме, %:") {
                    spinner(0..100, 5).bindIntValue(config::functionDarkPercent)
                }
                row {
                    checkBox("Жирным").bindSelected(config::functionBold)
                    checkBox("С тенью").bindSelected(config::functionShadow)
                }
                row("Насыщенность тени, %:") {
                    spinner(0..100, 5).bindIntValue(config::functionShadowPercent)
                        .comment("0 — тени не видно, 100 — сплошной серый.")
                }
                row("Сдвиг тени, px:") {
                    spinner(-4..4, 1).bindIntValue(config::functionShadowOffsetX)
                    label("по X")
                    spinner(-4..4, 1).bindIntValue(config::functionShadowOffsetY)
                    label("по Y")
                        .comment("X=1, Y=0 даёт псевдо-жирный вместо объёмной тени.")
                }
                row {
                    checkBox("Подписывать конец блока")
                        .bindSelected(config::functionLabel)
                        .comment("}  fun HandleNativeResult")
                }
                row("Начиная с длины блока, строк:") {
                    spinner(1..2000, 5).bindIntValue(config::functionLabelMinLines)
                }
                row("Подпись к серому, %:") {
                    spinner(0..100, 5).bindIntValue(config::functionLabelGreyPercent)
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
