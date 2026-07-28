package io.github.khopland

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.JDOMUtil
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel

internal const val LAYOUT_PROFILE_SETTINGS_ID = "io.github.khopland.ideLayoutProfiles.settings"
private const val SHORTCUT_SLOT_COUNT = 10

class LayoutProfilesConfigurable(private val project: Project) : SearchableConfigurable {
    private var model: DefaultListModel<ProfileDraft>? = null
    private var profileList: JBList<ProfileDraft>? = null

    override fun getId(): String = LAYOUT_PROFILE_SETTINGS_ID

    override fun getDisplayName(): String = "IDE Layout Profiles"

    override fun createComponent(): JComponent {
        syncProfileActions()
        val listModel = DefaultListModel<ProfileDraft>()
        val list = JBList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            emptyText.text = "No saved layout profiles"
            cellRenderer = profileRenderer()
        }
        model = listModel
        profileList = list

        val createNew = JButton("Create New")
        val rename = JButton("Rename")
        val delete = JButton("Delete")
        val moveUp = JButton("Move Up")
        val moveDown = JButton("Move Down")
        val applyProfile = JButton("Apply Profile")
        val updateProfile = JButton("Update from Current")
        val importProfiles = JButton("Import…")
        val exportProfiles = JButton("Export…")

        fun updateButtons() {
            val index = list.selectedIndex
            rename.isEnabled = index >= 0
            delete.isEnabled = index >= 0
            moveUp.isEnabled = index > 0
            moveDown.isEnabled = index in 0 until listModel.size - 1
            applyProfile.isEnabled = index >= 0
            updateProfile.isEnabled = index >= 0
            exportProfiles.isEnabled = !listModel.isEmpty
        }

        createNew.addActionListener {
            val name = askForName(
                project,
                LayoutProfilesBundle.message("dialog.save.defaultName", listModel.size + 1),
            ) ?: return@addActionListener
            val saved = saveNewLayoutProfile(project, name) ?: return@addActionListener
            listModel.addElement(ProfileDraft(saved.id, saved.displayName))
            list.selectedIndex = listModel.size - 1
            updateButtons()
        }
        rename.addActionListener {
            val index = list.selectedIndex
            val selected = list.selectedValue ?: return@addActionListener
            val name = Messages.showInputDialog(
                project,
                "Name this layout profile:",
                "Rename Layout Profile",
                Messages.getQuestionIcon(),
                selected.displayName,
                NonBlankInputValidator,
            )?.trim() ?: return@addActionListener
            listModel.set(index, selected.copy(displayName = name))
        }
        delete.addActionListener {
            val index = list.selectedIndex
            val selected = list.selectedValue ?: return@addActionListener
            if (
                Messages.showYesNoDialog(
                    project,
                    "Delete “${selected.displayName}”?",
                    "Delete Layout Profile",
                    Messages.getWarningIcon(),
                ) != Messages.YES
            ) {
                return@addActionListener
            }
            listModel.remove(index)
            if (!listModel.isEmpty) list.selectedIndex = index.coerceAtMost(listModel.size - 1)
            updateButtons()
        }

        fun move(offset: Int) {
            val index = list.selectedIndex
            val destination = index + offset
            if (index !in 0 until listModel.size || destination !in 0 until listModel.size) return
            val selected = listModel.remove(index)
            listModel.add(destination, selected)
            list.selectedIndex = destination
            updateButtons()
        }
        moveUp.addActionListener { move(-1) }
        moveDown.addActionListener { move(1) }
        applyProfile.addActionListener {
            val selected = list.selectedValue ?: return@addActionListener
            val profile = service().profile(selected.id) ?: return@addActionListener
            when (service().apply(project, profile.number)) {
                ApplyResult.APPLIED -> notify(project, "notification.applied", profile.displayName)
                ApplyResult.EMPTY -> notify(project, "notification.empty", profile.number, warning = true)
                ApplyResult.MISSING_LAYOUT -> notify(project, "notification.missing", profile.number, warning = true)
            }
            list.repaint()
        }
        updateProfile.addActionListener {
            val selected = list.selectedValue ?: return@addActionListener
            val updated = service().update(project, selected.id) ?: return@addActionListener
            notify(project, "notification.updated", updated.displayName)
            list.repaint()
        }
        importProfiles.addActionListener {
            val file = FileChooser.chooseFile(
                FileChooserDescriptorFactory.createSingleFileDescriptor("xml")
                    .withTitle("Import Layout Profiles"),
                project,
                null,
            ) ?: return@addActionListener
            try {
                val imported = LayoutProfileInterchange.read(JDOMUtil.load(file.toNioPath()))
                if (
                    !listModel.isEmpty &&
                    Messages.showYesNoDialog(
                        project,
                        "Replace all current layout profiles with ${imported.profiles.size} imported profiles?",
                        "Import Layout Profiles",
                        Messages.getWarningIcon(),
                    ) != Messages.YES
                ) {
                    return@addActionListener
                }
                val count = service().importProfiles(imported)
                syncProfileActions()
                reset()
                updateButtons()
                notify(project, "notification.imported", count)
            } catch (error: Exception) {
                Messages.showErrorDialog(
                    project,
                    error.cause?.message ?: error.message ?: "Could not import layout profiles.",
                    "Import Layout Profiles",
                )
            }
        }
        exportProfiles.addActionListener {
            val file = FileChooserFactory.getInstance()
                .createSaveFileDialog(
                    FileSaverDescriptor(
                        "Export Layout Profiles",
                        "Save all layout profiles to a portable file.",
                        "xml",
                    ),
                    project,
                )
                .save("ide-layout-profiles.xml")
                ?: return@addActionListener
            try {
                JDOMUtil.write(service().exportProfiles(), file.file.toPath())
                notify(project, "notification.exported", file.file.name)
            } catch (error: Exception) {
                Messages.showErrorDialog(
                    project,
                    error.cause?.message ?: error.message ?: "Could not export layout profiles.",
                    "Export Layout Profiles",
                )
            }
        }
        list.addListSelectionListener { updateButtons() }

