package com.hitapps.allmanview

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

        /** Не трогаем строку, на которой стоит каретка: пока печатаешь, скобка остаётся на месте. */
        var skipCaretLine: Boolean by property(true)

        var extensions: String? by string(DEFAULT_EXTENSIONS)
    }

    fun extensionSet(): Set<String> =
        (state.extensions ?: DEFAULT_EXTENSIONS)
            .split(',', ' ', ';')
            .mapNotNull { it.trim().removePrefix(".").lowercase().ifEmpty { null } }
            .toSet()

    companion object {
        const val DEFAULT_EXTENSIONS =
            "cs,cpp,cc,cxx,c,h,hpp,hlsl,cginc,compute,shader,json,js,jsx,ts,tsx,java,kt,kts"

        fun getInstance(): AllmanSettings = service()
    }
}
