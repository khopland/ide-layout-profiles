package io.github.khopland

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetSettings
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager

internal object StatusBarWidgetAdapter {
    fun capture(project: Project): Map<String, Boolean> {
        val settings = StatusBarWidgetSettings.getInstance()
        return manager(project).getWidgetFactories()
            .filter(StatusBarWidgetFactory::isConfigurable)
            .associateTo(linkedMapOf()) { it.id to settings.isEnabled(it) }
    }

    fun apply(project: Project, snapshot: Map<String, Boolean>) {
        val settings = StatusBarWidgetSettings.getInstance()
        val manager = manager(project)
        manager.getWidgetFactories()
            .filter { it.isConfigurable && it.id in snapshot }
            .forEach {
                settings.setEnabled(it, snapshot.getValue(it.id))
                manager.updateWidget(it)
            }
    }

    private fun manager(project: Project): StatusBarWidgetsManager =
        project.getService(StatusBarWidgetsManager::class.java)
}
