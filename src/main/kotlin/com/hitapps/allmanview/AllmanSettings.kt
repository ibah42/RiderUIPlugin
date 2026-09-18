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

    /** Colour, weight and shadow of the braces themselves -- independent of [showLabel]. */
    val showBraces: Boolean,
    val showLabel: Boolean,
    val labelMinLines: Int,
    val labelGreyPercent: Int,
)

@Service(Service.Level.APP)
@State(name = "AllmanView", storages = [Storage("allman-view.xml")])
class AllmanSettings : SimplePersistentStateComponent<AllmanSettings.Config>(Config()) {

    class Config : BaseState() {
        /** Master switch: with it off the plugin draws nothing at all. */
        var enabled: Boolean by property(true)

        /**
         * The move mechanic: phantom lines and the dimming of the text they replace.
         * Independent of [accentBraces] — either half can run on its own.
         */
        var moveBraces: Boolean by property(true)

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

        /**
         * The colour mechanic: brace accent, shadow and the end-of-block label.
         * Independent of [moveBraces] — either half can run on its own.
         */
        var accentBraces: Boolean by property(true)

        /** Master switch for the `nest` marker, independent of the colour settings below. */
        var nestedMarkerEnabled: Boolean by property(true)

        /**
         * Label a nested block whatever its length, ignoring the per-kind minimum. Separate
         * from [nestedMarkerEnabled]: that one is the `nest` word, this one is whether the
         * block is named at all -- a nested block's own declaration is the hardest to find by
         * scrolling, which is the whole reason for the rule.
         */
        var nestedLabelAlways: Boolean by property(true)

        /**
         * Colour of the `nest` marker: before a nested block's own declaration line, and as
         * the prefix on its end-of-block label. Based on the editor's keyword colour, pushed
         * towards grey by this percentage, since "nested" names a language construct rather
         * than a symbol of its own — it should not compete with the block's own accent colour.
         */
        var nestedLabelGreyPercent: Int by property(50)

        /**
         * Master switch for numbering a container's type/namespace children `[1]`, `[2]`, ...
         * Independent of [nestedMarkerEnabled]: a top-level block can be numbered without ever
         * being "nested", and a nested one can carry both markers at once.
         */
        var siblingNumberingEnabled: Boolean by property(true)

        /** How far the `[N]` ordinal moves from the editor's keyword colour towards grey. */
        var siblingGreyPercent: Int by property(50)

        // --- type braces: class, struct, interface, enum, record ---

        var accentTypes: Boolean by property(true)
        var typeBraces: Boolean by property(true)
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

        // Which function blocks the plugin looks at. Off means it does not see the block at
        // all -- no colour, no shadow, no label -- see ScanOptions.accentFunctions.
        var accentMethods: Boolean by property(true)
        var accentConstructors: Boolean by property(true)
        var accentProperties: Boolean by property(true)
        var accentAccessors: Boolean by property(true)
        var accentLambdas: Boolean by property(true)

        /** The lambda symbol on a lambda's own label. Independent of [lambdaNameEnabled]. */
        var lambdaSymbolEnabled: Boolean by property(true)

        /**
         * The borrowed name on a lambda's own label -- the method it is passed to, or the
         * assignment target. Independent of [lambdaSymbolEnabled]: with both off a lambda
         * simply gets no label.
         */
        var lambdaNameEnabled: Boolean by property(true)
        var functionBraces: Boolean by property(true)
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

        // --- namespace braces ---
        //
        // The same knobs as a type or a function, with one difference: there is no minimum
        // length. A namespace wraps the whole file, so its closing brace is always the one
        // furthest from its declaration -- the label is exactly what it is needed for.

        var accentNamespaces: Boolean by property(true)
        var namespaceBraces: Boolean by property(true)
        var namespaceLightPercent: Int by property(50)
        var namespaceDarkPercent: Int by property(50)
        var namespaceBold: Boolean by property(true)
        var namespaceShadow: Boolean by property(true)
        var namespaceShadowPercent: Int by property(45)
        var namespaceShadowOffsetX: Int by property(1)
        var namespaceShadowOffsetY: Int by property(1)
        var namespaceLabel: Boolean by property(true)
        var namespaceLabelGreyPercent: Int by property(50)

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
        if (!state.accentBraces) {
            return null
        }
        if (kind == BlockKind.TYPE && state.accentTypes) {
            return AccentConfig(
                showBraces = state.typeBraces,
                lightPercent = state.typeLightPercent,
                darkPercent = state.typeDarkPercent,
                bold = state.typeBold,
                shadow = state.typeShadow,
                shadowPercent = state.typeShadowPercent,
                shadowOffsetX = state.typeShadowOffsetX,
                shadowOffsetY = state.typeShadowOffsetY,
                showLabel = state.typeLabel,
                labelMinLines = state.typeLabelMinLines,
                labelGreyPercent = state.typeLabelGreyPercent,
            )
        }
        if (kind == BlockKind.FUNCTION && state.accentFunctions) {
            return AccentConfig(
                showBraces = state.functionBraces,
                lightPercent = state.functionLightPercent,
                darkPercent = state.functionDarkPercent,
                bold = state.functionBold,
                shadow = state.functionShadow,
                shadowPercent = state.functionShadowPercent,
                shadowOffsetX = state.functionShadowOffsetX,
                shadowOffsetY = state.functionShadowOffsetY,
                showLabel = state.functionLabel,
                labelMinLines = state.functionLabelMinLines,
                labelGreyPercent = state.functionLabelGreyPercent,
            )
        }
        if (kind == BlockKind.NAMESPACE && state.accentNamespaces) {
            return AccentConfig(
                showBraces = state.namespaceBraces,
                lightPercent = state.namespaceLightPercent,
                darkPercent = state.namespaceDarkPercent,
                bold = state.namespaceBold,
                shadow = state.namespaceShadow,
                shadowPercent = state.namespaceShadowPercent,
                shadowOffsetX = state.namespaceShadowOffsetX,
                shadowOffsetY = state.namespaceShadowOffsetY,
                showLabel = state.namespaceLabel,
                labelMinLines = NAMESPACE_LABEL_MIN_LINES,
                labelGreyPercent = state.namespaceLabelGreyPercent,
            )
        }
        return null
    }

    companion object {
        /**
         * A namespace has no minimum length: every block is at least zero lines long, so the
         * label is unconditional without [needsLabel] needing a special case for the kind.
         */
        private const val NAMESPACE_LABEL_MIN_LINES = 0

        fun getInstance(): AllmanSettings {
            return service()
        }
    }
}
