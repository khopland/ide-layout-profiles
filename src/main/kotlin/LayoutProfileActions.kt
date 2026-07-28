package io.github.khopland

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages

private const val NOTIFICATION_GROUP = "IDE Layout Profiles"
private const val PROFILE_ACTION_ID_PREFIX = "io.github.khopland.ideLayoutProfiles.profile."
private val PLUGIN_ID = PluginId.getId("io.github.khopland.ide-layout-profiles")
private val profileActionsLock = Any()

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

class ProfileActionsStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        syncProfileActions()
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

class UpdateActiveLayoutProfileAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val updated = service().updateActive(project) ?: return
        notify(project, "notification.updated", updated.displayName)
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
        when (service().apply(projects, active.number)) {
            ApplyResult.APPLIED -> notify(
                project,
                "notification.appliedAll",
                active.displayName,
                projects.size,
            )
            ApplyResult.EMPTY -> notify(project, "notification.empty", active.number, warning = true)
            ApplyResult.MISSING_LAYOUT -> notify(project, "notification.missing", active.number, warning = true)
        }
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

private fun applyLayoutProfile(project: Project, number: Int) {
    when (service().apply(project, number)) {
        ApplyResult.APPLIED -> notify(project, "notification.applied", service().slot(number)!!.displayName)
        ApplyResult.EMPTY -> notify(project, "notification.empty", number, warning = true)
        ApplyResult.MISSING_LAYOUT -> notify(project, "notification.missing", number, warning = true)
    }
}

internal fun saveNewLayoutProfile(project: Project, name: String? = null): LayoutProfile? {
    val number = service().nextProfileNumber()
    val profileName = name ?: askForName(
        project,
        LayoutProfilesBundle.message("dialog.save.defaultName", number),
    ) ?: return null
    service().save(project, number, profileName)
    syncProfileActions()
    notify(project, "notification.saved", profileName)
    return service().slot(number)
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

internal fun notify(project: Project, key: String, vararg params: Any, warning: Boolean = false) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup(NOTIFICATION_GROUP)
        .createNotification(
            LayoutProfilesBundle.message(key, *params),
            if (warning) NotificationType.WARNING else NotificationType.INFORMATION,
        )
        .notify(project)
}
