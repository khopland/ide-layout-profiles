package io.github.khopland

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ex.ToolWindowManagerEx
import com.intellij.toolWindow.ToolWindowDefaultLayoutManager

/**
 * The IntelliJ Platform has no public API for complete tool-window layout snapshots.
 * Keep the version-sensitive calls in this file and verify them against every target IDE.
 */
internal object PlatformLayoutAdapter {
    fun save(project: Project, layoutName: String) {
        val projectLayouts = ToolWindowManagerEx.getInstanceEx(project)
        ToolWindowDefaultLayoutManager.getInstance().apply {
            setLayout(layoutName, projectLayouts.getLayout())
            activeLayoutName = layoutName
        }
    }

    fun apply(project: Project, layoutName: String): Boolean {
        val layouts = ToolWindowDefaultLayoutManager.getInstance()
        if (layoutName !in layouts.getLayoutNames()) return false

        layouts.activeLayoutName = layoutName
        ToolWindowManagerEx.getInstanceEx(project).setLayout(layouts.getLayoutCopy())
        return true
    }

    fun delete(layoutName: String) {
        val layouts = ToolWindowDefaultLayoutManager.getInstance()
        if (layoutName in layouts.getLayoutNames()) layouts.deleteLayout(layoutName)
    }
}
