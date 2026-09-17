package com.hitapps.allmanview

import com.hitapps.allmanview.scan.Dialects
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.builder.selected

/**
 * The panel has two independent halves, each behind its own master checkbox.
 *
 * "Move braces down" owns the phantom lines and the dimming of the text they stand in for.
 * "Accent braces" owns the colour, the weight, the shadow and the end-of-block label of the
 * three kinds of block that get one -- types, functions and namespaces -- plus the nested
 * marker shared by all three. Turning one master off leaves the other running; the topmost
 * checkbox turns off the plugin as a whole.
 *
 * Every subsection below a master checkbox is a bold, borderless heading (`label(...).bold()`)
 * followed by a `rowsRange { ... }.enabledIf(...)`: a row disabled by an ancestor stays disabled
 * regardless of its own predicate, so the cascade — plugin → mechanic → per-kind toggle → the
 * one specific checkbox a spinner belongs to — greys out correctly at every level without a
 * separate bordered box for each one.
 */
class AllmanConfigurable : BoundConfigurable("Allman View") {

    override fun createPanel(): DialogPanel {
        val config = AllmanSettings.getInstance().state
        return panel {
            lateinit var pluginEnabled: Cell<JBCheckBox>
            row {
                pluginEnabled = checkBox("Allman View enabled")
                    .bindSelected(config::enabled)
                    .comment(
                        "The master switch for both mechanics below. With it off the editor " +
                            "shows the file exactly as it is on disk.",
                    )
            }

            // Both groups grey out with the master switch, so it is obvious what it controls.
            group("Move braces down") {
                lateinit var moveBraces: Cell<JBCheckBox>
                row {
                    moveBraces = checkBox("Draw the phantom lines")
                        .bindSelected(config::moveBraces)
                        .comment("if (x) {  →  the { is shown on a line of its own underneath.")
                }

                row {
                    label("Which lines to split").bold()
                }
                rowsRange {
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
                }.enabledIf(moveBraces.selected)

                row {
                    label("Dimming").bold()
                }
                rowsRange {
                    lateinit var dimOriginal: Cell<JBCheckBox>
                    row {
                        dimOriginal = checkBox("Dim the original text")
                            .bindSelected(config::dimOriginal)
                            .comment("The real text stays where it is and is simply muted.")
                    }
                    rowsRange {
                        row {
                            checkBox("Use the IDE hint colour")
                                .bindSelected(config::dimUseHintColor)
                                .comment("Turn off to set the dimming strength by hand.")
                        }
                        row("Dim towards background, %:") {
                            spinner(0..100, 5).bindIntValue(config::dimPercent)
                        }
                    }.enabledIf(dimOriginal.selected)
                }.enabledIf(moveBraces.selected)
            }.enabledIf(pluginEnabled.selected)

            group("Accent braces") {
                lateinit var accentBraces: Cell<JBCheckBox>
                row {
                    accentBraces = checkBox("Colour the braces of types and functions")
                        .bindSelected(config::accentBraces)
                        .comment(
                            "Colour, shadow and the end-of-block label. Works on its own, " +
                                "whether or not the braces are moved down.",
                        )
                }

                // Marking a block as nested is not a per-kind setting -- it applies to every
                // kind below -- so it sits above them all, under the same master checkbox.

                row {
                    label("Nested blocks").bold()
                }
                rowsRange {
                    lateinit var nestedMarkerEnabled: Cell<JBCheckBox>
                    row {
                        nestedMarkerEnabled = checkBox("Mark a block nested inside one of its own kind")
                            .bindSelected(config::nestedMarkerEnabled)
                            .comment(
                                "\"nest\" before its declaration and at the start of its " +
                                    "end-of-block label, always shown regardless of the " +
                                    "block's own length.",
                            )
                    }
                    row("\"nest\" marker towards grey, %:") {
                        spinner(0..100, 5).bindIntValue(config::nestedLabelGreyPercent)
                            .comment("Based on the editor's keyword colour, not the block's own accent.")
                    }.enabledIf(nestedMarkerEnabled.selected)
                }.enabledIf(accentBraces.selected)

                separator()

                rowsRange {
                    lateinit var accentTypes: Cell<JBCheckBox>
                    row {
                        accentTypes = checkBox("Types: class, struct, interface, enum, record")
                            .bindSelected(config::accentTypes)
                    }

                    rowsRange {
                        row {
                            label("Brace").bold()
                        }
                        row("Towards black on a light scheme, %:") {
                            spinner(0..100, 5).bindIntValue(config::typeLightPercent)
                        }
                        row("Towards white on a dark scheme, %:") {
                            spinner(0..100, 5).bindIntValue(config::typeDarkPercent)
                        }
                        row {
                            checkBox("Bold").bindSelected(config::typeBold)
                        }

                        row {
                            label("Shadow").bold()
                        }
                        lateinit var typeShadow: Cell<JBCheckBox>
                        row {
                            typeShadow = checkBox("Shadow").bindSelected(config::typeShadow)
                        }
                        rowsRange {
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
                        }.enabledIf(typeShadow.selected)

                        row {
                            label("End-of-block label").bold()
                        }
                        lateinit var typeLabel: Cell<JBCheckBox>
                        row {
                            typeLabel = checkBox("Label the end of the block")
                                .bindSelected(config::typeLabel)
                                .comment("}  class IosHttpClient")
                        }
                        rowsRange {
                            row("From this block length, lines:") {
                                spinner(1..2000, 5).bindIntValue(config::typeLabelMinLines)
                            }
                            row("Label towards grey, %:") {
                                spinner(0..100, 5).bindIntValue(config::typeLabelGreyPercent)
                            }
                        }.enabledIf(typeLabel.selected)
                    }.enabledIf(accentTypes.selected)
                }.enabledIf(accentBraces.selected)

                separator()

                rowsRange {
                    lateinit var accentFunctions: Cell<JBCheckBox>
                    row {
                        accentFunctions = checkBox("Functions: methods and lambdas")
                            .bindSelected(config::accentFunctions)
                            .comment(
                                "A lambda has no name of its own, so the colour and the label " +
                                    "come from the method it is passed to, or from the " +
                                    "assignment target.",
                            )
                    }

                    rowsRange {
                        row {
                            label("Brace").bold()
                        }
                        row("Towards black on a light scheme, %:") {
                            spinner(0..100, 5).bindIntValue(config::functionLightPercent)
                        }
                        row("Towards white on a dark scheme, %:") {
                            spinner(0..100, 5).bindIntValue(config::functionDarkPercent)
                        }
                        row {
                            checkBox("Bold").bindSelected(config::functionBold)
                        }

                        row {
                            label("Shadow").bold()
                        }
                        lateinit var functionShadow: Cell<JBCheckBox>
                        row {
                            functionShadow = checkBox("Shadow").bindSelected(config::functionShadow)
                        }
                        rowsRange {
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
                        }.enabledIf(functionShadow.selected)

                        row {
                            label("End-of-block label").bold()
                        }
                        lateinit var functionLabel: Cell<JBCheckBox>
                        row {
                            functionLabel = checkBox("Label the end of the block")
                                .bindSelected(config::functionLabel)
                                .comment("}  fun HandleNativeResult")
                        }
                        rowsRange {
                            row("From this block length, lines:") {
                                spinner(1..2000, 5).bindIntValue(config::functionLabelMinLines)
                            }
                            row("Label towards grey, %:") {
                                spinner(0..100, 5).bindIntValue(config::functionLabelGreyPercent)
                            }
                        }.enabledIf(functionLabel.selected)
                    }.enabledIf(accentFunctions.selected)
                }.enabledIf(accentBraces.selected)

                separator()

                rowsRange {
                    lateinit var accentNamespaces: Cell<JBCheckBox>
                    row {
                        accentNamespaces = checkBox("Namespaces")
                            .bindSelected(config::accentNamespaces)
                            .comment(
                                "A namespace has no name in its label and no minimum length: " +
                                    "its closing brace is the one furthest from its declaration, " +
                                    "so it is always labelled.",
                            )
                    }

                    rowsRange {
                        row {
                            label("Brace").bold()
                        }
                        row("Towards black on a light scheme, %:") {
                            spinner(0..100, 5).bindIntValue(config::namespaceLightPercent)
                        }
                        row("Towards white on a dark scheme, %:") {
                            spinner(0..100, 5).bindIntValue(config::namespaceDarkPercent)
                                .comment(
                                    "With no name to sample, the colour starts from the " +
                                        "editor's keyword colour.",
                                )
                        }
                        row {
                            checkBox("Bold").bindSelected(config::namespaceBold)
                        }

                        row {
                            label("Shadow").bold()
                        }
                        lateinit var namespaceShadow: Cell<JBCheckBox>
                        row {
                            namespaceShadow = checkBox("Shadow").bindSelected(config::namespaceShadow)
                        }
                        rowsRange {
                            row("Shadow strength, %:") {
                                spinner(0..100, 5).bindIntValue(config::namespaceShadowPercent)
                                    .comment("0 hides the shadow, 100 makes it solid grey.")
                            }
                            row("Shadow offset, px:") {
                                spinner(-8..8, 1).bindIntValue(config::namespaceShadowOffsetX)
                                label("X")
                                spinner(-8..8, 1).bindIntValue(config::namespaceShadowOffsetY)
                                label("Y")
                                    .comment("X=1, Y=0 gives faux bold instead of depth.")
                            }
                        }.enabledIf(namespaceShadow.selected)

                        row {
                            label("End-of-block label").bold()
                        }
                        lateinit var namespaceLabel: Cell<JBCheckBox>
                        row {
                            namespaceLabel = checkBox("Label the end of the block")
                                .bindSelected(config::namespaceLabel)
                                .comment("}  ns")
                        }
                        row("Label towards grey, %:") {
                            spinner(0..100, 5).bindIntValue(config::namespaceLabelGreyPercent)
                        }.enabledIf(namespaceLabel.selected)
                    }.enabledIf(accentNamespaces.selected)
                }.enabledIf(accentBraces.selected)
            }.enabledIf(pluginEnabled.selected)

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
            event.presentation.text = "Allman View (on)"
        } else {
            event.presentation.text = "Allman View (off)"
        }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val config = AllmanSettings.getInstance().state
        config.enabled = !config.enabled
        AllmanService.getInstance().refreshAll()
    }
}
