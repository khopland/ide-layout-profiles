package io.github.khopland

import com.intellij.ide.ui.NavBarLocation
import com.intellij.ide.ui.UISettings
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

internal const val LAYOUT_PROFILE_SLOT_COUNT = 5

internal enum class ApplyResult {
    APPLIED,
    EMPTY,
    MISSING_LAYOUT,
}

@Service(Service.Level.APP)
@State(
    name = "io.github.khopland.ideLayoutProfiles",
    storages = [Storage("ide-layout-profiles.xml")],
    category = SettingsCategory.UI,
)
internal class LayoutProfileService : PersistentStateComponent<LayoutProfilesState> {
    @Volatile
    private var savedState = LayoutProfilesState()

    override fun getState(): LayoutProfilesState = savedState

    override fun loadState(state: LayoutProfilesState) {
        state.slots = state.slots
            .filter { it.number in 1..LAYOUT_PROFILE_SLOT_COUNT && it.displayName.isNotBlank() }
            .distinctBy { it.number }
            .sortedBy { it.number }
            .toMutableList()
        if (state.activeSlot !in state.slots.map { it.number }) state.activeSlot = 0
        savedState = state
    }

    fun slot(number: Int): LayoutProfile? = savedState.slots.firstOrNull { it.number == number }

    fun firstEmptySlot(): Int? = (1..LAYOUT_PROFILE_SLOT_COUNT).firstOrNull { slot(it) == null }

    fun activeSlot(): LayoutProfile? = slot(savedState.activeSlot)

    fun save(project: Project, number: Int, displayName: String) {
        require(number in 1..LAYOUT_PROFILE_SLOT_COUNT)
        val savedSlot = LayoutProfile.capture(project, number, displayName.trim())
        PlatformLayoutAdapter.save(project, layoutName(number))
        savedState.slots.removeAll { it.number == number }
        savedState.slots.add(savedSlot)
        savedState.slots.sortBy { it.number }
        savedState.activeSlot = number
    }

    fun updateActive(project: Project): LayoutProfile? {
        val current = activeSlot() ?: return null
        save(project, current.number, current.displayName)
        return slot(current.number)
    }

    fun apply(project: Project, number: Int): ApplyResult {
        val savedSlot = slot(number) ?: return ApplyResult.EMPTY
        if (!PlatformLayoutAdapter.apply(project, layoutName(number))) return ApplyResult.MISSING_LAYOUT

        savedState.activeSlot = number
        savedSlot.applyChrome(project)
        return ApplyResult.APPLIED
    }

    fun rename(number: Int, displayName: String) {
        slot(number)?.displayName = displayName.trim()
    }

    fun clear(number: Int) {
        PlatformLayoutAdapter.delete(layoutName(number))
        savedState.slots.removeAll { it.number == number }
        if (savedState.activeSlot == number) savedState.activeSlot = 0
    }

    private fun layoutName(number: Int): String = "[IDE Layout Profiles] Slot $number"
}

internal class LayoutProfilesState {
    var activeSlot: Int = 0
    var slots: MutableList<LayoutProfile> = mutableListOf()
}

internal class LayoutProfile {
    var number: Int = 0
    var displayName: String = ""
    var showMainToolbar: Boolean = true
    var showNewMainToolbar: Boolean = true
    var showMainMenu: Boolean = true
    var showNavigationBar: Boolean = true
    var navigationBarLocation: String = NavBarLocation.TOP.name
    var hideToolStripes: Boolean = false
    var showStatusBar: Boolean = true
    var hasMainToolbarSnapshot: Boolean = false
    var mainToolbarSnapshot: String = ""
    var hasStatusBarWidgetSnapshot: Boolean = false
    var statusBarWidgets: MutableMap<String, Boolean> = linkedMapOf()

    fun applyChrome(project: Project) {
        UISettings.getInstance().apply {
            showMainToolbar = this@LayoutProfile.showMainToolbar
            showNewMainToolbar = this@LayoutProfile.showNewMainToolbar
            showMainMenu = this@LayoutProfile.showMainMenu
            showNavigationBar = this@LayoutProfile.showNavigationBar
            navBarLocation = NavBarLocation.entries
                .firstOrNull { it.name == navigationBarLocation }
                ?: navBarLocation
            hideToolStripes = this@LayoutProfile.hideToolStripes
            showStatusBar = this@LayoutProfile.showStatusBar
            MacToolbarRefresh.update(showNewMainToolbar)
            fireUISettingsChanged()
        }
        if (hasStatusBarWidgetSnapshot) {
            StatusBarWidgetAdapter.apply(project, statusBarWidgets)
        }
        if (hasMainToolbarSnapshot) {
            MainToolbarAdapter.apply(mainToolbarSnapshot)
        }
    }

    companion object {
        fun capture(project: Project, number: Int, displayName: String): LayoutProfile {
            val ui = UISettings.getInstance()
            return LayoutProfile().apply {
                this.number = number
                this.displayName = displayName
                showMainToolbar = ui.showMainToolbar
                showNewMainToolbar = ui.showNewMainToolbar
                showMainMenu = ui.showMainMenu
                showNavigationBar = ui.showNavigationBar
                navigationBarLocation = ui.navBarLocation.name
                hideToolStripes = ui.hideToolStripes
                showStatusBar = ui.showStatusBar
                mainToolbarSnapshot = MainToolbarAdapter.capture()
                hasMainToolbarSnapshot = mainToolbarSnapshot.isNotEmpty()
                hasStatusBarWidgetSnapshot = true
                statusBarWidgets = StatusBarWidgetAdapter.capture(project).toMutableMap()
            }
        }
    }
}
