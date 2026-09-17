package com.hitapps.allmanview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer

@Service(Service.Level.APP)
class AllmanService : Disposable {

    init {
        EditorFactory.getInstance().addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) {
                    attach(event.editor)
                }

                override fun editorReleased(event: EditorFactoryEvent) {
                    val controller = event.editor.getUserData(AllmanController.KEY)
                    if (controller != null) {
                        Disposer.dispose(controller)
                    }
                }
            },
            this,
        )
    }

    /** A controller is always attached; whether it draws anything is decided inside refresh(). */
    fun attach(editor: Editor) {
        if (editor.editorKind != EditorKind.MAIN_EDITOR) {
            return
        }
        if (editor.getUserData(AllmanController.KEY) != null) {
            return
        }
        val controller = AllmanController(editor)
        Disposer.register(this, controller)
    }

    fun refreshAll() {
        ApplicationManager.getApplication().invokeLater {
            for (editor in EditorFactory.getInstance().allEditors) {
                attach(editor)
                val controller = editor.getUserData(AllmanController.KEY)
                if (controller != null) {
                    controller.schedule(AllmanController.IMMEDIATE_DELAY_MS)
                }
            }
        }
    }

    override fun dispose() {
        // The editor listener and every controller are unregistered through Disposer.
    }

    companion object {
        fun getInstance(): AllmanService {
            return service()
        }
    }
}

/** Starts the service and picks up editors that were already open before the plugin loaded. */
class AllmanStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        AllmanService.getInstance().refreshAll()
    }
}
