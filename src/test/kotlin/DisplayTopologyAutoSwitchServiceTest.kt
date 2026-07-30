package io.github.khopland

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayTopologyAutoSwitchServiceTest {
    private val laptop = DisplayTopology(
        listOf(DisplayMonitor(0, 0, 1920, 1080, 1.0, 1.0)),
    )
    private val desk = DisplayTopology(
        listOf(
            DisplayMonitor(0, 0, 1920, 1080, 1.0, 1.0),
            DisplayMonitor(1920, 0, 2560, 1440, 1.0, 1.0),
        ),
    )

    @Test
    fun `a changed topology is consumed only after the debounce period`() {
        val debouncer = DisplayTopologyChangeDebouncer(debounceNanos = 10)
        debouncer.reset(laptop)

        assertFalse(debouncer.consumeIfStable(desk, nowNanos = 100))
        assertFalse(debouncer.consumeIfStable(desk, nowNanos = 109))
        assertTrue(debouncer.consumeIfStable(desk, nowNanos = 110))
        assertFalse(debouncer.consumeIfStable(desk, nowNanos = 200))
    }

    @Test
    fun `an unstable candidate restarts the debounce period`() {
        val debouncer = DisplayTopologyChangeDebouncer(debounceNanos = 10)
        val temporary = DisplayTopology(
            listOf(DisplayMonitor(0, 0, 1280, 720, 1.0, 1.0)),
        )
        debouncer.reset(laptop)

        assertFalse(debouncer.consumeIfStable(desk, nowNanos = 100))
        assertFalse(debouncer.consumeIfStable(temporary, nowNanos = 105))
        assertFalse(debouncer.consumeIfStable(desk, nowNanos = 110))
        assertFalse(debouncer.consumeIfStable(desk, nowNanos = 119))
        assertTrue(debouncer.consumeIfStable(desk, nowNanos = 120))
    }
}
