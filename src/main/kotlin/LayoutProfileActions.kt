package io.github.khopland

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
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

class SaveNewLayoutProfileAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val slot = service().firstEmptySlot()
        if (slot == null) {
            notify(project, "notification.full", warning = true)
            return
        }

        val name = askForName(project, LayoutProfilesBundle.message("dialog.save.defaultName", slot)) ?: return
        service().save(project, slot, name)
        notify(project, "notification.saved", name, slot)
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
        notify(project, "notification.updated", updated.displayName, updated.number)
    }

    override fun update(event: AnActionEvent) {
        val active = service().activeSlot()
        event.presentation.isEnabled = event.project != null && active != null
        event.presentation.text = if (active == null) {
            LayoutProfilesBundle.message("action.update.none.text")
        } else {
            LayoutProfilesBundle.message("action.update.text", active.number, active.displayName)
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}

class ManageLayoutProfilesAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val slots = (1..LAYOUT_PROFILE_SLOT_COUNT).map { number ->
            val name = service().slot(number)?.displayName
            if (name == null) {
                LayoutProfilesBundle.message("dialog.manage.emptySlot", number)
            } else {

                LayoutProfilesBundle.message("dialog.manage.savedSlot", number, name)
            }
        }.toTypedArray()

        val index = choose(
            project,
            LayoutProfilesBundle.message("dialog.manage.prompt"),
            LayoutProfilesBundle.message("dialog.manage.title"),
            slots,
        )
        if (index < 0) return

        val slotNumber = index + 1
        val savedSlot = service().slot(slotNumber)
        if (savedSlot == null) {
            val name = askForName(project, LayoutProfilesBundle.message("dialog.save.defaultName", slotNumber)) ?: return
            service().save(project, slotNumber, name)
            notify(project, "notification.saved", name, slotNumber)
            return
        }

        val operations = arrayOf(
            LayoutProfilesBundle.message("dialog.manage.apply"),
            LayoutProfilesBundle.message("dialog.manage.replace"),
            LayoutProfilesBundle.message("dialog.manage.rename"),
            LayoutProfilesBundle.message("dialog.manage.clear"),
        )
        when (
            choose(
                project,
                LayoutProfilesBundle.message("dialog.manage.operationPrompt", savedSlot.displayName),
                LayoutProfilesBundle.message("dialog.manage.title"),
                operations,
            )
        ) {
            0 -> when (service().apply(project, slotNumber)) {
                ApplyResult.APPLIED -> notify(project, "notification.applied", savedSlot.displayName)
                else -> notify(project, "notification.missing", slotNumber, warning = true)
            }

            1 -> replace(project, savedSlot)
            2 -> rename(project, savedSlot)
            3 -> clear(project, savedSlot)
        }
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    private fun replace(project: Project, slot: LayoutProfile) {
        val answer = Messages.showYesNoDialog(
            project,
            LayoutProfilesBundle.message("dialog.replace.message", slot.displayName),
            LayoutProfilesBundle.message("dialog.replace.title"),
            Messages.getWarningIcon(),
        )
        if (answer != Messages.YES) return
        service().save(project, slot.number, slot.displayName)
        notify(project, "notification.updated", slot.displayName, slot.number)
    }

    private fun rename(project: Project, slot: LayoutProfile) {
        val name = askForName(project, slot.displayName) ?: return
        service().rename(slot.number, name)
    }

    private fun clear(project: Project, slot: LayoutProfile) {
        val answer = Messages.showYesNoDialog(
            project,
            LayoutProfilesBundle.message("dialog.clear.message", slot.displayName),
            LayoutProfilesBundle.message("dialog.clear.title"),
            Messages.getWarningIcon(),
        )
        if (answer != Messages.YES) return
        service().clear(slot.number)
        notify(project, "notification.cleared", slot.displayName)
    }
}

private fun service(): LayoutProfileService =
    ApplicationManager.getApplication().getService(LayoutProfileService::class.java)

private fun askForName(project: Project, initialValue: String): String? =
    Messages.showInputDialog(
        project,
        LayoutProfilesBundle.message("dialog.save.prompt"),
        LayoutProfilesBundle.message("dialog.save.title"),
        Messages.getQuestionIcon(),
        initialValue,
        NonBlankInputValidator,
    )?.trim()

private object NonBlankInputValidator : InputValidator {
    override fun checkInput(inputString: String): Boolean = inputString.isNotBlank()
    override fun canClose(inputString: String): Boolean = checkInput(inputString)
}

@Suppress("DEPRECATION")
private fun choose(project: Project, message: String, title: String, options: Array<String>): Int =
    Messages.showChooseDialog(
        project,
        message,
        title,
        Messages.getQuestionIcon(),
        options,
        options.first(),
    )

private fun notify(project: Project, key: String, vararg params: Any, warning: Boolean = false) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup(NOTIFICATION_GROUP)
        .createNotification(
            LayoutProfilesBundle.message(key, *params),
            if (warning) NotificationType.WARNING else NotificationType.INFORMATION,
        )
        .notify(project)
}
