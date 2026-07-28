package io.github.khopland

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
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

        fun updateButtons() {
            val index = list.selectedIndex
            rename.isEnabled = index >= 0
            delete.isEnabled = index >= 0
            moveUp.isEnabled = index > 0
            moveDown.isEnabled = index in 0 until listModel.size - 1
            applyProfile.isEnabled = index >= 0
        }

        createNew.addActionListener {
            val name = askForName(
                project,
                LayoutProfilesBundle.message("dialog.save.defaultName", listModel.size + 1),
            ) ?: return@addActionListener
            apply()
            val saved = saveNewLayoutProfile(project, name) ?: return@addActionListener
            reset()
            list.selectedIndex = saved.number - 1
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
            apply()
            val profile = service().profiles().firstOrNull { it.id == selected.id } ?: return@addActionListener
            when (service().apply(project, profile.number)) {
                ApplyResult.APPLIED -> notify(project, "notification.applied", profile.displayName)
                ApplyResult.EMPTY -> notify(project, "notification.empty", profile.number, warning = true)
                ApplyResult.MISSING_LAYOUT -> notify(project, "notification.missing", profile.number, warning = true)
            }
        }
        list.addListSelectionListener { updateButtons() }

        val buttons = JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(8), 0)).apply {
            add(createNew)
            add(rename)
            add(delete)
            add(moveUp)
            add(moveDown)
            add(applyProfile)
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
            val shortcut = if (index < SHORTCUT_SLOT_COUNT) " — Apply Slot ${index + 1}" else ""
            renderer.getListCellRendererComponent(
                list,
                "${index + 1}. ${value.displayName}$shortcut",
                index,
                selected,
                focused,
            )
        }
    }

    private data class ProfileDraft(
        val id: String,
        val displayName: String,
    )
}
