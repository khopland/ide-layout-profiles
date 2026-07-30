package io.github.khopland

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val NOTIFICATION_GROUP = "IDE Layout Profiles"
private const val PROFILE_ACTION_ID_PREFIX = "io.github.khopland.ideLayoutProfiles.profile."
private val PLUGIN_ID = PluginId.getId("io.github.khopland.ide-layout-profiles")
private val profileActionsLock = Any()
private val LOG = Logger.getInstance("io.github.khopland.LayoutProfileActions")
private const val BEST_MATCH_CACHE_MILLIS = 2_000L

internal fun profileActionId(profileId: String): String = "$PROFILE_ACTION_ID_PREFIX$profileId"

internal fun slotActionId(slotNumber: Int): String =
    "io.github.khopland.ideLayoutProfiles.applySlot$slotNumber"

abstract class ApplySlotAction(private val slotNumber: Int) : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        applyLayoutProfile(event.project ?: return, slotNumber)
    }

    override fun update(event: AnActionEvent) {
        val name = service().slot(slotNumber)?.displayName
        event.presentation.isEnabled = event.project != null
        event.presentation.text = if (name == null) {
            LayoutProfilesBundle.message("action.apply.empty.text", slotNumber)
        } else {
            LayoutProfilesBundle.message("action.apply.text", slotNumber, name)
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

class ApplySlot1Action : ApplySlotAction(1)
class ApplySlot2Action : ApplySlotAction(2)
class ApplySlot3Action : ApplySlotAction(3)
class ApplySlot4Action : ApplySlotAction(4)
class ApplySlot5Action : ApplySlotAction(5)
class ApplySlot6Action : ApplySlotAction(6)
class ApplySlot7Action : ApplySlotAction(7)
class ApplySlot8Action : ApplySlotAction(8)
class ApplySlot9Action : ApplySlotAction(9)
class ApplySlot10Action : ApplySlotAction(10)

class ApplyLayoutProfileGroup : ActionGroup(), DumbAware {
    override fun getChildren(event: AnActionEvent?): Array<AnAction> {
        syncProfileActions()
        val activeId = service().activeSlot()?.id
        return service().profiles()
            .map { ApplyLayoutProfileAction(it.id, it.displayName, it.id == activeId) }
            .toTypedArray()
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

class UpdateLayoutProfileGroup : ActionGroup(), DumbAware {
    override fun getChildren(event: AnActionEvent?): Array<AnAction> =
        service().profiles()
            .map { UpdateLayoutProfileAction(it.id, it.displayName) }
            .toTypedArray()

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

class StartupLayoutProfileGroup : ActionGroup(), DumbAware {
    override fun getChildren(event: AnActionEvent?): Array<AnAction> {
        val startupMode = service().startupMode()
        val startupId = service().startupProfile()?.id
        return buildList {
            add(
                SelectStartupLayoutProfileAction(
                    mode = StartupMode.NONE,
                    profileId = null,
                    displayName = "None",
                    selected = startupMode == StartupMode.NONE,
                ),
            )
            add(
                SelectStartupLayoutProfileAction(
                    mode = StartupMode.BEST_MATCH,
                    profileId = null,
                    displayName = "Best Match",
                    selected = startupMode == StartupMode.BEST_MATCH,
                ),
            )
            service().profiles().forEach { profile ->
                add(
                    SelectStartupLayoutProfileAction(
                        mode = StartupMode.PROFILE,
                        profileId = profile.id,
                        displayName = profile.displayName,
                        selected = startupMode == StartupMode.PROFILE && profile.id == startupId,
                    ),
                )
            }
        }.toTypedArray()
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

class ProfileActionsStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        syncProfileActions()
        ApplicationManager.getApplication()
            .getService(DisplayTopologyAutoSwitchService::class.java)
            .refresh()
        service().startupMatch()?.let { profile ->
            reportApplyOutcome(
                project = project,
                profile = profile,
                outcome = applyLayoutProfileWithUndo(listOf(project), profile.number),
                notifySuccess = false,
            )
        }
    }
}

private class ApplyLayoutProfileAction(
    private val profileId: String,
    displayName: String,
    active: Boolean,
) : DumbAwareAction(profileActionName(displayName, active)) {
    override fun actionPerformed(event: AnActionEvent) {
        val profile = service().profile(profileId) ?: return
        applyLayoutProfile(event.project ?: return, profile.number)
    }

    override fun update(event: AnActionEvent) {
        val profile = service().profile(profileId)
        event.presentation.isEnabledAndVisible = event.project != null && profile != null
        if (profile != null) {
            event.presentation.text = profileActionName(
                profile.displayName,
                service().activeSlot()?.id == profileId,
            )
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

private class UpdateLayoutProfileAction(
    private val profileId: String,
    displayName: String,
) : DumbAwareAction(displayName) {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        updateLayoutProfile(project, profileId)
    }

    override fun update(event: AnActionEvent) {
        val profile = service().profile(profileId)
        event.presentation.isEnabledAndVisible = event.project != null && profile != null
        if (profile != null) event.presentation.text = profile.displayName
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

private class SelectStartupLayoutProfileAction(
    private val mode: StartupMode,
    private val profileId: String?,
    displayName: String,
    selected: Boolean,
) : DumbAwareAction(profileActionName(displayName, selected)) {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        when (mode) {
            StartupMode.NONE -> {
                service().setStartupProfile(null)
                notify(project, "notification.startupCleared")
            }
            StartupMode.BEST_MATCH -> {
                service().setStartupBestMatch()
                notify(project, "notification.startupBestMatchSet")
            }
            StartupMode.PROFILE -> {
                val selectedProfile = service().profile(profileId ?: return) ?: return
                service().setStartupProfile(selectedProfile.id)
                notify(project, "notification.startupSet", selectedProfile.displayName)
            }
        }
    }

    override fun update(event: AnActionEvent) {
        val profile = profileId?.let(service()::profile)
        event.presentation.isEnabledAndVisible =
            event.project != null && (mode != StartupMode.PROFILE || profile != null)
        event.presentation.text = profileActionName(
            profile?.displayName ?: if (mode == StartupMode.BEST_MATCH) "Best Match" else "None",
            when (mode) {
                StartupMode.NONE -> service().startupMode() == StartupMode.NONE
                StartupMode.BEST_MATCH -> service().startupMode() == StartupMode.BEST_MATCH
                StartupMode.PROFILE ->
                    service().startupMode() == StartupMode.PROFILE &&
                        service().startupProfile()?.id == profileId
            },
        )
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

private class ApplySavedProfileAction(private val profileId: String) : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val profile = service().profile(profileId) ?: return
        applyLayoutProfile(event.project ?: return, profile.number)
    }

    override fun update(event: AnActionEvent) {
        val profile = service().profile(profileId)
        event.presentation.isEnabledAndVisible = event.project != null && profile != null
        if (profile != null) {
            event.presentation.text = LayoutProfilesBundle.message(
                "action.applyProfile.named.text",
                profile.displayName,
            )
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

class SaveNewLayoutProfileAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        saveNewLayoutProfile(event.project ?: return)
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

class ApplyBestMatchLayoutProfileAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val profile = bestMatchCache.refresh() ?: return
        applyLayoutProfile(project, profile.number)
    }

    override fun update(event: AnActionEvent) {
        val hasProject = event.project != null
        val profile = if (hasProject) bestMatchCache.current() else null
        event.presentation.isEnabled =
            hasProject && (!bestMatchCache.isInitialized() || profile != null)
        event.presentation.text = if (profile == null) {
            LayoutProfilesBundle.message("action.bestMatch.none.text")
        } else {
            LayoutProfilesBundle.message("action.bestMatch.text", profile.displayName)
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

class UpdateActiveLayoutProfileAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val active = service().activeSlot() ?: return
        updateLayoutProfile(project, active.id)
    }

    override fun update(event: AnActionEvent) {
        val active = service().activeSlot()
        event.presentation.isEnabled = event.project != null && active != null
        event.presentation.text = if (active == null) {
            LayoutProfilesBundle.message("action.update.none.text")
        } else {
            LayoutProfilesBundle.message("action.update.text",  active.displayName)
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

class ApplyActiveLayoutProfileToAllProjectsAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val active = service().activeSlot() ?: return
        val projects = ProjectManager.getInstance().openProjects.filterNot(Project::isDisposed)
        reportApplyOutcome(
            project = project,
            profile = active,
            outcome = applyLayoutProfileWithUndo(projects, active.number),
            allProjects = true,
        )
    }

    override fun update(event: AnActionEvent) {
        val active = service().activeSlot()
        event.presentation.isEnabled = event.project != null && active != null
        event.presentation.text = if (active == null) {
            LayoutProfilesBundle.message("action.applyAll.none.text")
        } else {
            LayoutProfilesBundle.message("action.applyAll.text", active.displayName)
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

class UndoLastLayoutProfileApplyAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        restoreLastLayoutProfileApply(event.project ?: return)
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled =
            event.project != null && undoService().hasSnapshot()
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

class OpenLayoutProfileSettingsAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(
            event.project ?: return,
            LayoutProfilesConfigurable::class.java,
        )
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

private fun service(): LayoutProfileService =
    ApplicationManager.getApplication().getService(LayoutProfileService::class.java)

private fun undoService(): LayoutProfileUndoService =
    ApplicationManager.getApplication().getService(LayoutProfileUndoService::class.java)

private val bestMatchCache = BestMatchCache()

private class BestMatchCache {
    private val refreshPending = AtomicBoolean()

    @Volatile
    private var profileId: String? = null

    @Volatile
    private var refreshedAtNanos: Long? = null

    @Volatile
    private var initialized = false

    fun current(): LayoutProfile? {
        scheduleRefreshIfStale()
        return profileId?.let(service()::profile)
    }

    fun isInitialized(): Boolean = initialized

    fun refresh(): LayoutProfile? {
        try {
            val profile = service().bestMatch()
            profileId = profile?.id
            initialized = true
            return profile
        } finally {
            refreshedAtNanos = System.nanoTime()
        }
    }

    private fun scheduleRefreshIfStale() {
        val refreshedAt = refreshedAtNanos
        if (refreshedAt != null) {
            val cacheAge = System.nanoTime() - refreshedAt
            if (cacheAge in 0 until TimeUnit.MILLISECONDS.toNanos(BEST_MATCH_CACHE_MILLIS)) return
        }
        if (!refreshPending.compareAndSet(false, true)) return
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                refresh()
            } catch (error: Exception) {
                rethrowControlFlow(error)
                LOG.debug("Could not refresh the best matching layout profile", error)
            } finally {
                refreshPending.set(false)
            }
        }
    }
}

internal fun syncProfileActions() = synchronized(profileActionsLock) {
    val actionManager = ActionManager.getInstance()
    val profilesByActionId = service().profiles().associateBy { profileActionId(it.id) }

    actionManager.getActionIdList(PROFILE_ACTION_ID_PREFIX)
        .filterNot(profilesByActionId::containsKey)
        .forEach(actionManager::unregisterAction)

    profilesByActionId.forEach { (actionId, profile) ->
        if (actionManager.getAction(actionId) !is ApplySavedProfileAction) {
            if (actionManager.getAction(actionId) != null) actionManager.unregisterAction(actionId)
            actionManager.registerAction(actionId, ApplySavedProfileAction(profile.id), PLUGIN_ID)
        }
        actionManager.getAction(actionId).templatePresentation.text =
            LayoutProfilesBundle.message("action.applyProfile.named.text", profile.displayName)
    }
}

private fun profileActionName(displayName: String, active: Boolean): String =
    if (active) "✓ $displayName" else displayName

internal fun applyLayoutProfile(project: Project, number: Int): ApplyOutcome {
    val profile = service().slot(number)
    val outcome = applyLayoutProfileWithUndo(listOf(project), number)
    reportApplyOutcome(project, profile, outcome, slotNumber = number)
    return outcome
}

internal fun applyLayoutProfileWithUndo(
    projects: List<Project>,
    number: Int,
): ApplyOutcome {
    val profile = service().slot(number)
    if (profile == null || !PlatformLayoutAdapter.exists(profile.nativeLayoutName)) {
        return service().apply(projects, number)
    }
    val undo = undoService()
    return synchronized(undo) {
        val captured = undo.capture(projects)
        val outcome = service().apply(projects, number)
        if (outcome.result == ApplyResult.APPLIED || outcome.result == ApplyResult.PARTIALLY_APPLIED) {
            captured?.let(undo::remember)
        } else {
            undo.discard(captured)
        }
        outcome
    }
}

internal fun updateLayoutProfile(project: Project, profileId: String): LayoutProfile? {
    val profile = service().profile(profileId) ?: return null
    return runProfileOperation(
        project = project,
        failureKey = "notification.updateFailed",
        failureParams = arrayOf(profile.displayName),
        description = "update layout profile ${profile.id}",
    ) {
        service().update(project, profileId)
    }.fold(
        onSuccess = { updated ->
            updated?.also { notify(project, "notification.updated", it.displayName) }
        },
        onFailure = { null },
    )
}

internal fun saveNewLayoutProfile(project: Project, name: String? = null): LayoutProfile? {
    val number = service().nextProfileNumber()
    val profileName = name ?: askForName(
        project,
        LayoutProfilesBundle.message("dialog.save.defaultName", number),
    ) ?: return null
    val saved = runProfileOperation(
        project = project,
        failureKey = "notification.saveFailed",
        failureParams = arrayOf(profileName),
        description = "save new layout profile",
    ) {
        service().save(project, number, profileName)
    }
    if (saved.isFailure) return null
    syncProfileActions()
    notify(project, "notification.saved", profileName)
    return service().slot(number)
}

internal fun reportApplyOutcome(
    project: Project,
    profile: LayoutProfile?,
    outcome: ApplyOutcome,
    allProjects: Boolean = false,
    notifySuccess: Boolean = true,
    slotNumber: Int = profile?.number ?: 0,
) {
    val profileName = profile?.displayName.orEmpty()
    outcome.failures.forEach { failure ->
        val projectContext = failure.projectName?.let { " in project $it" }.orEmpty()
        LOG.warn("Failed to apply layout profile ${profile?.id.orEmpty()}$projectContext", failure.cause)
    }
    when (outcome.result) {
        ApplyResult.APPLIED -> if (notifySuccess) {
            if (allProjects) {
                notify(
                    project,
                    "notification.appliedAll",
                    profileName,
                    outcome.appliedProjects,
                    action = undoNotificationAction(project),
                )
            } else {
                notify(
                    project,
                    "notification.applied",
                    profileName,
                    action = undoNotificationAction(project),
                )
            }
        }
        ApplyResult.PARTIALLY_APPLIED -> notify(
            project,
            "notification.appliedPartial",
            profileName,
            outcome.appliedProjects,
            outcome.failures.size,
            warning = true,
            action = undoNotificationAction(project),
        )
        ApplyResult.EMPTY -> notify(project, "notification.empty", slotNumber, warning = true)
        ApplyResult.MISSING_LAYOUT -> notify(
            project,
            "notification.missing",
            slotNumber,
            warning = true,
        )
        ApplyResult.FAILED -> notify(
            project,
            "notification.applyFailed",
            profileName,
            warning = true,
        )
        ApplyResult.NO_TARGETS -> notify(
            project,
            "notification.noTargetProjects",
            warning = true,
        )
    }
}

private fun undoNotificationAction(project: Project): AnAction? =
    if (undoService().hasSnapshot()) {
        NotificationAction.createSimple("Undo Layout Change") {
            restoreLastLayoutProfileApply(project)
        }
    } else {
        null
    }

private fun restoreLastLayoutProfileApply(project: Project) {
    val outcome = undoService().restore()
    if (outcome == null) {
        notify(project, "notification.undoUnavailable", warning = true)
        return
    }
    outcome.failures.forEach { failure ->
        LOG.warn("Failed to restore the layout before the last profile apply", failure.cause)
    }
    when (outcome.result) {
        ApplyResult.APPLIED -> notify(project, "notification.undoApplied")
        ApplyResult.PARTIALLY_APPLIED -> notify(
            project,
            "notification.undoPartial",
            outcome.appliedProjects,
            outcome.failures.size,
            warning = true,
        )
        else -> notify(project, "notification.undoFailed", warning = true)
    }
}

private fun <T> runProfileOperation(
    project: Project,
    failureKey: String,
    failureParams: Array<out Any>,
    description: String,
    operation: () -> T,
): Result<T> = try {
    Result.success(operation())
} catch (error: Exception) {
    rethrowControlFlow(error)
    LOG.warn("Failed to $description", error)
    notify(project, failureKey, *failureParams, warning = true)
    Result.failure(error)
}

private fun rethrowControlFlow(error: Exception) {
    if (error is ProcessCanceledException || error is ControlFlowException) throw error
}

internal fun askForName(project: Project, initialValue: String): String? =
    Messages.showInputDialog(
        project,
        LayoutProfilesBundle.message("dialog.save.prompt"),
        LayoutProfilesBundle.message("dialog.save.title"),
        Messages.getQuestionIcon(),
        initialValue,
        NonBlankInputValidator,
    )?.trim()

internal object NonBlankInputValidator : InputValidator {
    override fun checkInput(inputString: String): Boolean = inputString.isNotBlank()
    override fun canClose(inputString: String): Boolean = checkInput(inputString)
}

internal fun notify(
    project: Project?,
    key: String,
    vararg params: Any,
    warning: Boolean = false,
    action: AnAction? = null,
) {
    val notification = NotificationGroupManager.getInstance()
        .getNotificationGroup(NOTIFICATION_GROUP)
        .createNotification(
            LayoutProfilesBundle.message(key, *params),
            if (warning) NotificationType.WARNING else NotificationType.INFORMATION,
        )
    action?.let(notification::addAction)
    notification.notify(project)
}
