package io.github.khopland

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.text.DateFormatUtil
import org.jdom.JDOMException
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.IOException
import java.nio.file.Path
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants

internal const val LAYOUT_PROFILE_SETTINGS_ID = "io.github.khopland.ideLayoutProfiles.settings"
private const val SHORTCUT_SLOT_COUNT = 10

class LayoutProfilesConfigurable() : SearchableConfigurable {
    private var projectOverride: Project? = null
    private var model: DefaultListModel<ProfileDraft>? = null
    private var profileList: JBList<ProfileDraft>? = null
    private var autoSwitchBestMatch: JBCheckBox? = null

    internal constructor(project: Project) : this() {
        projectOverride = project
    }

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
        val duplicate = JButton("Duplicate")
        val rename = JButton("Rename")
        val delete = JButton("Delete")
        val moveUp = JButton("Move Up")
        val moveDown = JButton("Move Down")
        val applyProfile = JButton("Apply Profile")
        val updateProfile = JButton("Update from Current")
        val importProfiles = JButton("Import…")
        val exportSelected = JButton("Export Selected…")
        val exportAll = JButton("Export All…")
        val autoSwitch = JBCheckBox(
            "Automatically apply the best matching profile after the display layout changes",
        )
        val details = JLabel().apply {
            verticalAlignment = SwingConstants.TOP
            border = JBUI.Borders.emptyLeft(12)
            preferredSize = Dimension(JBUI.scale(280), JBUI.scale(220))
        }
        autoSwitchBestMatch = autoSwitch

        fun updateButtons() {
            val index = list.selectedIndex
            duplicate.isEnabled = index >= 0
            rename.isEnabled = index >= 0
            delete.isEnabled = index >= 0
            moveUp.isEnabled = index > 0
            moveDown.isEnabled = index in 0 until listModel.size - 1
            applyProfile.isEnabled = index >= 0
            updateProfile.isEnabled = index >= 0
            exportSelected.isEnabled = index >= 0
            exportAll.isEnabled = !listModel.isEmpty
            details.text = list.selectedValue?.let(::profileDetails)
                ?: "<html><b>Profile details</b><br>Select a profile to inspect it.</html>"
        }

        createNew.addActionListener {
            val project = requireProject("Create a layout profile") ?: return@addActionListener
            val name = askForName(
                project,
                LayoutProfilesBundle.message("dialog.save.defaultName", listModel.size + 1),
            ) ?: return@addActionListener
            val saved = saveNewLayoutProfile(project, name) ?: return@addActionListener
            listModel.addElement(ProfileDraft(saved.id, saved.displayName))
            list.selectedIndex = listModel.size - 1
            updateButtons()
        }
        duplicate.addActionListener {
            val project = contextProject()
            val selected = list.selectedValue ?: return@addActionListener
            try {
                val copied = service().duplicate(selected.id) ?: return@addActionListener
                listModel.addElement(ProfileDraft(copied.id, copied.displayName))
                list.selectedIndex = listModel.size - 1
                syncProfileActions()
                notify(project, "notification.duplicated", copied.displayName)
                updateButtons()
            } catch (error: Exception) {
                if (error is ProcessCanceledException || error is ControlFlowException) throw error
                showFileError(
                    error,
                    "Could not duplicate the layout profile.",
                    "Duplicate Layout Profile",
                    project,
                )
            }
        }
        rename.addActionListener {
            val project = contextProject()
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
            val project = contextProject()
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
            val project = requireProject("Apply a layout profile") ?: return@addActionListener
            val selected = list.selectedValue ?: return@addActionListener
            val profile = service().profile(selected.id) ?: return@addActionListener
            applyLayoutProfile(project, profile.number)
            list.repaint()
        }
        updateProfile.addActionListener {
            val project = requireProject("Update a layout profile") ?: return@addActionListener
            val selected = list.selectedValue ?: return@addActionListener
            updateLayoutProfile(project, selected.id) ?: return@addActionListener
            list.repaint()
        }
        importProfiles.addActionListener {
            val project = contextProject()
            val file = FileChooser.chooseFile(
                FileChooserDescriptorFactory.singleFile()
                    .withExtensionFilter("xml")
                    .withTitle("Import Layout Profiles"),
                project,
                null,
            ) ?: return@addActionListener
            loadProfiles(file.toNioPath(), project, ::updateButtons)
        }
        exportSelected.addActionListener {
            val selected = list.selectedValue ?: return@addActionListener
            exportProfiles(setOf(selected.id), "ide-layout-profile.xml")
        }
        exportAll.addActionListener { exportProfiles(null, "ide-layout-profiles.xml") }
        list.addListSelectionListener { updateButtons() }

