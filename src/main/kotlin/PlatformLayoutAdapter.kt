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

    fun saveTemporary(project: Project, layoutName: String) {
        val layouts = ToolWindowDefaultLayoutManager.getInstance()
        val previousLayoutName = layouts.activeLayoutName
        try {
            layouts.setLayout(layoutName, ToolWindowManagerEx.getInstanceEx(project).getLayout())
        } finally {
            layouts.activeLayoutName = previousLayoutName
        }
    }

    fun apply(project: Project, layoutName: String) {
        val layouts = ToolWindowDefaultLayoutManager.getInstance()
        layouts.activeLayoutName = layoutName
        ToolWindowManagerEx.getInstanceEx(project).setLayout(layouts.getLayoutCopy())
    }

    fun applyTemporary(project: Project, layoutName: String) {
        val layout = requireNotNull(layoutCopy(layoutName)) {
            "The temporary layout “$layoutName” is missing."
        }
        val projectLayouts = ToolWindowManagerEx.getInstanceEx(project)
        val setLayout = projectLayouts.javaClass.methods.firstOrNull { method ->
            method.name == "setLayout" &&
                method.parameterCount == 1 &&
                method.parameterTypes.single().isAssignableFrom(layout.javaClass)
        } ?: error("The IDE does not expose a compatible tool-window layout setter.")
        setLayout.invoke(projectLayouts, layout)
    }

    fun copy(sourceLayoutName: String, targetLayoutName: String) {
        val source = requireNotNull(layoutCopy(sourceLayoutName)) {
            "The native layout “$sourceLayoutName” is missing."
        }
        setLayout(targetLayoutName, source)
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
        replace(mapOf(layoutName to element), emptySet())
    }

    fun replace(importedLayouts: Map<String, Element>, obsoleteLayoutNames: Set<String>) {
        val layouts = ToolWindowDefaultLayoutManager.getInstance()
        val preparedLayouts = importedLayouts.mapValues { (_, element) -> readLayout(element) }
        val affectedNames = importedLayouts.keys + obsoleteLayoutNames
        val previousLayouts = affectedNames.associateWith(::layoutCopy)
        val previousActiveLayoutName = layouts.activeLayoutName

        try {
            preparedLayouts.forEach(::setLayout)
            (obsoleteLayoutNames - importedLayouts.keys).forEach(::delete)
        } catch (error: Exception) {
            previousLayouts.forEach { (name, previousLayout) ->
                runCatching {
                    if (previousLayout == null) delete(name) else setLayout(name, previousLayout)
                }.exceptionOrNull()?.let(error::addSuppressed)
            }
            layouts.activeLayoutName = previousActiveLayoutName
            throw error
        }

        layouts.activeLayoutName = previousActiveLayoutName
            .takeIf { it in layouts.getLayoutNames() }
            ?: importedLayouts.keys.firstOrNull()
            ?: layouts.activeLayoutName
    }

    private fun readLayout(element: Element): Any {
        val layoutClass = ToolWindowDefaultLayoutManager.getInstance().getLayoutCopy().javaClass
        val layout: Any = layoutClass.getConstructor().newInstance()
        layoutClass
            .getMethod("readExternal", Element::class.java, Boolean::class.javaPrimitiveType)
            .invoke(layout, element, false)
        return layout
    }

    private fun layoutCopy(layoutName: String): Any? {
        val layouts = ToolWindowDefaultLayoutManager.getInstance()
        if (layoutName !in layouts.getLayoutNames()) return null
        val previousLayoutName = layouts.activeLayoutName
        return try {
            layouts.activeLayoutName = layoutName
            layouts.getLayoutCopy()
        } finally {
            layouts.activeLayoutName = previousLayoutName
        }
    }

    private fun setLayout(layout: Map.Entry<String, Any>) {
        setLayout(layout.key, layout.value)
    }

    private fun setLayout(layoutName: String, layout: Any) {
        val layouts = ToolWindowDefaultLayoutManager.getInstance()
        layouts.javaClass
            .getMethod("setLayout", String::class.java, layout.javaClass)
            .invoke(layouts, layoutName, layout)
    }

    fun delete(layoutName: String) {
        val layouts = ToolWindowDefaultLayoutManager.getInstance()
        if (layoutName in layouts.getLayoutNames()) layouts.deleteLayout(layoutName)
    }
}
