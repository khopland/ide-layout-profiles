package io.github.khopland

import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import kotlin.math.abs
import kotlin.math.roundToLong

internal data class DisplayMonitor(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val scaleX: Double,
    val scaleY: Double,
)

internal data class DisplayTopology(
    val monitors: List<DisplayMonitor>,
) {
    val isEmpty: Boolean get() = monitors.isEmpty()

    fun serialize(): String = monitors.joinToString("|") {
        "${it.x},${it.y},${it.width},${it.height},${it.scaleX},${it.scaleY}"
    }

    fun distanceTo(other: DisplayTopology): Long? {
        if (isEmpty || other.isEmpty) return null
        val countPenalty = abs(monitors.size - other.monitors.size) * 1_000_000_000L
        return countPenalty + monitors.zip(other.monitors).sumOf { (left, right) ->
            abs(left.x.toLong() - right.x) +
                abs(left.y.toLong() - right.y) +
                abs(left.width.toLong() - right.width) +
                abs(left.height.toLong() - right.height) +
                (abs(left.scaleX - right.scaleX) * 10_000).roundToLong() +
                (abs(left.scaleY - right.scaleY) * 10_000).roundToLong()
        }
    }

    companion object {
        val EMPTY = DisplayTopology(emptyList())

        fun parse(serialized: String): DisplayTopology {
            if (serialized.isBlank()) return EMPTY
            return runCatching {
                serialized.split("|").map { encodedMonitor ->
                    val values = encodedMonitor.split(",")
                    require(values.size == 6)
                    DisplayMonitor(
                        x = values[0].toInt(),
                        y = values[1].toInt(),
                        width = values[2].toInt().also { require(it > 0) },
                        height = values[3].toInt().also { require(it > 0) },
                        scaleX = values[4].toDouble().also { require(it > 0) },
                        scaleY = values[5].toDouble().also { require(it > 0) },
                    )
                }.sortedWith(compareBy(DisplayMonitor::x, DisplayMonitor::y))
            }.fold(::DisplayTopology) { EMPTY }
        }

        fun current(): DisplayTopology {
            if (GraphicsEnvironment.isHeadless()) return EMPTY
            return runCatching {
                val toolkit = Toolkit.getDefaultToolkit()
                GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .screenDevices
                    .map { device ->
                        val configuration = device.defaultConfiguration
                        val bounds = configuration.bounds
                        val insets = toolkit.getScreenInsets(configuration)
                        DisplayMonitor(
                            x = bounds.x + insets.left,
                            y = bounds.y + insets.top,
                            width = (bounds.width - insets.left - insets.right).coerceAtLeast(1),
                            height = (bounds.height - insets.top - insets.bottom).coerceAtLeast(1),
                            scaleX = configuration.defaultTransform.scaleX,
                            scaleY = configuration.defaultTransform.scaleY,
                        )
                    }
                    .sortedWith(compareBy(DisplayMonitor::x, DisplayMonitor::y))
            }.fold(::DisplayTopology) { EMPTY }
        }
    }
}
