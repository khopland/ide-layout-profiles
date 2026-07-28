package io.github.khopland

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages

private const val NOTIFICATION_GROUP = "IDE Layout Profiles"

abstract class ApplySlotAction(private val slotNumber: Int) : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        when (service().apply(project, slotNumber)) {
            ApplyResult.APPLIED -> notify(project, "notification.applied", service().slot(slotNumber)!!.displayName)
            ApplyResult.EMPTY -> notify(project, "notification.empty", slotNumber, warning = true)
            ApplyResult.MISSING_LAYOUT -> notify(project, "notification.missing", slotNumber, warning = true)
        }
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

internal fun saveNewLayoutProfile(project: Project, name: String? = null): LayoutProfile? {
    val number = service().nextProfileNumber()
    val profileName = name ?: askForName(
        project,
        LayoutProfilesBundle.message("dialog.save.defaultName", number),
    ) ?: return null
    service().save(project, number, profileName)
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
