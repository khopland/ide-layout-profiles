package io.github.khopland

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.SwingConstants

class LayoutProfilePlatformTest : BasePlatformTestCase() {
    fun testPluginActionsAreRegistered() {
        val actions = ActionManager.getInstance()

        assertNotNull(actions.getAction("io.github.khopland.ideLayoutProfiles.applySlot1"))
        assertNotNull(actions.getAction("io.github.khopland.ideLayoutProfiles.applySlot10"))
        assertNotNull(actions.getAction("io.github.khopland.ideLayoutProfiles.saveNew"))
        assertNotNull(actions.getAction("io.github.khopland.ideLayoutProfiles.updateActive"))
        assertNotNull(actions.getAction("io.github.khopland.ideLayoutProfiles.applyActiveToAll"))
        assertNotNull(actions.getAction("io.github.khopland.ideLayoutProfiles.openSettings"))
        assertTrue(
            actions.getAction("io.github.khopland.ideLayoutProfiles.applyProfile") is ActionGroup,
        )
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
        assertTrue(
            component.descendants()
                .filterIsInstance<JButton>()
                .any { it.text == "Update from Current" },
        )
        assertTrue(
            component.descendants()
                .filterIsInstance<JButton>()
                .any { it.text == "Import…" },
        )
        assertTrue(
            component.descendants()
                .filterIsInstance<JButton>()
                .any { it.text == "Export…" },
        )
        configurable.disposeUIResources()
    }

    fun testSettingsButtonsFitWhenThePageIsNarrow() {
        val configurable = LayoutProfilesConfigurable(project)
        val component = configurable.createComponent()
        component.setSize(1200, 400)
        component.layoutRecursively()
        component.setSize(600, 400)
        component.layoutRecursively()

        val buttons = component.descendants()
            .filterIsInstance<JButton>()
            .toList()
        val buttonBar = buttons.first { it.text == "Create New" }.parent

        assertTrue(
            "Every settings button should fit inside the button bar",
            buttons.all { button ->
                button.x >= 0 &&
                    button.y >= 0 &&
                    button.x + button.width <= buttonBar.width &&
                    button.y + button.height <= buttonBar.height
            },
        )
        configurable.disposeUIResources()
    }

    fun testApplyProfileGroupListsEveryProfileAndMarksTheActiveOne() {
        val service = ApplicationManager.getApplication().getService(LayoutProfileService::class.java)

        try {
            service.loadState(LayoutProfilesState().apply {
                activeSlot = 11
                slots = (1..11).map { number ->
                    LayoutProfile().apply {
                        id = "profile-$number"
                        this.number = number
                        displayName = "Profile $number"
                    }
                }.toMutableList()
            })

            val group = ActionManager.getInstance()
                .getAction("io.github.khopland.ideLayoutProfiles.applyProfile") as ActionGroup
            val children = group.getChildren(null)

            assertEquals(11, children.size)
            assertEquals("Profile 1", children.first().templatePresentation.text)
            assertEquals("✓ Profile 11", children.last().templatePresentation.text)
        } finally {
            service.loadState(LayoutProfilesState())
            syncProfileActions()
        }
    }

    fun testProfileActionKeepsItsUuidAcrossRenameAndReorder() {
        val service = ApplicationManager.getApplication().getService(LayoutProfileService::class.java)
        val actions = ActionManager.getInstance()

        try {
            service.loadState(LayoutProfilesState().apply {
                slots = mutableListOf(
                    LayoutProfile().apply {
                        id = "work"
                        number = 1
                        displayName = "Work"
                    },
                    LayoutProfile().apply {
                        id = "focus"
                        number = 2
                        displayName = "Focus"
                    },
                )
            })
            syncProfileActions()
            val actionId = profileActionId("focus")
            val action = actions.getAction(actionId)

            service.updateProfiles(
                listOf(
                    LayoutProfileUpdate("focus", "Deep Focus"),
                    LayoutProfileUpdate("work", "Work"),
                ),
            )
            syncProfileActions()

            assertSame(action, actions.getAction(actionId))
            assertEquals(
                "Layout Profiles: Apply Profile: Deep Focus",
                action.templatePresentation.text,
            )
            assertNotNull(actions.getAction(slotActionId(2)))

            service.updateProfiles(listOf(LayoutProfileUpdate("work", "Work")))
            syncProfileActions()
            assertNull(actions.getAction(actionId))
        } finally {
            service.loadState(LayoutProfilesState())
            syncProfileActions()
        }
    }

