package io.github.khopland

import com.intellij.ide.ui.UISettings
import com.intellij.ide.ui.customization.ActionUrl
import com.intellij.ide.ui.customization.CustomActionsListener
import com.intellij.ide.ui.customization.CustomActionsSchema
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.keymap.impl.ui.ActionsTreeUtil
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetSettings
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Component
import java.awt.Container
import javax.swing.JButton

class LayoutProfilePlatformTest : BasePlatformTestCase() {
    fun testPluginActionsAreRegistered() {
        val actions = ActionManager.getInstance()

        assertNotNull(actions.getAction("io.github.khopland.ideLayoutProfiles.applySlot1"))
        assertNotNull(actions.getAction("io.github.khopland.ideLayoutProfiles.saveNew"))
        assertNotNull(actions.getAction("io.github.khopland.ideLayoutProfiles.updateActive"))
        assertNotNull(actions.getAction("io.github.khopland.ideLayoutProfiles.openSettings"))
        assertNull(actions.getAction("io.github.khopland.ideLayoutProfiles.manage"))
        assertTrue(
            actions.getAction("io.github.khopland.ideLayoutProfiles.applySlot1")
                .templatePresentation.text
                .startsWith("Layout Profiles:"),
        )
    }

    fun testSettingsPageIsRegisteredAndBuilds() {
        val configurable = requireNotNull(
            Configurable.PROJECT_CONFIGURABLE
                .getExtensions(project)
                .first { it.id == LAYOUT_PROFILE_SETTINGS_ID }
                .createConfigurable(),
        )

        assertTrue(configurable is LayoutProfilesConfigurable)
        val component = requireNotNull(configurable.createComponent())
        assertTrue(
            component.descendants()
                .filterIsInstance<JButton>()
                .any { it.text == "Create New" },
        )
        configurable.disposeUIResources()
    }

    fun testSaveAndApplyRoundTrip() {
        val ui = UISettings.getInstance()
        val original = LayoutProfile.capture(project, 0, "Original")
        val service = LayoutProfileService()

        try {
            ui.showNewMainToolbar = true
            ui.showStatusBar = true
            ui.hideToolStripes = false
            service.save(project, 1, "Focus")

            ui.showNewMainToolbar = false
            ui.showStatusBar = false
            ui.hideToolStripes = true
            service.updateActive(project)

            ui.showNewMainToolbar = true
            ui.showStatusBar = true
            ui.hideToolStripes = false
            assertEquals(ApplyResult.APPLIED, service.apply(project, 1))
            assertFalse(ui.showNewMainToolbar)
            assertFalse(ui.showStatusBar)
            assertTrue(ui.hideToolStripes)
            assertEquals("Focus", service.activeSlot()?.displayName)
        } finally {
            service.clear(1)
            original.applyChrome(project)
        }
    }

    fun testSaveAndApplyRestoresStatusBarWidgets() {
        val manager = project.getService(StatusBarWidgetsManager::class.java)
        val factory = StatusBarWidgetFactory.EP_NAME.extensionList.first { it.isConfigurable }
        val settings = StatusBarWidgetSettings.getInstance()
        val original = settings.isEnabled(factory)
        val service = LayoutProfileService()

        try {
            settings.setEnabled(factory, true)
            manager.updateWidget(factory)
            service.save(project, 1, "Widgets")

            settings.setEnabled(factory, false)
            manager.updateWidget(factory)

            assertEquals(ApplyResult.APPLIED, service.apply(project, 1))
            assertTrue(settings.isEnabled(factory))
        } finally {
            settings.setEnabled(factory, original)
            manager.updateWidget(factory)
            service.clear(1)
        }
    }

    fun testSaveAndApplyRestoresMainToolbarCustomization() {
        val schema = CustomActionsSchema.getInstance()
        val originalSchemaActions = schemaActions(schema).map { it.copy() }
        val service = LayoutProfileService()

        try {
            val deletion = ActionUrl(
                arrayListOf(
                    "root",
                    ActionsTreeUtil.getMainToolbar(),
                    ActionsTreeUtil.getMainToolbarLeft(),
                ),
                "main.toolbar.Project",
                ActionUrl.DELETED,
                0,
            )
            val savedSchemaActions = originalSchemaActions.filterNot(::isMainToolbarAction) + deletion
            setSchemaActions(schema, savedSchemaActions)
            val expected = mainToolbarActions(schema).map { it.copy() }
            service.save(project, 1, "Toolbar")
            assertTrue(service.slot(1)!!.mainToolbarSnapshot.contains("main.toolbar.Project"))

            setSchemaActions(schema, originalSchemaActions)

            assertEquals(ApplyResult.APPLIED, service.apply(project, 1))
            assertEquals(expected, mainToolbarActions(schema))
        } finally {
            setSchemaActions(schema, originalSchemaActions)
            service.clear(1)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun schemaActions(schema: CustomActionsSchema): List<ActionUrl> =
        schema.javaClass.getMethod("getActions").invoke(schema) as List<ActionUrl>

    private fun mainToolbarActions(schema: CustomActionsSchema): List<ActionUrl> =
        schemaActions(schema).filter(::isMainToolbarAction)

    private fun isMainToolbarAction(action: ActionUrl): Boolean =
        action.groupPath.size > 1 && action.groupPath[1] == ActionsTreeUtil.getMainToolbar()

    private fun setSchemaActions(schema: CustomActionsSchema, actions: List<ActionUrl>) {
        val updated = CustomActionsSchema(null)
        updated.copyFrom(schema)
        updated.javaClass.getMethod("setActions", List::class.java).invoke(updated, actions)
        schema.copyFrom(updated)
        schema.initActionIcons()
        schema.setCustomizationSchemaForCurrentProjects()
        CustomActionsListener.fireSchemaChanged()
    }

    private fun Container.descendants(): Sequence<Component> = sequence {
        components.forEach {
            yield(it)
            if (it is Container) yieldAll(it.descendants())
        }
    }
}
