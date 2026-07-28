package io.github.khopland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayTopologyTest {
    @Test
    fun `topology survives serialization`() {
        val topology = DisplayTopology(
            listOf(
                DisplayMonitor(-1920, 0, 1920, 1040, 1.0, 1.0),
                DisplayMonitor(0, 24, 2560, 1416, 2.0, 2.0),
            ),
        )

        assertEquals(topology, DisplayTopology.parse(topology.serialize()))
    }

    @Test
    fun `matching strongly prefers the same monitor count`() {
        val current = DisplayTopology(
            listOf(
                DisplayMonitor(0, 24, 2560, 1416, 2.0, 2.0),
                DisplayMonitor(2560, 0, 1920, 1040, 1.0, 1.0),
            ),
        )
        val closeTwoMonitorLayout = DisplayTopology(
            listOf(
                DisplayMonitor(0, 24, 2560, 1416, 2.0, 2.0),
                DisplayMonitor(2560, 0, 1920, 1000, 1.0, 1.0),
            ),
        )
        val exactPrimaryMonitorOnly = DisplayTopology(
            listOf(DisplayMonitor(0, 24, 2560, 1416, 2.0, 2.0)),
        )

        assertEquals(40L, current.distanceTo(closeTwoMonitorLayout))
        assertEquals(1_000_000_000L, current.distanceTo(exactPrimaryMonitorOnly))
        assertNull(current.distanceTo(DisplayTopology.EMPTY))
    }
}