    fun testSaveAndApplyRoundTrip() {
        val ui = UISettings.getInstance()
        val original = LayoutProfile.capture(0, "Original")
        val service = LayoutProfileService()

        try {
            ui.showNewMainToolbar = true
            ui.showStatusBar = true
            ui.hideToolStripes = false
            ui.editorTabPlacement = SwingConstants.BOTTOM
            ui.wideScreenSupport = true
            service.save(project, 1, "Focus")

            ui.showNewMainToolbar = false
            ui.showStatusBar = false
            ui.hideToolStripes = true
            ui.editorTabPlacement = SwingConstants.TOP
            ui.wideScreenSupport = false
            service.updateActive(project)

            ui.showNewMainToolbar = true
            ui.showStatusBar = true
            ui.hideToolStripes = false
            ui.editorTabPlacement = SwingConstants.BOTTOM
            ui.wideScreenSupport = true
            assertEquals(ApplyResult.APPLIED, service.apply(project, 1))
            assertFalse(ui.showNewMainToolbar)
            assertFalse(ui.showStatusBar)
            assertTrue(ui.hideToolStripes)
            assertEquals(SwingConstants.TOP, ui.editorTabPlacement)
            assertFalse(ui.wideScreenSupport)
            assertEquals("Focus", service.activeSlot()?.displayName)

            service.slot(1)!!.editorTabPlacement = -1
            ui.editorTabPlacement = SwingConstants.BOTTOM
            ui.wideScreenSupport = true
            assertEquals(ApplyResult.APPLIED, service.apply(project, 1))
            assertEquals(SwingConstants.BOTTOM, ui.editorTabPlacement)
            assertTrue(ui.wideScreenSupport)
        } finally {
            service.clear(1)
            original.applyChrome()
        }
    }

    fun testProfilesCanBeExportedAndImported() {
        val ui = UISettings.getInstance()
        val original = LayoutProfile.capture(0, "Original")
        val service = LayoutProfileService()

        try {
            ui.showStatusBar = false
            ui.editorTabPlacement = SwingConstants.BOTTOM
            service.save(project, 1, "Portable")
            val profileId = service.slot(1)!!.id
            val xml = JDOMUtil.write(service.exportProfiles())

            assertTrue(xml.contains("<ide-layout-profiles version=\"1\">"))
            assertTrue(xml.contains("<tool-window-layout>"))

            val imported = LayoutProfileInterchange.read(JDOMUtil.load(xml))
            service.clear(1)
            assertEquals(1, service.importProfiles(imported))

            val restored = service.slot(1)!!
            assertEquals(profileId, restored.id)
            assertEquals("Portable", restored.displayName)
            assertFalse(restored.showStatusBar)
            assertEquals(SwingConstants.BOTTOM, restored.editorTabPlacement)
            assertEquals(ApplyResult.APPLIED, service.apply(project, 1))
        } finally {
            while (service.profiles().isNotEmpty()) service.clear(1)
            original.applyChrome()
        }
    }

    fun testAnyProfileCanBeUpdatedById() {
        val ui = UISettings.getInstance()
        val original = LayoutProfile.capture(0, "Original")
        val service = LayoutProfileService()

        try {
            ui.showStatusBar = true
            service.save(project, 1, "First")
            val firstId = service.slot(1)!!.id
            service.save(project, 2, "Second")

            ui.showStatusBar = false
            assertEquals("First", service.update(project, firstId)?.displayName)

            ui.showStatusBar = true
            assertEquals(ApplyResult.APPLIED, service.apply(project, 1))
            assertFalse(ui.showStatusBar)
        } finally {
            while (service.profiles().isNotEmpty()) service.clear(1)
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

    fun testActiveProfileCanBeAppliedToEveryProject() {
        val service = LayoutProfileService()

        try {
            service.save(project, 1, "Everywhere")
            assertEquals(
                ApplyResult.APPLIED,
                service.apply(listOf(project, project), 1),
            )
            assertEquals("Everywhere", service.activeSlot()?.displayName)
        } finally {
            service.clear(1)
        }
    }

    private fun Container.descendants(): Sequence<Component> = sequence {
        components.forEach {
            yield(it)
            if (it is Container) yieldAll(it.descendants())
        }
    }

    private fun Container.layoutRecursively() {
        doLayout()
        components.filterIsInstance<Container>().forEach { it.layoutRecursively() }
    }
}
