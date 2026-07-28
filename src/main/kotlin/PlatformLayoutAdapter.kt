package io.github.khopland

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ex.ToolWindowManagerEx
import com.intellij.toolWindow.ToolWindowDefaultLayoutManager
import org.jdom.Element

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

    fun export(layoutName: String): Element? {
        val layouts = ToolWindowDefaultLayoutManager.getInstance()
        if (layoutName !in layouts.getLayoutNames()) return null
        val previousLayoutName = layouts.activeLayoutName
        return try {
            layouts.activeLayoutName = layoutName
            val layout: Any = layouts.getLayoutCopy()
            layout.javaClass
                .getMethod("writeExternal", String::class.java)
                .invoke(layout, LAYOUT_ELEMENT) as? Element
        } finally {
            layouts.activeLayoutName = previousLayoutName
        }
    }

    fun import(layoutName: String, element: Element) {
        val layouts = ToolWindowDefaultLayoutManager.getInstance()
        val currentLayout: Any = layouts.getLayoutCopy()
        val layoutClass = currentLayout.javaClass
        val layout = layoutClass.getConstructor().newInstance()
        layoutClass
            .getMethod("readExternal", Element::class.java, Boolean::class.javaPrimitiveType)
            .invoke(layout, element, false)
        layouts.javaClass
            .getMethod("setLayout", String::class.java, layoutClass)
            .invoke(layouts, layoutName, layout)
    }

    fun delete(layoutName: String) {
        val layouts = ToolWindowDefaultLayoutManager.getInstance()
        if (layoutName in layouts.getLayoutNames()) layouts.deleteLayout(layoutName)
    }
}
