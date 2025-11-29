package de.mark225.stickytree

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.Alarm
import com.intellij.util.ui.UIUtil
import org.jetbrains.annotations.NotNull
import java.awt.Component
import java.awt.Container
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.JTree
import javax.swing.JLayer

class StickyTreeInstaller(private val project: Project) : Disposable {
    private var installed = false
    private var attempts = 0
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var busConnection: MessageBusConnection? = null

    fun install() {
        if (project.isDisposed) return
        // Start a retrying attempt sequence on the EDT
        UIUtil.invokeLaterIfNeeded {
            attachToolWindowListener()
            tryInstallOrSchedule()
        }
    }

    private fun tryInstallOrSchedule() {
        if (installed || project.isDisposed) return

        val tree = findProjectViewTree(project)
        val scroll = tree?.let { findEnclosingScrollPane(it) }

        if (tree == null || scroll == null) {
            // Project View might not be constructed yet; retry a few times with delay
            if (attempts < 50) { // ~25 seconds at 500ms, generous but bounded
                attempts++
                alarm.cancelAllRequests()
                alarm.addRequest({ tryInstallOrSchedule() }, 500)
            }
            return
        }

        // Avoid double installation
        val key = "StickyTree.HeaderInstalled"
        if (scroll.getClientProperty(key) == true) {
            installed = true
            return
        }

        // Remove any existing column header to avoid layout-affecting changes that cause flicker
        try {
            scroll.setColumnHeaderView(null)
        } catch (_: Throwable) {
        }

        val viewport = scroll.viewport ?: return
        // If the current view is already a JLayer (possibly installed by the platform or another plugin),
        // we still wrap it inside our own JLayer to add the overlay. JLayer nesting is supported.
        val toWrap: JComponent? = when (val currentView = viewport.view) {
            is JComponent -> currentView
            else -> tree
        }

        val ui = StickyTreeLayerUI()
        val layer = JLayer<JComponent>(toWrap)
        layer.setUI(ui)
        viewport.view = layer
        viewport.revalidate()
        viewport.repaint()
        scroll.putClientProperty(key, true)

        if (!installed) Disposer.register(project, this)
        installed = true

    }

    private fun attachToolWindowListener() {
        if (busConnection != null) return
        val conn = project.messageBus.connect(this)
        conn.subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
            override fun stateChanged(toolWindowManager: ToolWindowManager) {
                val tw = toolWindowManager.getToolWindow("Project")
                if (tw != null && tw.isAvailable && tw.isVisible) {
                    tryInstallOrSchedule()
                }
            }
            override fun toolWindowsRegistered(@NotNull ids: List<String> , @NotNull toolWindowManager : ToolWindowManager ) {
                if (ids.contains("Project")) {
                    tryInstallOrSchedule()
                }
            }
        })
        busConnection = conn
    }

    override fun dispose() {
        // Nothing specific; header is attached to scroll pane which will be disposed by IDE
    }

    private fun findProjectViewTree(project: Project): JTree? {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Project") ?: return null
        val contentManager = toolWindow.contentManager ?: return null
        val contents = contentManager.contents
        for (content in contents) {
            val comp = content.component ?: continue
            val tree = UIUtil.findComponentOfType(comp, JTree::class.java)
            if (tree != null) return tree
        }
        // Also try the tool window component itself as a fallback
        val twComp = toolWindow.component
        return UIUtil.findComponentOfType(twComp, JTree::class.java)
    }

    private fun findEnclosingScrollPane(component: Component): JScrollPane? {
        var c: Container? = component.parent
        while (c != null) {
            if (c is JScrollPane) return c
            c = c.parent
        }
        return null
    }
}
