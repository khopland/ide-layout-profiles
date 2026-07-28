package io.github.khopland

import com.intellij.ide.ui.NavBarLocation
import com.intellij.ide.ui.UISettings
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import java.util.UUID

internal enum class ApplyResult {
    APPLIED,
    EMPTY,
    MISSING_LAYOUT,
}

internal data class LayoutProfileUpdate(
    val id: String,
    val displayName: String,
)

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
        val activeNumber = state.activeSlot
        state.slots = state.slots
            .filter { it.number > 0 && it.displayName.isNotBlank() }
            .distinctBy { it.number }
            .sortedBy { it.number }
            .toMutableList()
        val activeProfile = state.slots.firstOrNull { it.number == activeNumber }
        val usedIds = mutableSetOf<String>()
        state.slots.forEachIndexed { index, profile ->
            if (profile.nativeLayoutName.isBlank()) {
                profile.nativeLayoutName = legacyLayoutName(profile.number)
            }
            profile.number = index + 1
            if (profile.id.isBlank() || !usedIds.add(profile.id)) {
                profile.id = UUID.randomUUID().toString()
                usedIds.add(profile.id)
            }
        }
        state.activeSlot = activeProfile?.let { state.slots.indexOf(it) + 1 } ?: 0
        savedState = state
    }

    fun slot(number: Int): LayoutProfile? = savedState.slots.firstOrNull { it.number == number }

    fun profiles(): List<LayoutProfile> = savedState.slots.sortedBy { it.number }

    fun nextProfileNumber(): Int = savedState.slots.size + 1

    fun activeSlot(): LayoutProfile? = slot(savedState.activeSlot)

    fun save(project: Project, number: Int, displayName: String) {
        require(number in 1..nextProfileNumber())
        val current = slot(number)
        val id = current?.id ?: UUID.randomUUID().toString()
        val savedSlot = LayoutProfile.capture(number, displayName.trim()).apply {
            this.id = id
            nativeLayoutName = current?.nativeLayoutName ?: layoutName(id)
        }
        PlatformLayoutAdapter.save(project, savedSlot.nativeLayoutName)
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
        if (!PlatformLayoutAdapter.apply(project, savedSlot.nativeLayoutName)) return ApplyResult.MISSING_LAYOUT

        savedState.activeSlot = number
        savedSlot.applyChrome()
        return ApplyResult.APPLIED
    }

    fun clear(number: Int) {
        val activeId = activeSlot()?.id
        slot(number)?.let { PlatformLayoutAdapter.delete(it.nativeLayoutName) }
        savedState.slots.removeAll { it.number == number }
        savedState.slots.forEachIndexed { index, profile -> profile.number = index + 1 }
        savedState.activeSlot = savedState.slots
            .firstOrNull { it.id == activeId }
            ?.number
            ?: 0
    }

    fun updateProfiles(updates: List<LayoutProfileUpdate>) {
        require(updates.all { it.displayName.isNotBlank() })
        require(updates.map { it.id }.distinct().size == updates.size)

        val existing = savedState.slots.associateBy { it.id }
        require(updates.all { it.id in existing })
        val activeId = activeSlot()?.id
        val retainedIds = updates.mapTo(mutableSetOf(), LayoutProfileUpdate::id)
        savedState.slots
            .filterNot { it.id in retainedIds }
            .forEach { PlatformLayoutAdapter.delete(it.nativeLayoutName) }
        savedState.slots = updates.mapIndexed { index, update ->
            existing.getValue(update.id).apply {
                number = index + 1
                displayName = update.displayName.trim()
            }
        }.toMutableList()
        savedState.activeSlot = savedState.slots
            .firstOrNull { it.id == activeId }
            ?.number
            ?: 0
    }

    private fun layoutName(id: String): String = "[IDE Layout Profiles] Profile $id"

    private fun legacyLayoutName(number: Int): String = "[IDE Layout Profiles] Slot $number"
}

internal class LayoutProfilesState {
    var activeSlot: Int = 0
    var slots: MutableList<LayoutProfile> = mutableListOf()
}

internal class LayoutProfile {
    var id: String = ""
    var nativeLayoutName: String = ""
    var number: Int = 0
    var displayName: String = ""
    var showMainToolbar: Boolean = true
    var showNewMainToolbar: Boolean = true
    var showMainMenu: Boolean = true
    var showNavigationBar: Boolean = true
    var navigationBarLocation: String = NavBarLocation.TOP.name
    var hideToolStripes: Boolean = false
    var showStatusBar: Boolean = true

    fun applyChrome() {
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
            fireUISettingsChanged()
        }
    }

    companion object {
        fun capture(number: Int, displayName: String): LayoutProfile {
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
            }
        }
    }
}
