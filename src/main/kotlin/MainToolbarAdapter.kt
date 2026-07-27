package io.github.khopland

import com.intellij.ide.ui.customization.ActionUrl
import com.intellij.ide.ui.customization.CustomActionsListener
import com.intellij.ide.ui.customization.CustomActionsSchema
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.keymap.impl.ui.ActionsTreeUtil
import com.intellij.openapi.util.JDOMUtil
import org.jdom.Element

internal object MainToolbarAdapter {
    private val log = Logger.getInstance(MainToolbarAdapter::class.java)

    fun capture(): String = try {
        val root = Element("main-toolbar")
        val schema = CustomActionsSchema.getInstance()
        val toolbarName = ActionsTreeUtil.getMainToolbar()
        schema.getActions()
            .filter { it.belongsToMainToolbar(toolbarName) }
            .forEach { action ->
                root.addContent(Element("action").also(action::writeExternal))
            }
        JDOMUtil.writeElement(root)
    } catch (exception: Exception) {
        log.warn("Unable to capture the Main Toolbar customization", exception)
        ""
    }

    fun apply(snapshot: String) {
        try {
            val root = JDOMUtil.load(snapshot)
            val schema = CustomActionsSchema.getInstance()
            val toolbarName = ActionsTreeUtil.getMainToolbar()
            val actions = schema.getActions()
                .filterNot { it.belongsToMainToolbar(toolbarName) }
                .mapTo(ArrayList(), ActionUrl::copy)
            root.getChildren("action")
                .mapTo(actions) { element ->
                    ActionUrl().apply { readExternal(element) }
                }

            val updated = CustomActionsSchema(null)
            updated.copyFrom(schema)
            updated.setActions(actions)
            schema.copyFrom(updated)
            schema.initActionIcons()
            schema.setCustomizationSchemaForCurrentProjects()
            CustomActionsListener.fireSchemaChanged()
        } catch (exception: Exception) {
            log.warn("Unable to restore the Main Toolbar customization", exception)
        }
    }

    private fun ActionUrl.belongsToMainToolbar(toolbarName: String): Boolean =
        groupPath.size > 1 && groupPath[1] == toolbarName
}
