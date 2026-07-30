package io.github.khopland

import com.intellij.ide.ui.NavBarLocation
import com.intellij.ide.ui.UISettings
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import org.jdom.Element
import java.util.UUID

internal enum class ApplyResult {
    APPLIED,
    PARTIALLY_APPLIED,
    EMPTY,
    MISSING_LAYOUT,
    FAILED,
    NO_TARGETS,
}

internal data class ApplyFailure(
    val projectName: String?,
    val cause: Exception,
)

internal data class ApplyOutcome(
    val result: ApplyResult,
    val appliedProjects: Int = 0,
    val failures: List<ApplyFailure> = emptyList(),
)

internal enum class StartupMode {
    NONE,
    PROFILE,
    BEST_MATCH,
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

internal data class LayoutProfileHealth(
    val nativeLayoutAvailable: Boolean,
    val topologyAvailable: Boolean,
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

    @Synchronized
    override fun getState(): LayoutProfilesState = savedState.deepCopy()

    @Synchronized
    override fun loadState(state: LayoutProfilesState) {
        val normalizedState = state.deepCopy()
        val activeNumber = normalizedState.activeSlot
        val startupProfileId = normalizedState.startupProfileId
        val startupBestMatch = normalizedState.startupBestMatch
        normalizedState.slots = normalizedState.slots
            .filter { it.number > 0 && it.displayName.isNotBlank() }
            .distinctBy { it.number }
            .sortedBy { it.number }
            .toMutableList()
        val activeProfile = normalizedState.slots.firstOrNull { it.number == activeNumber }
        val usedIds = mutableSetOf<String>()
        normalizedState.slots.forEachIndexed { index, profile ->
            if (profile.nativeLayoutName.isBlank()) {
                profile.nativeLayoutName = legacyLayoutName(profile.number)
            }
            profile.number = index + 1
            if (profile.id.isBlank() || !usedIds.add(profile.id)) {
                profile.id = UUID.randomUUID().toString()
                usedIds.add(profile.id)
            }
        }
        normalizedState.activeSlot = activeProfile?.let { normalizedState.slots.indexOf(it) + 1 } ?: 0
        normalizedState.startupBestMatch = startupBestMatch
        normalizedState.startupProfileId = if (startupBestMatch) {
            ""
        } else {
            startupProfileId.takeIf { id ->
                normalizedState.slots.any { it.id == id }
            }.orEmpty()
        }
        savedState = normalizedState
    }

    @Synchronized
    fun slot(number: Int): LayoutProfile? =
        savedState.slots.firstOrNull { it.number == number }?.deepCopy()

    @Synchronized
    fun profiles(): List<LayoutProfile> =
        savedState.slots.sortedBy { it.number }.map(LayoutProfile::deepCopy)

    @Synchronized
    fun profile(id: String): LayoutProfile? =
        savedState.slots.firstOrNull { it.id == id }?.deepCopy()

    @Synchronized
    fun profileHealth(id: String): LayoutProfileHealth? {
        val profile = savedState.slots.firstOrNull { it.id == id } ?: return null
        return LayoutProfileHealth(
            nativeLayoutAvailable = PlatformLayoutAdapter.exists(profile.nativeLayoutName),
            topologyAvailable = !DisplayTopology.parse(profile.displayTopology).isEmpty,
        )
    }

    @Synchronized
    fun nextProfileNumber(): Int = savedState.slots.size + 1

    @Synchronized
    fun activeSlot(): LayoutProfile? = slot(savedState.activeSlot)

    @Synchronized
    fun startupMode(): StartupMode = when {
        savedState.startupBestMatch -> StartupMode.BEST_MATCH
        savedState.startupProfileId.isNotBlank() -> StartupMode.PROFILE
        else -> StartupMode.NONE
    }

    @Synchronized
    fun startupProfile(): LayoutProfile? =
        savedState.startupProfileId.takeIf(String::isNotBlank)?.let(::profile)

    @Synchronized
    fun startupMatch(topology: DisplayTopology = DisplayTopology.current()): LayoutProfile? =
        when (startupMode()) {
            StartupMode.NONE -> null
            StartupMode.PROFILE -> startupProfile()
            StartupMode.BEST_MATCH -> bestMatch(topology)
        }

    @Synchronized
    fun setStartupProfile(id: String?) {
        require(id == null || profile(id) != null)
        savedState.startupProfileId = id.orEmpty()
        savedState.startupBestMatch = false
    }

    @Synchronized
    fun setStartupBestMatch() {
        savedState.startupProfileId = ""
        savedState.startupBestMatch = true
    }

    @Synchronized
    fun autoSwitchBestMatch(): Boolean = savedState.autoSwitchBestMatch

    @Synchronized
    fun setAutoSwitchBestMatch(enabled: Boolean) {
        savedState.autoSwitchBestMatch = enabled
    }

    @Synchronized
    fun bestMatch(topology: DisplayTopology = DisplayTopology.current()): LayoutProfile? =
        profiles()
            .mapNotNull { profile ->
                topology.distanceTo(DisplayTopology.parse(profile.displayTopology))
                    ?.let { distance -> profile to distance }
            }
            .minByOrNull { it.second }
            ?.first

    @Synchronized
    fun exportProfiles(): Element = LayoutProfileInterchange.write(profiles())

    @Synchronized
    fun exportProfiles(profileIds: Set<String>): Element {
        require(profileIds.isNotEmpty())
        val selected = profiles().filter { it.id in profileIds }
        require(selected.size == profileIds.size)
        return LayoutProfileInterchange.write(selected)
    }

    fun importProfiles(imported: ImportedProfiles): Int =
        importProfiles(imported, ImportMode.REPLACE_ALL).imported

    fun importProfiles(imported: ImportedProfiles, mode: ImportMode): ImportResult {
        val stateSnapshot = synchronized(this) {
            savedState.deepCopy()
        }
        val existingProfiles = stateSnapshot.slots.sortedBy(LayoutProfile::number)
        val existingById = existingProfiles.associateBy(LayoutProfile::id)
        val activeId = existingProfiles.firstOrNull { it.number == stateSnapshot.activeSlot }?.id
        val startupMode = when {
            stateSnapshot.startupBestMatch -> StartupMode.BEST_MATCH
            stateSnapshot.startupProfileId.isNotBlank() -> StartupMode.PROFILE
            else -> StartupMode.NONE
        }
        val startupProfileId = stateSnapshot.startupProfileId.takeIf(existingById::containsKey)
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
            startupBestMatch = startupMode == StartupMode.BEST_MATCH
            autoSwitchBestMatch = stateSnapshot.autoSwitchBestMatch
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
        val current = synchronized(this) {
            require(number in 1..savedState.slots.size + 1)
            savedState.slots.firstOrNull { it.number == number }?.deepCopy()
        }
        val id = current?.id ?: UUID.randomUUID().toString()
        val savedSlot = LayoutProfile.capture(number, displayName.trim()).apply {
            this.id = id
            nativeLayoutName = current?.nativeLayoutName ?: layoutName(id)
        }
        PlatformLayoutAdapter.save(project, savedSlot.nativeLayoutName)
        synchronized(this) {
            savedState.slots.removeAll { it.number == number }
            savedState.slots.add(savedSlot)
            savedState.slots.sortBy { it.number }
            savedState.activeSlot = number
        }
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

    @Synchronized
    fun duplicate(id: String): LayoutProfile? {
        val source = profile(id) ?: return null
        val duplicateId = UUID.randomUUID().toString()
        val duplicate = source.deepCopy().apply {
            this.id = duplicateId
            number = nextProfileNumber()
            displayName = copyName(source.displayName)
            nativeLayoutName = layoutName(duplicateId)
        }
        PlatformLayoutAdapter.copy(source.nativeLayoutName, duplicate.nativeLayoutName)
        savedState.slots.add(duplicate)
        return duplicate.deepCopy()
    }

    fun apply(project: Project, number: Int): ApplyOutcome {
        return apply(listOf(project), number)
    }

    fun apply(projects: Iterable<Project>, number: Int): ApplyOutcome {
        val savedSlot = synchronized(this) {
            savedState.slots.firstOrNull { it.number == number }?.deepCopy()
        } ?: return ApplyOutcome(ApplyResult.EMPTY)
        val targetProjects = projects.filterNot(Project::isDisposed)
        if (targetProjects.isEmpty()) return ApplyOutcome(ApplyResult.NO_TARGETS)
        return try {
            if (!PlatformLayoutAdapter.exists(savedSlot.nativeLayoutName)) {
                return ApplyOutcome(ApplyResult.MISSING_LAYOUT)
            }

            savedSlot.applyChrome()
            val failures = mutableListOf<ApplyFailure>()
            var appliedProjects = 0
            targetProjects.forEach { project ->
                try {
                    PlatformLayoutAdapter.apply(project, savedSlot.nativeLayoutName)
                    appliedProjects += 1
                } catch (error: Exception) {
                    rethrowControlFlow(error)
                    failures += ApplyFailure(project.name, error)
                }
            }
            val result = when {
                failures.isEmpty() -> ApplyResult.APPLIED
                appliedProjects > 0 -> ApplyResult.PARTIALLY_APPLIED
                else -> ApplyResult.FAILED
            }
            if (result == ApplyResult.APPLIED || result == ApplyResult.PARTIALLY_APPLIED) {
                synchronized(this) {
                    savedState.activeSlot = savedState.slots
                        .firstOrNull { it.id == savedSlot.id }
                        ?.number
                        ?: savedState.activeSlot
                }
            }
            ApplyOutcome(result, appliedProjects, failures)
        } catch (error: Exception) {
            rethrowControlFlow(error)
            ApplyOutcome(
                result = ApplyResult.FAILED,
                failures = listOf(ApplyFailure(null, error)),
            )
        }
    }

    fun clear(number: Int) {
        val cleared = synchronized(this) {
            savedState.slots.firstOrNull { it.number == number }?.deepCopy()
        }
        cleared?.let { PlatformLayoutAdapter.delete(it.nativeLayoutName) }
        synchronized(this) {
            val activeId = savedState.slots
                .firstOrNull { it.number == savedState.activeSlot }
                ?.id
            if (cleared != null) savedState.slots.removeAll { it.id == cleared.id }
            savedState.slots.forEachIndexed { index, profile -> profile.number = index + 1 }
            savedState.activeSlot = savedState.slots
                .firstOrNull { it.id == activeId }
                ?.number
                ?: 0
            if (savedState.slots.none { it.id == savedState.startupProfileId }) {
                savedState.startupProfileId = ""
            }
        }
    }

    @Synchronized
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

    private fun copyName(displayName: String): String {
        val usedNames = savedState.slots.mapTo(mutableSetOf(), LayoutProfile::displayName)
        return generateSequence(1) { it + 1 }
            .map { copyNumber ->
                if (copyNumber == 1) "$displayName Copy" else "$displayName Copy $copyNumber"
            }
            .first { it !in usedNames }
    }

    private fun rethrowControlFlow(error: Exception) {
        if (error is ProcessCanceledException || error is ControlFlowException) throw error
    }
}

internal class LayoutProfilesState {
    var activeSlot: Int = 0
    var startupProfileId: String = ""
    var startupBestMatch: Boolean = false
    var autoSwitchBestMatch: Boolean = false
    var slots: MutableList<LayoutProfile> = mutableListOf()

    fun deepCopy(): LayoutProfilesState = LayoutProfilesState().also { copy ->
        copy.activeSlot = activeSlot
        copy.startupProfileId = startupProfileId
        copy.startupBestMatch = startupBestMatch
        copy.autoSwitchBestMatch = autoSwitchBestMatch
        copy.slots = slots.map(LayoutProfile::deepCopy).toMutableList()
    }
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
    var capturedAtEpochMillis: Long = 0

    fun deepCopy(): LayoutProfile = LayoutProfile().also { copy ->
        copy.id = id
        copy.nativeLayoutName = nativeLayoutName
        copy.number = number
        copy.displayName = displayName
        copy.showMainToolbar = showMainToolbar
        copy.showNewMainToolbar = showNewMainToolbar
        copy.showMainMenu = showMainMenu
        copy.showNavigationBar = showNavigationBar
        copy.navigationBarLocation = navigationBarLocation
        copy.hideToolStripes = hideToolStripes
        copy.showStatusBar = showStatusBar
        copy.editorTabPlacement = editorTabPlacement
        copy.wideScreenSupport = wideScreenSupport
        copy.displayTopology = displayTopology
        copy.capturedAtEpochMillis = capturedAtEpochMillis
    }

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
                capturedAtEpochMillis = System.currentTimeMillis()
            }
        }
    }
}