        val buttons = JPanel(WrappingFlowLayout(FlowLayout.LEADING, JBUI.scale(8), 0)).apply {
            add(createNew)
            add(duplicate)
            add(rename)
            add(delete)
            add(moveUp)
            add(moveDown)
            add(applyProfile)
            add(updateProfile)
            add(importProfiles)
            add(exportSelected)
            add(exportAll)
        }
        val introduction = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(
                JLabel(
                    """
                    <html>Create, update, apply, and import take effect immediately.<br>
                    Rename, reorder, delete, and automation options take effect when you click Apply.<br>
                    The first ten profiles are assigned to the Apply Slot 1–10 keybindings.</html>
                    """.trimIndent(),
                ),
            )
            add(autoSwitch)
        }
        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(8)
            add(introduction, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
            add(details, BorderLayout.EAST)
            add(buttons, BorderLayout.SOUTH)
            reset()
            updateButtons()
        }
    }

    override fun isModified(): Boolean =
        drafts() != savedDrafts() ||
            autoSwitchBestMatch?.isSelected?.let { it != service().autoSwitchBestMatch() } == true

    override fun apply() {
        service().updateProfiles(drafts().map { LayoutProfileUpdate(it.id, it.displayName) })
        autoSwitchBestMatch?.let { service().setAutoSwitchBestMatch(it.isSelected) }
        ApplicationManager.getApplication()
            .getService(DisplayTopologyAutoSwitchService::class.java)
            .refresh()
        syncProfileActions()
    }

    override fun reset() {
        val listModel = model ?: return
        listModel.removeAllElements()
        savedDrafts().forEach(listModel::addElement)
        if (!listModel.isEmpty) profileList?.selectedIndex = 0
        autoSwitchBestMatch?.isSelected = service().autoSwitchBestMatch()
    }

    override fun disposeUIResources() {
        model = null
        profileList = null
        autoSwitchBestMatch = null
    }

    private fun chooseImportMode(profileCount: Int, project: Project?): ImportMode? {
        val choice = Messages.showDialog(
            project,
            """
            Choose how to import $profileCount layout profiles.

            Add New keeps existing profiles and skips matching IDs.
            Update Existing changes matching IDs only.
            Import as Copies adds every profile with a new ID.
            Replace All removes the current profiles.
            """.trimIndent(),
            "Import Layout Profiles",
            arrayOf("Add New", "Update Existing", "Import as Copies", "Replace All", "Cancel"),
            0,
            Messages.getQuestionIcon(),
        )
        return when (choice) {
            0 -> ImportMode.ADD
            1 -> ImportMode.UPDATE_EXISTING
            2 -> ImportMode.COPY
            3 -> ImportMode.REPLACE_ALL
            else -> null
        }
    }

    private fun exportProfiles(profileIds: Set<String>?, defaultFileName: String) {
        val project = contextProject()
        val file = FileChooserFactory.getInstance()
            .createSaveFileDialog(
                FileSaverDescriptor(
                    "Export Layout Profiles",
                    "Save layout profiles to a portable file.",
                    "xml",
                ),
                project,
            )
            .save(defaultFileName)
            ?: return
        val exported = try {
            profileIds?.let(service()::exportProfiles) ?: service().exportProfiles()
        } catch (error: IllegalArgumentException) {
            showFileError(
                error,
                "Could not export layout profiles.",
                "Export Layout Profiles",
                project,
            )
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val failure = try {
                JDOMUtil.write(exported, file.file.toPath())
                null
            } catch (error: IOException) {
                error
            }
            ApplicationManager.getApplication().invokeLater(
                {
                    if (failure == null) {
                        notify(project, "notification.exported", file.file.name)
                    } else {
                        showFileError(
                            failure,
                            "Could not export layout profiles.",
                            "Export Layout Profiles",
                            project,
                        )
                    }
                },
                ModalityState.any(),
            )
        }
    }

    private fun loadProfiles(
        file: Path,
        project: Project?,
        updateButtons: () -> Unit,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val loaded = try {
                Result.success(LayoutProfileInterchange.read(JDOMUtil.load(file)))
            } catch (error: IOException) {
                Result.failure(error)
            } catch (error: JDOMException) {
                Result.failure(error)
            } catch (error: IllegalArgumentException) {
                Result.failure(error)
            }
            ApplicationManager.getApplication().invokeLater(
                {
                    if (model == null || project?.isDisposed == true) return@invokeLater
                    loaded.fold(
                        onSuccess = { imported -> importProfiles(imported, project, updateButtons) },
                        onFailure = { error ->
                            showFileError(
                                error,
                                "Could not import layout profiles.",
                                "Import Layout Profiles",
                                project,
                            )
                        },
                    )
                },
                ModalityState.any(),
            )
        }
    }

    private fun importProfiles(
        imported: ImportedProfiles,
        project: Project?,
        updateButtons: () -> Unit,
    ) {
        val mode = chooseImportMode(imported.profiles.size, project) ?: return
        val savedBefore = savedDrafts().associateBy(ProfileDraft::id)
        try {
            val result = service().importProfiles(imported, mode)
            syncProfileActions()
            if (mode == ImportMode.REPLACE_ALL) reset() else reconcileDrafts(savedBefore)
            updateButtons()
            if (result.skipped == 0) {
                notify(project, "notification.imported", result.imported)
            } else {
                notify(project, "notification.importedSkipped", result.imported, result.skipped)
            }
        } catch (error: Exception) {
            if (error is ProcessCanceledException || error is ControlFlowException) throw error
            showFileError(
                error,
                "Could not import layout profiles.",
                "Import Layout Profiles",
                project,
            )
        }
    }

    private fun showFileError(
        error: Throwable,
        fallbackMessage: String,
        title: String,
        project: Project?,
    ) {
        if (project?.isDisposed == true) return
        Messages.showErrorDialog(
            project,
            error.message?.takeIf(String::isNotBlank) ?: fallbackMessage,
            title,
        )
    }

    private fun reconcileDrafts(savedBefore: Map<String, ProfileDraft>) {
        val listModel = model ?: return
        val savedAfter = service().profiles().associateBy(LayoutProfile::id)
        repeat(listModel.size) { index ->
            val draft = listModel.getElementAt(index)
            val updated = savedAfter[draft.id] ?: return@repeat
            if (draft.displayName == savedBefore[draft.id]?.displayName) {
                listModel.set(index, draft.copy(displayName = updated.displayName))
            }
        }
        savedAfter.values
            .filter { it.id !in savedBefore }
            .forEach { listModel.addElement(ProfileDraft(it.id, it.displayName)) }
    }

    private fun drafts(): List<ProfileDraft> {
        val listModel = model ?: return emptyList()
        return (0 until listModel.size).map(listModel::getElementAt)
    }

    private fun savedDrafts(): List<ProfileDraft> =
        service().profiles().map { ProfileDraft(it.id, it.displayName) }

    private fun profileDetails(draft: ProfileDraft): String {
        val profile = service().profile(draft.id) ?: return "<html>Profile not found.</html>"
        val health = service().profileHealth(draft.id)
        val topology = DisplayTopology.parse(profile.displayTopology)
        val status = when {
            health?.nativeLayoutAvailable == false -> "Missing native layout"
            else -> "Ready"
        }
        val captured = profile.capturedAtEpochMillis
            .takeIf { it > 0 }
            ?.let(DateFormatUtil::formatDateTime)
            ?: "Unknown (legacy profile)"
        val topologyText = if (topology.isEmpty) {
            "Not captured"
        } else {
            "${topology.monitors.size} display${if (topology.monitors.size == 1) "" else "s"}"
        }
        val name = StringUtil.escapeXmlEntities(profile.displayName)
        return """
            <html>
            <b>$name</b><br>
            Slot: ${profile.number}<br>
            Status: $status<br>
            Captured: $captured<br>
            Topology: $topologyText<br><br>
            <b>Stored appearance</b><br>
            Main toolbar: ${onOff(profile.showNewMainToolbar)}<br>
            Navigation bar: ${onOff(profile.showNavigationBar)} (${profile.navigationBarLocation})<br>
            Tool-window bars: ${onOff(!profile.hideToolStripes)}<br>
            Status bar: ${onOff(profile.showStatusBar)}<br>
            Widescreen layout: ${onOff(profile.wideScreenSupport)}
            </html>
        """.trimIndent()
    }

    private fun onOff(enabled: Boolean): String = if (enabled) "On" else "Off"

    private fun service(): LayoutProfileService =
        ApplicationManager.getApplication().getService(LayoutProfileService::class.java)

    private fun contextProject(): Project? {
        projectOverride?.takeUnless(Project::isDisposed)?.let { return it }
        val component = profileList ?: return null
        return CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(component))
            ?.takeUnless(Project::isDisposed)
    }

    private fun requireProject(operation: String): Project? =
        contextProject() ?: run {
            Messages.showInfoMessage(
                "Open a project before using this command.",
                operation,
            )
            null
        }

    private fun profileRenderer(): ListCellRenderer<in ProfileDraft> {
        val renderer = DefaultListCellRenderer()
        return ListCellRenderer { list: JList<out ProfileDraft>, value, index, selected, focused ->
            val shortcut = profileShortcutText(value.id, index + 1)
                .takeIf(String::isNotEmpty)
                ?.let { " — $it" }
                .orEmpty()
            val active = if (service().activeSlot()?.id == value.id) "✓ " else ""
            val health = service().profileHealth(value.id)
            val warning = if (health?.nativeLayoutAvailable == false) "⚠ " else ""
            renderer.getListCellRendererComponent(
                list,
                "$warning$active${index + 1}. ${value.displayName}$shortcut",
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
