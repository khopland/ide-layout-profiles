package io.github.khopland

import com.intellij.ide.ui.NavBarLocation
import com.intellij.ide.ui.UISettings
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import org.jdom.Element
import java.util.UUID

internal enum class ApplyResult {
    APPLIED,
    EMPTY,
    MISSING_LAYOUT,
}

internal enum class ImportMode {
    ADD,
    UPDATE_EXISTING,
    COPY,
    REPLACE_ALL,
}

internal data class ImportResult(
    val imported: Int,
    val skipped: Int,
)

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
        val startupProfileId = state.startupProfileId
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
        state.startupProfileId = startupProfileId.takeIf { id ->
            state.slots.any { it.id == id }
        }.orEmpty()
        savedState = state
    }

    fun slot(number: Int): LayoutProfile? = savedState.slots.firstOrNull { it.number == number }

    fun profiles(): List<LayoutProfile> = savedState.slots.sortedBy { it.number }

    fun profile(id: String): LayoutProfile? = savedState.slots.firstOrNull { it.id == id }

    fun nextProfileNumber(): Int = savedState.slots.size + 1

    fun activeSlot(): LayoutProfile? = slot(savedState.activeSlot)

    fun startupProfile(): LayoutProfile? = profile(savedState.startupProfileId)

    fun setStartupProfile(id: String?) {
        require(id == null || profile(id) != null)
        savedState.startupProfileId = id.orEmpty()
    }

    fun bestMatch(topology: DisplayTopology = DisplayTopology.current()): LayoutProfile? =
        profiles()
            .mapNotNull { profile ->
                topology.distanceTo(DisplayTopology.parse(profile.displayTopology))
                    ?.let { distance -> profile to distance }
            }
            .minByOrNull { it.second }
            ?.first

    fun exportProfiles(): Element = LayoutProfileInterchange.write(profiles())

    fun exportProfiles(profileIds: Set<String>): Element {
        require(profileIds.isNotEmpty())
        val selected = profiles().filter { it.id in profileIds }
        require(selected.size == profileIds.size)
        return LayoutProfileInterchange.write(selected)
    }

    fun importProfiles(imported: ImportedProfiles): Int =
        importProfiles(imported, ImportMode.REPLACE_ALL).imported

    fun importProfiles(imported: ImportedProfiles, mode: ImportMode): ImportResult {
        val existingProfiles = profiles()
        val existingById = existingProfiles.associateBy(LayoutProfile::id)
        val activeId = activeSlot()?.id
        val startupProfileId = startupProfile()?.id
        val selectedImports = when (mode) {
            ImportMode.ADD -> imported.profiles.filter { it.profile.id !in existingById }
            ImportMode.UPDATE_EXISTING -> imported.profiles.filter { it.profile.id in existingById }
            ImportMode.COPY -> {
                val usedIds = existingById.keys.toMutableSet()
                imported.profiles.onEach { importedProfile ->
                    importedProfile.profile.id = generateSequence { UUID.randomUUID().toString() }
                        .first(usedIds::add)
                }
            }
            ImportMode.REPLACE_ALL -> imported.profiles
        }

        selectedImports.forEach { importedProfile ->
            importedProfile.profile.nativeLayoutName = layoutName(importedProfile.profile.id)
        }
        val replacementsById = selectedImports.associateBy { it.profile.id }
        val finalProfiles = when (mode) {
            ImportMode.ADD,
            ImportMode.COPY,
                -> existingProfiles + selectedImports.map(ImportedProfile::profile)
            ImportMode.UPDATE_EXISTING -> existingProfiles.map { profile ->
                replacementsById[profile.id]?.profile ?: profile
            }
            ImportMode.REPLACE_ALL -> selectedImports.map(ImportedProfile::profile)
        }
        val newState = LayoutProfilesState().apply {
            slots = finalProfiles.onEachIndexed { index, profile ->
                profile.number = index + 1
            }.toMutableList()
            activeSlot = if (mode == ImportMode.REPLACE_ALL) {
                0
            } else {
                slots.indexOfFirst { it.id == activeId }.takeIf { it >= 0 }?.plus(1) ?: 0
            }
            this.startupProfileId = startupProfileId.takeIf { id ->
                slots.any { it.id == id }
            }.orEmpty()
        }
        val newLayouts = selectedImports.associate { importedProfile ->
            importedProfile.profile.nativeLayoutName to importedProfile.nativeLayout
        }
        val obsoleteLayoutNames = when (mode) {
            ImportMode.UPDATE_EXISTING -> selectedImports
                .mapNotNull { existingById[it.profile.id]?.nativeLayoutName }
                .toSet() - newLayouts.keys
            ImportMode.REPLACE_ALL -> existingProfiles
                .mapTo(mutableSetOf(), LayoutProfile::nativeLayoutName) - newLayouts.keys
            ImportMode.ADD,
            ImportMode.COPY,
                -> emptySet()
        }
        PlatformLayoutAdapter.replace(newLayouts, obsoleteLayoutNames)
        loadState(newState)
        return ImportResult(
            imported = selectedImports.size,
            skipped = imported.profiles.size - selectedImports.size,
        )
    }

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
        return update(project, current.id)
    }

    fun update(project: Project, id: String): LayoutProfile? {
        val current = profile(id) ?: return null
        save(project, current.number, current.displayName)
        return profile(id)
    }

    fun apply(project: Project, number: Int): ApplyResult {
        return apply(listOf(project), number)
    }

    fun apply(projects: Iterable<Project>, number: Int): ApplyResult {
        val savedSlot = slot(number) ?: return ApplyResult.EMPTY
        if (!PlatformLayoutAdapter.exists(savedSlot.nativeLayoutName)) return ApplyResult.MISSING_LAYOUT

        savedSlot.applyChrome()
        projects.filterNot(Project::isDisposed)
            .forEach { PlatformLayoutAdapter.apply(it, savedSlot.nativeLayoutName) }
        savedState.activeSlot = number
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
        if (startupProfile() == null) savedState.startupProfileId = ""
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
        if (startupProfile() == null) savedState.startupProfileId = ""
    }

    private fun layoutName(id: String): String = "[IDE Layout Profiles] Profile $id"

    private fun legacyLayoutName(number: Int): String = "[IDE Layout Profiles] Slot $number"
}

internal class LayoutProfilesState {
    var activeSlot: Int = 0
    var startupProfileId: String = ""
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
    var editorTabPlacement: Int = -1
    var wideScreenSupport: Boolean = false
    var displayTopology: String = ""

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
            if (this@LayoutProfile.editorTabPlacement >= 0) {
                this.editorTabPlacement = this@LayoutProfile.editorTabPlacement
                wideScreenSupport = this@LayoutProfile.wideScreenSupport
            }
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
                editorTabPlacement = ui.editorTabPlacement
                wideScreenSupport = ui.wideScreenSupport
                displayTopology = DisplayTopology.current().serialize()
            }
        }
    }
}
