package de.mark225.stickytree

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

class StickyTreeStartupActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        try {
            StickyTreeInstaller(project).install()
        } catch (t: Throwable) {
            // Swallow to avoid impacting IDE if anything goes wrong
        }
    }
}
