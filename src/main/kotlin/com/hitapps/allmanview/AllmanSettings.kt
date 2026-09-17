package com.hitapps.allmanview

import com.hitapps.allmanview.scan.BlockKind
import com.hitapps.allmanview.scan.Dialects
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/** Как оформлять скобки одного вида блоков. */
data class AccentConfig(
    val lightPercent: Int,
    val darkPercent: Int,
    val bold: Boolean,
    val shadow: Boolean,

    /** Насколько тень отходит от фона к серому: 0 — невидима, 100 — сплошной серый. */
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
        /** Общий выключатель. */
        var enabled: Boolean by property(true)

        /** true — разносим ещё и `} else {` на три строки; false — только висящую `{`. */
        var fullAllman: Boolean by property(true)

        /** Разносить `if (x) return;` на две строки. */
        var splitStatements: Boolean by property(true)

        /** Разворачивать `if (x) { Foo(); }` на четыре строки. */
        var expandInlineBlocks: Boolean by property(true)

        /** Гасить исходный текст, который визуально уехал вниз. */
        var dimOriginal: Boolean by property(true)

        /** Брать для гашения цвет подсказок IDE вместо своего процента. */
        var dimUseHintColor: Boolean by property(true)

        /** Насколько погашенный текст уведён к фону, если цвет подсказок не используется. */
        var dimPercent: Int by property(55)

        // --- скобки типов: class, struct, interface, enum, record ---

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

        // --- скобки функций, методов, конструкторов и лямбд ---

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

        /** Применять к любому текстовому файлу, игнорируя [extensions]. */
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

    /** Настройки одного вида блоков — чтобы не дублировать код на типы и функции. */
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