        val buttons = JPanel(WrappingFlowLayout(FlowLayout.LEADING, JBUI.scale(8), 0)).apply {
            add(createNew)
            add(rename)
            add(delete)
            add(moveUp)
            add(moveDown)
            add(applyProfile)
            add(updateProfile)
            add(importProfiles)
            add(exportProfiles)
        }
        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(8)
            add(
                JLabel("The first ten profiles are assigned to the Apply Slot 1–10 keybindings."),
                BorderLayout.NORTH,
            )
            add(JBScrollPane(list), BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
            reset()
            updateButtons()
        }
    }

    override fun isModified(): Boolean = drafts() != savedDrafts()

    override fun apply() {
        service().updateProfiles(drafts().map { LayoutProfileUpdate(it.id, it.displayName) })
        syncProfileActions()
    }

    override fun reset() {
        val listModel = model ?: return
        listModel.removeAllElements()
        savedDrafts().forEach(listModel::addElement)
        if (!listModel.isEmpty) profileList?.selectedIndex = 0
    }

    override fun disposeUIResources() {
        model = null
        profileList = null
    }

    private fun drafts(): List<ProfileDraft> {
        val listModel = model ?: return emptyList()
        return (0 until listModel.size).map(listModel::getElementAt)
    }

    private fun savedDrafts(): List<ProfileDraft> =
        service().profiles().map { ProfileDraft(it.id, it.displayName) }

    private fun service(): LayoutProfileService =
        ApplicationManager.getApplication().getService(LayoutProfileService::class.java)

    private fun profileRenderer(): ListCellRenderer<in ProfileDraft> {
        val renderer = DefaultListCellRenderer()
        return ListCellRenderer { list: JList<out ProfileDraft>, value, index, selected, focused ->
            val shortcut = profileShortcutText(value.id, index + 1)
                .takeIf(String::isNotEmpty)
                ?.let { " — $it" }
                .orEmpty()
            val active = if (service().activeSlot()?.id == value.id) "✓ " else ""
            renderer.getListCellRendererComponent(
                list,
                "$active${index + 1}. ${value.displayName}$shortcut",
                index,
                selected,
                focused,
            )
        }
    }

    private fun profileShortcutText(profileId: String, slotNumber: Int): String {
        val actionIds = buildList {
            add(profileActionId(profileId))
            if (slotNumber <= SHORTCUT_SLOT_COUNT) add(slotActionId(slotNumber))
        }
        val keymap = KeymapManager.getInstance().activeKeymap
        val shortcuts = actionIds
            .flatMap { keymap.getShortcuts(it).asList() }
            .distinct()
            .toTypedArray()
        return KeymapUtil.getShortcutsText(shortcuts)
    }

    private data class ProfileDraft(
        val id: String,
        val displayName: String,
    )
}

private class WrappingFlowLayout(
    align: Int,
    horizontalGap: Int,
    verticalGap: Int,
) : FlowLayout(align, horizontalGap, verticalGap) {
    override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, false)

    override fun minimumLayoutSize(target: Container): Dimension = layoutSize(target, true)

    private fun layoutSize(target: Container, minimum: Boolean): Dimension {
        val insets = target.insets
        val parentWidth = target.parent?.let {
            it.width - it.insets.left - it.insets.right
        } ?: 0
        val availableWidth = (parentWidth.takeIf { it > 0 } ?: target.width)
            .takeIf { it > 0 }
            ?.minus(insets.left + insets.right + hgap * 2)
            ?: Int.MAX_VALUE
        var width = 0
        var height = 0
        var rowWidth = 0
        var rowHeight = 0

        target.components.filter(Component::isVisible).forEach { component ->
            val size = if (minimum) component.minimumSize else component.preferredSize
            val gap = if (rowWidth == 0) 0 else hgap
            if (rowWidth + gap + size.width > availableWidth) {
                width = maxOf(width, rowWidth)
                height += rowHeight + if (height == 0) 0 else vgap
                rowWidth = size.width
                rowHeight = size.height
            } else {
                rowWidth += gap + size.width
                rowHeight = maxOf(rowHeight, size.height)
            }
        }
        width = maxOf(width, rowWidth)
        height += rowHeight
        return Dimension(
            width + insets.left + insets.right + hgap * 2,
            height + insets.top + insets.bottom + vgap * 2,
        )
    }
}
