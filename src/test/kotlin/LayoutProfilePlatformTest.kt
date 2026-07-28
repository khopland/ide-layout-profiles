package io.github.khopland

import com.intellij.ide.ui.UISettings
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import kotlinx.coroutines.runBlocking
import org.jdom.Element
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
        assertNotNull(actions.getAction("io.github.khopland.ideLayoutProfiles.applyBestMatch"))
        assertNotNull(actions.getAction("io.github.khopland.ideLayoutProfiles.applyActiveToAll"))
        assertNotNull(actions.getAction("io.github.khopland.ideLayoutProfiles.openSettings"))
        assertTrue(
            actions.getAction("io.github.khopland.ideLayoutProfiles.applyProfile") is ActionGroup,
        )
        assertTrue(
            actions.getAction("io.github.khopland.ideLayoutProfiles.updateProfile") is ActionGroup,
        )
        assertTrue(
            actions.getAction("io.github.khopland.ideLayoutProfiles.startupProfile") is ActionGroup,
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
                .any { it.text == "Export Selected…" },
        )
        assertTrue(
            component.descendants()
                .filterIsInstance<JButton>()
                .any { it.text == "Export All…" },
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

    fun testUpdatingFromCurrentDoesNotCommitPendingReorder() {
        val service = ApplicationManager.getApplication().getService(LayoutProfileService::class.java)
        val configurable = LayoutProfilesConfigurable(project)

        try {
            service.save(project, 1, "First")
            service.save(project, 2, "Second")
            val component = configurable.createComponent()
            val buttons = component.descendants()
                .filterIsInstance<JButton>()
                .associateBy(JButton::getText)

            buttons.getValue("Move Down").doClick()
            buttons.getValue("Update from Current").doClick()

            assertEquals(listOf("First", "Second"), service.profiles().map(LayoutProfile::displayName))
            assertTrue(configurable.isModified)
        } finally {
            configurable.disposeUIResources()
            while (service.profiles().isNotEmpty()) service.clear(1)
            syncProfileActions()
        }
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

    fun testUpdateProfileGroupListsEveryProfile() {
        val service = ApplicationManager.getApplication().getService(LayoutProfileService::class.java)

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

            val group = ActionManager.getInstance()
                .getAction("io.github.khopland.ideLayoutProfiles.updateProfile") as ActionGroup

            assertEquals(
                listOf("Work", "Focus"),
                group.getChildren(null).map { it.templatePresentation.text },
            )
        } finally {
            service.loadState(LayoutProfilesState())
            syncProfileActions()
        }
    }

    fun testStartupProfileGroupListsEveryProfileAndMarksTheSelectedOne() {
        val service = ApplicationManager.getApplication().getService(LayoutProfileService::class.java)

        try {
            service.loadState(LayoutProfilesState().apply {
                startupProfileId = "focus"
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

            val group = ActionManager.getInstance()
                .getAction("io.github.khopland.ideLayoutProfiles.startupProfile") as ActionGroup

            assertEquals(
                listOf("None", "Work", "✓ Focus"),
                group.getChildren(null).map { it.templatePresentation.text },
            )
        } finally {
            service.loadState(LayoutProfilesState())
            syncProfileActions()
        }
    }

    fun testStartupActivityReportsMissingNativeLayout() {
        val service = ApplicationManager.getApplication().getService(LayoutProfileService::class.java)
        var received: Notification? = null
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    received = notification
                }
            },
        )

        try {
            service.loadState(LayoutProfilesState().apply {
                startupProfileId = "startup"
                slots = mutableListOf(
                    LayoutProfile().apply {
                        id = "startup"
                        nativeLayoutName = "[IDE Layout Profiles Test] Missing"
                        number = 1
                        displayName = "Startup"
                    },
                )
            })

            runBlocking { ProfileActionsStartupActivity().execute(project) }

            assertEquals(NotificationType.WARNING, received?.type)
            assertTrue(received?.content?.contains("missing") == true)
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
            val displayTopology = DisplayTopology(
                listOf(DisplayMonitor(0, 24, 2560, 1416, 2.0, 2.0)),
            ).serialize()
            service.slot(1)!!.displayTopology = displayTopology
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
            assertEquals(displayTopology, restored.displayTopology)
            assertEquals(ApplyResult.APPLIED, service.apply(project, 1))
        } finally {
            while (service.profiles().isNotEmpty()) service.clear(1)
            original.applyChrome()
        }
    }

    fun testSelectedProfileCanBeExported() {
        val service = LayoutProfileService()

        try {
            service.save(project, 1, "First")
            service.save(project, 2, "Second")
            val second = requireNotNull(service.slot(2))

            val exported = LayoutProfileInterchange.read(
                service.exportProfiles(setOf(second.id)),
            )

            assertEquals(listOf("Second"), exported.profiles.map { it.profile.displayName })
        } finally {
            while (service.profiles().isNotEmpty()) service.clear(1)
        }
    }

    fun testAddImportAppendsOnlyNewProfiles() {
        val service = LayoutProfileService()

        try {
            service.save(project, 1, "Existing")
            val existing = requireNotNull(service.slot(1))
            val layout = requireNotNull(PlatformLayoutAdapter.export(existing.nativeLayoutName))
            val addedId = java.util.UUID.randomUUID().toString()

            val result = service.importProfiles(
                ImportedProfiles(
                    listOf(
                        importedProfile(existing.id, "Replacement", layout),
                        importedProfile(addedId, "Added", layout),
                    ),
                ),
                ImportMode.ADD,
            )

            assertEquals(ImportResult(imported = 1, skipped = 1), result)
            assertEquals(listOf("Existing", "Added"), service.profiles().map(LayoutProfile::displayName))
        } finally {
            while (service.profiles().isNotEmpty()) service.clear(1)
        }
    }

    fun testUpdateImportChangesOnlyExistingProfiles() {
        val service = LayoutProfileService()

        try {
            service.save(project, 1, "Existing")
            service.save(project, 2, "Keep")
            val existing = requireNotNull(service.slot(1))
            val layout = requireNotNull(PlatformLayoutAdapter.export(existing.nativeLayoutName))
            val missingId = java.util.UUID.randomUUID().toString()

            val result = service.importProfiles(
                ImportedProfiles(
                    listOf(
                        importedProfile(existing.id, "Updated", layout),
                        importedProfile(missingId, "Missing", layout),
                    ),
                ),
                ImportMode.UPDATE_EXISTING,
            )

            assertEquals(ImportResult(imported = 1, skipped = 1), result)
            assertEquals(listOf("Updated", "Keep"), service.profiles().map(LayoutProfile::displayName))
            assertNull(service.profile(missingId))
        } finally {
            while (service.profiles().isNotEmpty()) service.clear(1)
        }
    }

    fun testCopyImportAssignsFreshProfileIds() {
        val service = LayoutProfileService()

        try {
            service.save(project, 1, "Existing")
            val existing = requireNotNull(service.slot(1))
            val layout = requireNotNull(PlatformLayoutAdapter.export(existing.nativeLayoutName))

            val result = service.importProfiles(
                ImportedProfiles(
                    listOf(importedProfile(existing.id, "Copied", layout)),
                ),
                ImportMode.COPY,
            )

            assertEquals(ImportResult(imported = 1, skipped = 0), result)
            assertEquals(listOf("Existing", "Copied"), service.profiles().map(LayoutProfile::displayName))
            assertEquals(2, service.profiles().map(LayoutProfile::id).distinct().size)
        } finally {
            while (service.profiles().isNotEmpty()) service.clear(1)
        }
    }

    fun testFailedImportRestoresExistingNativeLayouts() {
        val service = LayoutProfileService()

        try {
            service.save(project, 1, "Original")
            val original = requireNotNull(service.slot(1))
            val originalLayout = requireNotNull(PlatformLayoutAdapter.export(original.nativeLayoutName))
            val originalLayoutXml = JDOMUtil.write(originalLayout)
            val newProfileId = java.util.UUID.randomUUID().toString()
            val replacementLayout = Element(LAYOUT_ELEMENT).apply {
                addContent(Element("window_info").setAttribute("id", "Replacement"))
            }
            val invalidLayout = object : Element(LAYOUT_ELEMENT) {
                override fun getChildren(name: String): List<Element> {
                    error("Deliberate layout preparation failure")
                }
            }
            val imported = ImportedProfiles(
                listOf(
                    ImportedProfile(
                        LayoutProfile().apply {
                            id = original.id
                            displayName = "Replacement"
                        },
                        replacementLayout,
                    ),
                    ImportedProfile(
                        LayoutProfile().apply {
                            id = newProfileId
                            displayName = "Invalid"
                        },
                        invalidLayout,
                    ),
                ),
            )

            assertNotNull(runCatching { service.importProfiles(imported) }.exceptionOrNull())
            assertEquals(listOf("Original"), service.profiles().map(LayoutProfile::displayName))
            assertEquals(
                originalLayoutXml,
                JDOMUtil.write(requireNotNull(PlatformLayoutAdapter.export(original.nativeLayoutName))),
            )
            assertFalse(
                PlatformLayoutAdapter.exists("[IDE Layout Profiles] Profile $newProfileId"),
            )
        } finally {
            while (service.profiles().isNotEmpty()) service.clear(1)
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
        val secondFixture = IdeaTestFixtureFactory.getFixtureFactory()
            .createFixtureBuilder("second-project")
            .fixture
        val markerLayoutName = "[IDE Layout Profiles Test] Marker"
        val firstResultName = "[IDE Layout Profiles Test] First Result"
        val secondResultName = "[IDE Layout Profiles Test] Second Result"

        secondFixture.setUp()
        try {
            service.save(project, 1, "Everywhere")
            val savedLayoutXml = JDOMUtil.write(
                requireNotNull(PlatformLayoutAdapter.export(service.slot(1)!!.nativeLayoutName)),
            )
            PlatformLayoutAdapter.import(
                markerLayoutName,
                Element(LAYOUT_ELEMENT).apply {
                    addContent(Element("window_info").setAttribute("id", "Second Project Marker"))
                },
            )
            PlatformLayoutAdapter.apply(secondFixture.project, markerLayoutName)

            assertNotSame(project, secondFixture.project)
            assertEquals(
                ApplyResult.APPLIED,
                service.apply(listOf(project, secondFixture.project), 1),
            )
            PlatformLayoutAdapter.save(project, firstResultName)
            PlatformLayoutAdapter.save(secondFixture.project, secondResultName)

            assertEquals(
                savedLayoutXml,
                JDOMUtil.write(requireNotNull(PlatformLayoutAdapter.export(firstResultName))),
            )
            assertEquals(
                savedLayoutXml,
                JDOMUtil.write(requireNotNull(PlatformLayoutAdapter.export(secondResultName))),
            )
            assertEquals("Everywhere", service.activeSlot()?.displayName)
        } finally {
            service.clear(1)
            PlatformLayoutAdapter.delete(markerLayoutName)
            PlatformLayoutAdapter.delete(firstResultName)
            PlatformLayoutAdapter.delete(secondResultName)
            secondFixture.tearDown()
        }
    }

    private fun Container.descendants(): Sequence<Component> = sequence {
        components.forEach {
            yield(it)
            if (it is Container) yieldAll(it.descendants())
        }
    }

    private fun importedProfile(id: String, name: String, layout: Element) = ImportedProfile(
        LayoutProfile().apply {
            this.id = id
            displayName = name
        },
        layout.clone(),
    )

    private fun Container.layoutRecursively() {
        doLayout()
        components.filterIsInstance<Container>().forEach { it.layoutRecursively() }
    }
}
