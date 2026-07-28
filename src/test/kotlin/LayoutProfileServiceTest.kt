package io.github.khopland

import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.SwingConstants

class LayoutProfileServiceTest {
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
