package io.github.khopland

import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import javax.swing.SwingConstants

class LayoutProfileServiceTest {
    @Test
    fun `import rejects unsupported format versions`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            LayoutProfileInterchange.read(
                Element("ide-layout-profiles").setAttribute("version", "2"),
            )
        }

        assertEquals("Unsupported profile export version.", error.message)
    }

    @Test
    fun `import rejects malformed display topology`() {
        val profile = Element("profile")
            .setAttribute("id", UUID.randomUUID().toString())
            .setAttribute("name", "Malformed")
            .addContent(
                Element("ui")
                    .setAttribute("main-toolbar", "true")
                    .setAttribute("new-main-toolbar", "true")
                    .setAttribute("main-menu", "true")
                    .setAttribute("navigation-bar", "true")
                    .setAttribute("navigation-bar-location", "TOP")
                    .setAttribute("tool-window-bars-hidden", "false")
                    .setAttribute("status-bar", "true")
                    .setAttribute("editor-tab-placement", "-1")
                    .setAttribute("widescreen", "false")
                    .setAttribute("display-topology", "not-a-topology"),
            )
            .addContent(Element(LAYOUT_ELEMENT))
        val root = Element("ide-layout-profiles")
            .setAttribute("version", "1")
            .addContent(profile)

        val error = assertThrows(IllegalArgumentException::class.java) {
            LayoutProfileInterchange.read(root)
        }

        assertEquals("Profile “Malformed” has an invalid display topology.", error.message)
    }

    @Test
    fun `import accepts legacy profiles without editor tab placement`() {
        val profile = Element("profile")
            .setAttribute("id", UUID.randomUUID().toString())
            .setAttribute("name", "Legacy")
            .addContent(
                Element("ui")
                    .setAttribute("main-toolbar", "true")
                    .setAttribute("new-main-toolbar", "true")
                    .setAttribute("main-menu", "true")
                    .setAttribute("navigation-bar", "true")
                    .setAttribute("navigation-bar-location", "TOP")
                    .setAttribute("tool-window-bars-hidden", "false")
                    .setAttribute("status-bar", "true")
                    .setAttribute("editor-tab-placement", "-1")
                    .setAttribute("widescreen", "false"),
            )
            .addContent(Element(LAYOUT_ELEMENT))
        val root = Element("ide-layout-profiles")
            .setAttribute("version", "1")
            .addContent(profile)

        val imported = LayoutProfileInterchange.read(root)

        assertEquals(-1, imported.profiles.single().profile.editorTabPlacement)
    }

    @Test
    fun `loading state drops invalid profiles and compacts positions`() {
        val service = LayoutProfileService()
        service.loadState(LayoutProfilesState().apply {
            activeSlot = 6
            slots = mutableListOf(
                slot(2, "Work"),
                slot(2, "Duplicate"),
                slot(6, "Focus"),
                slot(0, "Invalid"),
                slot(3, ""),
            )
        })

        assertEquals("Work", service.slot(1)?.displayName)
        assertEquals("Focus", service.slot(2)?.displayName)
        assertEquals(2, service.state.slots.size)
        assertEquals("Focus", service.activeSlot()?.displayName)
        assertEquals(3, service.nextProfileNumber())
        assertEquals(-1, service.slot(1)?.editorTabPlacement)
    }

    @Test
    fun `loaded and persisted state are isolated snapshots`() {
        val source = LayoutProfilesState().apply {
            slots = mutableListOf(slot(1, "Work").apply { id = "work" })
        }
        val service = LayoutProfileService()

        service.loadState(source)
        source.slots.single().displayName = "Changed outside the service"
        val persisted = service.state
        persisted.slots.single().displayName = "Changed persistence snapshot"
        service.slot(1)?.displayName = "Changed slot snapshot"
        service.profiles().single().displayName = "Changed profiles snapshot"

        assertEquals("Work", service.slot(1)?.displayName)
        assertEquals("Work", service.state.slots.single().displayName)
    }

    @Test
    fun `more than ten profiles can be stored and reordered`() {
        val service = LayoutProfileService()
        service.loadState(LayoutProfilesState().apply {
            activeSlot = 12
            slots = (1..12).map { number ->
                slot(number, "Profile $number").apply { id = "profile-$number" }
            }.toMutableList()
        })

        assertEquals(12, service.profiles().size)
        assertEquals(13, service.nextProfileNumber())

        service.updateProfiles(
            service.profiles()
                .reversed()
                .map { LayoutProfileUpdate(it.id, it.displayName) },
        )

        assertEquals("profile-12", service.slot(1)?.id)
        assertEquals(1, service.activeSlot()?.number)
        assertEquals(13, service.nextProfileNumber())
    }

    @Test
    fun `appearance settings survive persistence`() {
        val state = LayoutProfilesState().apply {
            slots = mutableListOf(slot(1, "Focus").apply {
                id = "focus"
                nativeLayoutName = "[IDE Layout Profiles] Profile focus"
                showNewMainToolbar = false
                showStatusBar = false
                hideToolStripes = true
                editorTabPlacement = SwingConstants.BOTTOM
                wideScreenSupport = true
            })
        }

        val restored = XmlSerializer.deserialize(
            XmlSerializer.serialize(state),
            LayoutProfilesState::class.java,
        )
        val slot = restored.slots.single()

        assertEquals("focus", slot.id)
        assertEquals("[IDE Layout Profiles] Profile focus", slot.nativeLayoutName)
        assertFalse(slot.showNewMainToolbar)
        assertFalse(slot.showStatusBar)
        assertTrue(slot.hideToolStripes)
        assertEquals(SwingConstants.BOTTOM, slot.editorTabPlacement)
        assertTrue(slot.wideScreenSupport)
    }

    @Test
    fun `startup profile and display topology survive persistence`() {
        val topology = DisplayTopology(
            listOf(DisplayMonitor(0, 24, 2560, 1416, 2.0, 2.0)),
        ).serialize()
        val state = LayoutProfilesState().apply {
            startupProfileId = "focus"
            slots = mutableListOf(slot(1, "Focus").apply {
                id = "focus"
                displayTopology = topology
            })
        }

        val restored = XmlSerializer.deserialize(
            XmlSerializer.serialize(state),
            LayoutProfilesState::class.java,
        )
        val service = LayoutProfileService().apply { loadState(restored) }

        assertEquals(StartupMode.PROFILE, service.startupMode())
        assertEquals("focus", service.startupProfile()?.id)
        assertEquals(topology, service.profile("focus")?.displayTopology)
    }

    @Test
    fun `best match startup mode is exclusive and survives persistence`() {
        val topology = DisplayTopology(
            listOf(DisplayMonitor(0, 24, 2560, 1416, 2.0, 2.0)),
        )
        val state = LayoutProfilesState().apply {
            startupProfileId = "ignored"
            startupBestMatch = true
            autoSwitchBestMatch = true
            slots = mutableListOf(slot(1, "Focus").apply {
                id = "focus"
                displayTopology = topology.serialize()
            })
        }
        val restored = XmlSerializer.deserialize(
            XmlSerializer.serialize(state),
            LayoutProfilesState::class.java,
        )
        val service = LayoutProfileService().apply { loadState(restored) }

        assertEquals(StartupMode.BEST_MATCH, service.startupMode())
        assertEquals("", service.state.startupProfileId)
        assertEquals("focus", service.startupMatch(topology)?.id)
        assertTrue(service.autoSwitchBestMatch())

        service.setStartupProfile("focus")
        assertEquals(StartupMode.PROFILE, service.startupMode())
        assertFalse(service.state.startupBestMatch)

        service.setStartupProfile(null)
        assertEquals(StartupMode.NONE, service.startupMode())
    }

    @Test
    fun `best match prefers a profile with the closest display topology`() {
        val singleDisplay = DisplayTopology(
            listOf(DisplayMonitor(0, 24, 2560, 1416, 2.0, 2.0)),
        )
        val dualDisplay = DisplayTopology(
            listOf(
                DisplayMonitor(0, 24, 2560, 1416, 2.0, 2.0),
                DisplayMonitor(2560, 0, 1920, 1040, 1.0, 1.0),
            ),
        )
        val service = LayoutProfileService().apply {
            loadState(LayoutProfilesState().apply {
                slots = mutableListOf(
                    slot(1, "Laptop").apply {
                        id = "laptop"
                        displayTopology = singleDisplay.serialize()
                    },
                    slot(2, "Desk").apply {
                        id = "desk"
                        displayTopology = dualDisplay.serialize()
                    },
                )
            })
        }

        assertEquals("desk", service.bestMatch(dualDisplay)?.id)
    }

    @Test
    fun `reordering profiles preserves the active profile and native layouts`() {
        val service = LayoutProfileService()
        service.loadState(LayoutProfilesState().apply {
            activeSlot = 2
            slots = mutableListOf(
                slot(1, "Work").apply {
                    id = "work"
                    nativeLayoutName = "native-work"
                },
                slot(2, "Focus").apply {
                    id = "focus"
                    nativeLayoutName = "native-focus"
                },
            )
        })

        service.updateProfiles(
            listOf(
                LayoutProfileUpdate("focus", "Deep Focus"),
                LayoutProfileUpdate("work", "Work"),
            ),
        )

        assertEquals("focus", service.slot(1)?.id)
        assertEquals("Deep Focus", service.slot(1)?.displayName)
        assertEquals("native-focus", service.slot(1)?.nativeLayoutName)
        assertEquals(1, service.activeSlot()?.number)
        assertEquals("work", service.slot(2)?.id)
        assertEquals("native-work", service.slot(2)?.nativeLayoutName)
    }

    private fun slot(number: Int, name: String) = LayoutProfile().apply {
        this.number = number
        displayName = name
    }
}
