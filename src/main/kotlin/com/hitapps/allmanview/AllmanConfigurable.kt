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
            group("What to move") {
                row {
                    checkBox("Show { on its own line")
                        .bindSelected(config::enabled)
                }
                row {
                    checkBox("Full Allman: split } else { into three lines")
                        .bindSelected(config::fullAllman)
                }
                row {
                    checkBox("Split single-line if / for / foreach / while / using / lock")
                        .bindSelected(config::splitStatements)
                        .comment("if (x) return;  →  if (x) ⏎ return;")
                }
                row {
                    checkBox("Expand a single-line braced block")
                        .bindSelected(config::expandInlineBlocks)
                        .comment("if (x) { Foo(); }  →  if (x) ⏎ { ⏎ Foo(); ⏎ }")
                }
                row {
                    checkBox("Dim the original text")
                        .bindSelected(config::dimOriginal)
                        .comment("The real text stays where it is and is simply muted.")
                }
                row {
                    checkBox("Use the IDE hint colour")
                        .bindSelected(config::dimUseHintColor)
                        .comment("Turn off to set the dimming strength by hand.")
                }
                row("Dim towards background, %:") {
                    spinner(0..100, 5).bindIntValue(config::dimPercent)
                }
            }

            group("Type braces: class, struct, interface, enum, record") {
                row {
                    checkBox("Highlight")
                        .bindSelected(config::accentTypes)
                        .comment(
                            "The colour is sampled from the type name itself and pushed away " +
                                "from the background.",
                        )
                }
                row("Towards black on a light scheme, %:") {
                    spinner(0..100, 5).bindIntValue(config::typeLightPercent)
                }
                row("Towards white on a dark scheme, %:") {
                    spinner(0..100, 5).bindIntValue(config::typeDarkPercent)
                }
                row {
                    checkBox("Bold").bindSelected(config::typeBold)
                    checkBox("Shadow").bindSelected(config::typeShadow)
                }
                row("Shadow strength, %:") {
                    spinner(0..100, 5).bindIntValue(config::typeShadowPercent)
                        .comment("0 hides the shadow, 100 makes it solid grey.")
                }
                row("Shadow offset, px:") {
                    spinner(-8..8, 1).bindIntValue(config::typeShadowOffsetX)
                    label("X")
                    spinner(-8..8, 1).bindIntValue(config::typeShadowOffsetY)
                    label("Y")
                        .comment("X=1, Y=0 gives faux bold instead of depth.")
                }
                row {
                    checkBox("Label the end of the block")
                        .bindSelected(config::typeLabel)
                        .comment("}  class IosHttpClient")
                }
                row("From this block length, lines:") {
                    spinner(1..2000, 5).bindIntValue(config::typeLabelMinLines)
                }
                row("Label towards grey, %:") {
                    spinner(0..100, 5).bindIntValue(config::typeLabelGreyPercent)
                }
            }

            group("Function braces: methods and lambdas") {
                row {
                    checkBox("Highlight")
                        .bindSelected(config::accentFunctions)
                        .comment(
                            "A lambda has no name of its own, so the colour and the label come " +
                                "from the method it is passed to, or from the assignment target.",
                        )
                }
                row("Towards black on a light scheme, %:") {
                    spinner(0..100, 5).bindIntValue(config::functionLightPercent)
                }
                row("Towards white on a dark scheme, %:") {
                    spinner(0..100, 5).bindIntValue(config::functionDarkPercent)
                }
                row {
                    checkBox("Bold").bindSelected(config::functionBold)
                    checkBox("Shadow").bindSelected(config::functionShadow)
                }
                row("Shadow strength, %:") {
                    spinner(0..100, 5).bindIntValue(config::functionShadowPercent)
                        .comment("0 hides the shadow, 100 makes it solid grey.")
                }
                row("Shadow offset, px:") {
                    spinner(-8..8, 1).bindIntValue(config::functionShadowOffsetX)
                    label("X")
                    spinner(-8..8, 1).bindIntValue(config::functionShadowOffsetY)
                    label("Y")
                        .comment("X=1, Y=0 gives faux bold instead of depth.")
                }
                row {
                    checkBox("Label the end of the block")
                        .bindSelected(config::functionLabel)
                        .comment("}  fun HandleNativeResult")
                }
                row("From this block length, lines:") {
                    spinner(1..2000, 5).bindIntValue(config::functionLabelMinLines)
                }
                row("Label towards grey, %:") {
                    spinner(0..100, 5).bindIntValue(config::functionLabelGreyPercent)
                }
            }

            group("Which files") {
                row {
                    checkBox("All text files")
                        .bindSelected(config::allFiles)
                        .comment("When on, the extension list below is ignored.")
                }
                row("Extensions:") {
                    textArea()
                        .bindText(
                            { config.extensions ?: Dialects.DEFAULT_EXTENSIONS },
                            { config.extensions = it },
                        )
                        .rows(4)
                        .align(AlignX.FILL)
                        .comment(
                            "Separated by comma, space or newline. The dot and the star are optional.",
                        )
                }
                row {
                    button("Restore the default list") {
                        config.extensions = Dialects.DEFAULT_EXTENSIONS
                        reset()
                    }
                }
            }

            row {
                comment(
                    "String literal parsing adapts to the language: C# (@\"\", \"\"\"\"\"\", ${'$'}\"\"), " +
                        "C/C++ (R\"()\", 1'000'000), JVM and Swift (text blocks \"\"\"\"\"\"), " +
                        "JS/TS/Go (`templates`). An unknown extension is parsed by the generic " +
                        "rules, which are enough for any language with curly blocks.",
                )
            }
            row {
                comment(
                    "Nothing is written to the file: a phantom line is a block inlay and the " +
                        "caret never enters it. Copying, search and git see the real text.",
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
            event.presentation.text = "Allman Braces (on)"
        } else {
            event.presentation.text = "Allman Braces (off)"
        }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val config = AllmanSettings.getInstance().state
        config.enabled = !config.enabled
        AllmanService.getInstance().refreshAll()
    }
}
