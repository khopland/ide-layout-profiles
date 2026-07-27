package io.github.khopland

import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutProfileServiceTest {
    @Test
    fun `loading state drops invalid and duplicate slots`() {
        val service = LayoutProfileService()
        service.loadState(LayoutProfilesState().apply {
            activeSlot = 6
            slots = mutableListOf(
                slot(2, "Work"),
                slot(2, "Duplicate"),
                slot(6, "Out of range"),
                slot(3, ""),
            )
        })

        assertEquals("Work", service.slot(2)?.displayName)
        assertEquals(1, service.state.slots.size)
        assertNull(service.activeSlot())
        assertEquals(1, service.firstEmptySlot())
    }

    @Test
    fun `toolbar and status bar snapshots survive persistence`() {
        val state = LayoutProfilesState().apply {
            slots = mutableListOf(slot(1, "Focus").apply {
                id = "focus"
                nativeLayoutName = "[IDE Layout Profiles] Profile focus"
                hasMainToolbarSnapshot = true
                mainToolbarSnapshot = "<main-toolbar><action /></main-toolbar>"
                hasStatusBarWidgetSnapshot = true
                statusBarWidgets = linkedMapOf(
                    "Position" to false,
                    "Encoding" to true,
                )
            })
        }

        val restored = XmlSerializer.deserialize(
            XmlSerializer.serialize(state),
            LayoutProfilesState::class.java,
        )
        val slot = restored.slots.single()

        assertEquals("focus", slot.id)
        assertEquals("[IDE Layout Profiles] Profile focus", slot.nativeLayoutName)
        assertTrue(slot.hasMainToolbarSnapshot)
        assertEquals("<main-toolbar><action /></main-toolbar>", slot.mainToolbarSnapshot)
        assertTrue(slot.hasStatusBarWidgetSnapshot)
        assertFalse(slot.statusBarWidgets.getValue("Position"))
        assertTrue(slot.statusBarWidgets.getValue("Encoding"))
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
