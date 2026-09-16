package com.hitapps.allmanview

import com.hitapps.allmanview.scan.Dialects
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(name = "AllmanView", storages = [Storage("allman-view.xml")])
class AllmanSettings : SimplePersistentStateComponent<AllmanSettings.Config>(Config()) {

    class Config : BaseState() {
        /** Общий выключатель. */
        var enabled: Boolean by property(true)

        /** true — разносим ещё и `} else {` на три строки; false — только висящую `{`. */
        var fullAllman: Boolean by property(true)

        /** Гасить исходный текст, который визуально уехал вниз. */
        var dimOriginal: Boolean by property(true)

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

    companion object {
        fun getInstance(): AllmanSettings {
            return service()
        }
    }
}
