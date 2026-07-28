package io.github.khopland

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.options.Configurable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Component
import java.awt.Container
import javax.swing.JButton

class LayoutProfilePlatformTest : BasePlatformTestCase() {
    fun testPluginActionsAreRegistered() {
        val actions = ActionManager.getInstance()

        assertNotNull(actions.getAction("io.github.khopland.ideLayoutProfiles.applySlot1"))
        assertNotNull(actions.getAction("io.github.khopland.ideLayoutProfiles.applySlot10"))
        assertNotNull(actions.getAction("io.github.khopland.ideLayoutProfiles.saveNew"))
        assertNotNull(actions.getAction("io.github.khopland.ideLayoutProfiles.updateActive"))
        assertNotNull(actions.getAction("io.github.khopland.ideLayoutProfiles.openSettings"))
        @Suppress("UnresolvedPluginConfigReference")
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
        val original = LayoutProfile.capture(0, "Original")
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
            original.applyChrome()
        }
    }

    fun testMoreThanTenProfilesCanBeSavedAndApplied() {
        val service = LayoutProfileService()

        try {
            repeat(11) {
                val number = service.nextProfileNumber()
                service.save(project, number, "Profile $number")
            }

            assertEquals(11, service.profiles().size)
            assertEquals(ApplyResult.APPLIED, service.apply(project, 11))
        } finally {
            while (service.profiles().isNotEmpty()) service.clear(1)
        }
    }

    private fun Container.descendants(): Sequence<Component> = sequence {
        components.forEach {
            yield(it)
            if (it is Container) yieldAll(it.descendants())
        }
    }
}
