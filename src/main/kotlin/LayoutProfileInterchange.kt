package io.github.khopland

import com.intellij.ide.ui.NavBarLocation
import org.jdom.Element
import java.util.UUID
import javax.swing.SwingConstants

private const val ROOT_ELEMENT = "ide-layout-profiles"
private const val PROFILE_ELEMENT = "profile"
private const val UI_ELEMENT = "ui"
internal const val LAYOUT_ELEMENT = "tool-window-layout"
private const val FORMAT_VERSION = 1

internal data class ImportedProfiles(
    val profiles: List<ImportedProfile>,
)

internal data class ImportedProfile(
    val profile: LayoutProfile,
    val nativeLayout: Element,
)

internal object LayoutProfileInterchange {
    fun write(profiles: List<LayoutProfile>): Element =
        Element(ROOT_ELEMENT)
            .setAttribute("version", FORMAT_VERSION.toString())
            .apply {
                profiles.forEach { profile ->
                    addContent(
                        Element(PROFILE_ELEMENT)
                            .setAttribute("id", profile.id)
                            .setAttribute("name", profile.displayName)
                            .apply {
                                if (profile.capturedAtEpochMillis > 0) {
                                    setAttribute(
                                        "captured-at",
                                        profile.capturedAtEpochMillis.toString(),
                                    )
                                }
                            }
                            .addContent(profile.uiElement())
                            .addContent(
                                requireNotNull(
                                    PlatformLayoutAdapter.export(profile.nativeLayoutName),
                                ) {
                                    "The native layout for “${profile.displayName}” is missing."
                                },
                            ),
                    )
                }
            }

    fun read(root: Element): ImportedProfiles {
        require(root.name == ROOT_ELEMENT) { "This is not an IDE Layout Profiles export." }
        require(root.requiredInt("version") == FORMAT_VERSION) {
            "Unsupported profile export version."
        }
        val profiles = root.getChildren(PROFILE_ELEMENT).mapIndexed { index, element ->
            element.toImportedProfile(index + 1)
        }
        require(profiles.isNotEmpty()) { "The profile export is empty." }
        require(profiles.map { it.profile.id }.distinct().size == profiles.size) {
            "The profile export contains duplicate profile IDs."
        }
        return ImportedProfiles(profiles)
    }

    private fun LayoutProfile.uiElement(): Element =
        Element(UI_ELEMENT)
            .setAttribute("main-toolbar", showMainToolbar.toString())
            .setAttribute("new-main-toolbar", showNewMainToolbar.toString())
            .setAttribute("main-menu", showMainMenu.toString())
            .setAttribute("navigation-bar", showNavigationBar.toString())
            .setAttribute("navigation-bar-location", navigationBarLocation)
            .setAttribute("tool-window-bars-hidden", hideToolStripes.toString())
            .setAttribute("status-bar", showStatusBar.toString())
            .setAttribute("editor-tab-placement", editorTabPlacement.toString())
            .setAttribute("widescreen", wideScreenSupport.toString())
            .apply {
                if (displayTopology.isNotBlank()) {
                    setAttribute("display-topology", displayTopology)
                }
            }

    private fun Element.toImportedProfile(number: Int): ImportedProfile {
        val id = required("id").trim()
        val displayName = required("name").trim()
        val capturedAt = getAttributeValue("captured-at")?.let { encoded ->
            encoded.toLongOrNull()?.takeIf { it >= 0 }
                ?: throw IllegalArgumentException(
                    "Profile “$displayName” has an invalid capture time.",
                )
        } ?: 0
        require(id.isNotEmpty()) { "Profile $number has no ID." }
        require(runCatching { UUID.fromString(id) }.isSuccess) {
            "Profile “$displayName” has an invalid ID."
        }
        require(displayName.isNotEmpty()) { "Profile $number has no name." }
        val ui = requireNotNull(getChild(UI_ELEMENT)) {
            "Profile “$displayName” has no UI settings."
        }
        val navigationBarLocation = ui.required("navigation-bar-location")
        require(NavBarLocation.entries.any { it.name == navigationBarLocation }) {
            "Profile “$displayName” has an invalid navigation bar location."
        }
        val editorTabPlacement = ui.requiredInt("editor-tab-placement")
        require(
            editorTabPlacement in setOf(
                -1,
                SwingConstants.TOP,
                SwingConstants.LEFT,
                SwingConstants.BOTTOM,
                SwingConstants.RIGHT,
            ),
        ) {
            "Profile “$displayName” has an invalid editor tab placement."
        }
        val nativeLayout = requireNotNull(getChild(LAYOUT_ELEMENT)) {
            "Profile “$displayName” has no tool-window layout."
        }
        val displayTopology = ui.getAttributeValue("display-topology").orEmpty()
        require(displayTopology.isBlank() || !DisplayTopology.parse(displayTopology).isEmpty) {
            "Profile “$displayName” has an invalid display topology."
        }

        return ImportedProfile(
            LayoutProfile().apply {
                this.id = id
                this.number = number
                this.displayName = displayName
                showMainToolbar = ui.requiredBoolean("main-toolbar")
                showNewMainToolbar = ui.requiredBoolean("new-main-toolbar")
                showMainMenu = ui.requiredBoolean("main-menu")
                showNavigationBar = ui.requiredBoolean("navigation-bar")
                this.navigationBarLocation = navigationBarLocation
                hideToolStripes = ui.requiredBoolean("tool-window-bars-hidden")
                showStatusBar = ui.requiredBoolean("status-bar")
                this.editorTabPlacement = editorTabPlacement
                wideScreenSupport = ui.requiredBoolean("widescreen")
                this.displayTopology = displayTopology
                capturedAtEpochMillis = capturedAt
            },
            nativeLayout.clone(),
        )
    }

    private fun Element.required(name: String): String =
        requireNotNull(getAttributeValue(name)) { "Missing “$name” in <${this.name}>." }

    private fun Element.requiredBoolean(name: String): Boolean =
        required(name).toBooleanStrictOrNull()
            ?: throw IllegalArgumentException("Invalid Boolean “$name” in <${this.name}>.")

    private fun Element.requiredInt(name: String): Int =
        required(name).toIntOrNull()
            ?: throw IllegalArgumentException("Invalid number “$name” in <${this.name}>.")
}
