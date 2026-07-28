package io.github.khopland

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ex.ToolWindowManagerEx
import com.intellij.toolWindow.ToolWindowDefaultLayoutManager

/**
 * The IntelliJ Platform has no public API for complete tool-window layout snapshots.
 * Keep the version-sensitive calls in this file and verify them against every target IDE.
 */
internal object PlatformLayoutAdapter {
    fun exists(layoutName: String): Boolean =
        layoutName in ToolWindowDefaultLayoutManager.getInstance().getLayoutNames()

    fun save(project: Project, layoutName: String) {
        val projectLayouts = ToolWindowManagerEx.getInstanceEx(project)
        ToolWindowDefaultLayoutManager.getInstance().apply {
            setLayout(layoutName, projectLayouts.getLayout())
            activeLayoutName = layoutName
        }
    }

    fun apply(project: Project, layoutName: String) {
        val layouts = ToolWindowDefaultLayoutManager.getInstance()
        layouts.activeLayoutName = layoutName
        ToolWindowManagerEx.getInstanceEx(project).setLayout(layouts.getLayoutCopy())
    }

    fun delete(layoutName: String) {
        val layouts = ToolWindowDefaultLayoutManager.getInstance()
        if (layoutName in layouts.getLayoutNames()) layouts.deleteLayout(layoutName)
    }
}
