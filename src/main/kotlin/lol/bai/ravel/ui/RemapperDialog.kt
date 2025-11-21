package lol.bai.ravel.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.rootManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.*
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.util.ui.JBUI
import lol.bai.ravel.mapping.MioMappingConfig
import org.jetbrains.annotations.NonNls
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener
import com.intellij.ui.dsl.builder.panel as rootPanel

class RemapperDialog(
    val project: Project,
    val model: RemapperModel
) : DialogWrapper(project), DataProvider {

    val fileColor = FileColorManager.getInstance(project)!!

    val mappingsLabel = JLabel(B("dialog.remapper.mappings")).apply { horizontalTextPosition = JLabel.LEFT }
    val modulesLabel = JLabel().apply { horizontalTextPosition = JLabel.LEFT }

    lateinit var moduleList: ModuleList
    lateinit var mappingsModel: CollectionListModel<MioMappingConfig>

    init {
        title = B("dialog.remapper.title")
        init()
        check()
    }

    override fun getData(dataId: @NonNls String) = when (dataId) {
        K.modelData.name -> model
        K.modulesLabel.name -> modulesLabel
        K.modulesList.name -> moduleList
        K.mappingsModel.name -> mappingsModel
        K.check.name -> this::check
        else -> null
    }

    fun check() {
        val hasMappings = model.mappings.isNotEmpty()
        val hasModules = model.modules.isNotEmpty()

        mappingsLabel.icon = if (hasMappings) null else AllIcons.General.BalloonError
        modulesLabel.icon = if (hasModules) null else AllIcons.General.BalloonError

        okAction.isEnabled = hasMappings && hasModules
    }

    override fun createCenterPanel() = rootPanel {
        mappingsModel = CollectionListModel(model.mappings, true).apply {
            addListDataListener(object : ListDataListener {
                override fun intervalAdded(e: ListDataEvent) = check()
                override fun intervalRemoved(e: ListDataEvent) = check()
                override fun contentsChanged(e: ListDataEvent) = check()
            })
        }
        val mappingsList = JBList<MioMappingConfig>().apply {
            model = mappingsModel
            setEmptyText(B("dialog.remapper.empty"))
        }
        val mappings = ToolbarDecorator
            .createDecorator(mappingsList)
            .setPreferredSize(JBUI.size(300, 500))
            .addExtraAction(A<MappingActionGroup>())
            .setButtonComparator(
                B("group.lol.bai.ravel.ui.MappingActionGroup.text"),
                *CommonActionsPanel.Buttons.entries.map { it.text }.toTypedArray()
            )
            .createPanel()

        val moduleModel = CollectionListModel<ModuleEntry>()
        ModuleManager.getInstance(project).modules.sortedBy { it.name }.forEach { module ->
            if (module.rootManager.sourceRoots.isEmpty()) return@forEach
            moduleModel.add(ModuleEntry(module, false))
        }
        moduleList = ModuleList(moduleModel)
        modulesLabel.text = B("dialog.remapper.modules", model.modules.size, moduleModel.size)
        ListSpeedSearch.installOn(moduleList)
        val modules = ToolbarDecorator
            .createDecorator(moduleList)
            .setPreferredSize(JBUI.size(400, 500))
            .disableAddAction()
            .disableRemoveAction()
            .disableUpDownActions()
            .addExtraAction(A<MarkAllModuleAction>())
            .addExtraAction(A<MarkModuleAction>())
            .addExtraAction(A<UnmarkModuleAction>())
            .createPanel()

        row {
            cell(mappings).label(mappingsLabel, LabelPosition.TOP)
            cell(modules).label(modulesLabel, LabelPosition.TOP)
        }
    }

    inner class ModuleList(
        val model: CollectionListModel<ModuleEntry>
    ) : JBList<ModuleEntry>() {
        init {
            super.model = model
            cellRenderer = ModuleCellRenderer()
        }
    }

    data class ModuleEntry(
        val module: Module,
        var selected: Boolean
    )

    inner class ModuleCellRenderer : ColoredListCellRenderer<ModuleEntry>() {
        override fun customizeCellRenderer(list: JList<out ModuleEntry>, value: ModuleEntry, index: Int, selected: Boolean, hasFocus: Boolean) {
            append(value.module.name)
            icon = ModuleType.get(value.module).icon
            if (value.selected) background = fileColor.getColor("Green")
            if (selected) background = JBUI.CurrentTheme.List.background(true, hasFocus)
        }
    }

}
