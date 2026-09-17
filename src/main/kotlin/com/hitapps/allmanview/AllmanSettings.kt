package com.hitapps.allmanview

import com.hitapps.allmanview.scan.BlockKind
import com.hitapps.allmanview.scan.Dialects
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/** How braces of one kind of block should look. */
data class AccentConfig(
    val lightPercent: Int,
    val darkPercent: Int,
    val bold: Boolean,
    val shadow: Boolean,

    /** How far the shadow moves from the background towards grey: 0 invisible, 100 solid grey. */
    val shadowPercent: Int,
    val shadowOffsetX: Int,
    val shadowOffsetY: Int,
    val label: Boolean,
    val labelMinLines: Int,
    val labelGreyPercent: Int,
)

@Service(Service.Level.APP)
@State(name = "AllmanView", storages = [Storage("allman-view.xml")])
class AllmanSettings : SimplePersistentStateComponent<AllmanSettings.Config>(Config()) {

    class Config : BaseState() {
        /** Master switch. */
        var enabled: Boolean by property(true)

        /** true also splits `} else {` into three lines, not just the hanging `{`. */
        var fullAllman: Boolean by property(true)

        /** Split `if (x) return;` into two lines. */
        var splitStatements: Boolean by property(true)

        /** Expand `if (x) { Foo(); }` into four lines. */
        var expandInlineBlocks: Boolean by property(true)

        /** Dim the original text that visually moved down. */
        var dimOriginal: Boolean by property(true)

        /** Use the IDE hint colour for dimming instead of an explicit percentage. */
        var dimUseHintColor: Boolean by property(true)

        /** How far dimmed text moves towards the background when the hint colour is not used. */
        var dimPercent: Int by property(55)

        // --- type braces: class, struct, interface, enum, record ---

        var accentTypes: Boolean by property(true)
        var typeLightPercent: Int by property(50)
        var typeDarkPercent: Int by property(50)
        var typeBold: Boolean by property(true)
        var typeShadow: Boolean by property(true)
        var typeShadowPercent: Int by property(45)
        var typeShadowOffsetX: Int by property(1)
        var typeShadowOffsetY: Int by property(1)
        var typeLabel: Boolean by property(true)
        var typeLabelMinLines: Int by property(50)
        var typeLabelGreyPercent: Int by property(50)

        // --- function braces: methods, constructors and lambdas ---

        var accentFunctions: Boolean by property(true)
        var functionLightPercent: Int by property(50)
        var functionDarkPercent: Int by property(50)
        var functionBold: Boolean by property(true)
        var functionShadow: Boolean by property(true)
        var functionShadowPercent: Int by property(45)
        var functionShadowOffsetX: Int by property(1)
        var functionShadowOffsetY: Int by property(1)
        var functionLabel: Boolean by property(true)
        var functionLabelMinLines: Int by property(30)
        var functionLabelGreyPercent: Int by property(50)

        /** Apply to any text file, ignoring [extensions]. */
        var allFiles: Boolean by property(false)

        var extensions: String? by string(Dialects.DEFAULT_EXTENSIONS)
    }

    fun extensionSet(): Set<String> {
        val configured = state.extensions ?: Dialects.DEFAULT_EXTENSIONS
        val result = HashSet<String>()

        for (token in configured.split(',', ' ', ';', '\n')) {
            val normalized = token.trim().removePrefix(".").lowercase()
            if (normalized.isNotEmpty()) {
                result.add(normalized)
            }
        }
        return result
    }

    fun appliesTo(extension: String?): Boolean {
        if (state.allFiles) {
            return true
        }
        if (extension == null) {
            return false
        }
        return extension.lowercase() in extensionSet()
    }

    /** Settings for one kind of block, so types and functions share the same code path. */
    fun accentFor(kind: BlockKind): AccentConfig? {
        if (kind == BlockKind.TYPE && state.accentTypes) {
            return AccentConfig(
                lightPercent = state.typeLightPercent,
                darkPercent = state.typeDarkPercent,
                bold = state.typeBold,
                shadow = state.typeShadow,
                shadowPercent = state.typeShadowPercent,
                shadowOffsetX = state.typeShadowOffsetX,
                shadowOffsetY = state.typeShadowOffsetY,
                label = state.typeLabel,
                labelMinLines = state.typeLabelMinLines,
                labelGreyPercent = state.typeLabelGreyPercent,
            )
        }
        if (kind == BlockKind.FUNCTION && state.accentFunctions) {
            return AccentConfig(
                lightPercent = state.functionLightPercent,
                darkPercent = state.functionDarkPercent,
                bold = state.functionBold,
                shadow = state.functionShadow,
                shadowPercent = state.functionShadowPercent,
                shadowOffsetX = state.functionShadowOffsetX,
                shadowOffsetY = state.functionShadowOffsetY,
                label = state.functionLabel,
                labelMinLines = state.functionLabelMinLines,
                labelGreyPercent = state.functionLabelGreyPercent,
            )
        }
        return null
    }

    companion object {
        fun getInstance(): AllmanSettings {
            return service()
        }
    }
}
