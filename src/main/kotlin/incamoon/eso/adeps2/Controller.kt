package incamoon.eso.adeps2

import com.jfoenix.controls.JFXComboBox
import incamoon.eso.adeps2.Controller.AppEvent
import javafx.animation.AnimationTimer
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.value.ChangeListener
import javafx.beans.value.ObservableValue
import javafx.collections.FXCollections
import javafx.collections.ListChangeListener
import javafx.event.ActionEvent
import javafx.event.EventHandler
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.control.cell.PropertyValueFactory
import javafx.scene.input.MouseEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.Region
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.scene.text.Text
import javafx.scene.text.TextFlow
import javafx.stage.DirectoryChooser
import javafx.stage.Stage
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.javafx.JavaFx
import java.net.URL
import java.nio.file.Paths
import java.util.*
import java.util.prefs.Preferences

@ObsoleteCoroutinesApi
@DelicateCoroutinesApi
class Controller : Initializable {

    @FXML
    lateinit var main: BorderPane

    @FXML
    lateinit var libsTable: TableView<EsoLibsAnalyser.Companion.Addon>

    @FXML
    lateinit var addsTable: TableView<EsoLibsAnalyser.Companion.Addon>

    @FXML
    lateinit var libsMissing: TableView<EsoLibsAnalyser.Companion.AddonIdent>

    @FXML
    lateinit var libsDuplicated: TableView<EsoLibsAnalyser.Companion.AddonIdent>

    @FXML
    lateinit var libsUnreferenced: TableView<EsoLibsAnalyser.Companion.AddonIdent>

    @FXML
    lateinit var browse: Button

    @FXML
    lateinit var folder: TextField

    @FXML
    lateinit var analyse: Button

    @FXML
    lateinit var progress: ProgressIndicator

    @FXML
    lateinit var log: TextArea

    @FXML
    lateinit var versions: JFXComboBox<String>

    @FXML
    lateinit var libsNameColumn: TableColumn<EsoLibsAnalyser.Companion.Addon, String>

    @FXML
    lateinit var libsOODColumn: TableColumn<EsoLibsAnalyser.Companion.Addon, Int>

    @FXML
    lateinit var libsSTColumn: TableColumn<EsoLibsAnalyser.Companion.Addon, String>

    @FXML
    lateinit var libsVersionColumm: TableColumn<EsoLibsAnalyser.Companion.Addon, String>

    @FXML
    lateinit var libsDependenciesColumm: TableColumn<EsoLibsAnalyser.Companion.Addon, List<EsoLibsAnalyser.Companion.AddonIdent>>

    @FXML
    lateinit var libsOptionalDependenciesColumm: TableColumn<EsoLibsAnalyser.Companion.Addon, List<EsoLibsAnalyser.Companion.AddonIdent>>

    @FXML
    lateinit var addsNameColumn: TableColumn<EsoLibsAnalyser.Companion.Addon, String>

    @FXML
    lateinit var addsOODColumn: TableColumn<EsoLibsAnalyser.Companion.Addon, Int>

    @FXML
    lateinit var addsVersionColumm: TableColumn<EsoLibsAnalyser.Companion.Addon, String>

    @FXML
    lateinit var addsDependenciesColumm: TableColumn<EsoLibsAnalyser.Companion.Addon, List<EsoLibsAnalyser.Companion.AddonIdent>>

    @FXML
    lateinit var addsOptionalDependenciesColumm: TableColumn<EsoLibsAnalyser.Companion.Addon, List<EsoLibsAnalyser.Companion.AddonIdent>>

    @FXML
    lateinit var missNameColumn: TableColumn<EsoLibsAnalyser.Companion.AddonIdent, String>

    @FXML
    lateinit var dupsNameColumn: TableColumn<EsoLibsAnalyser.Companion.AddonIdent, String>

    @FXML
    lateinit var unreferencedNameColumn: TableColumn<EsoLibsAnalyser.Companion.AddonIdent, String>

    @FXML
    lateinit var maximizeLabel: Label

    private val stage: Stage by lazy { main.scene.window as Stage }

    private val appEvents: Channel<AppEvent> by lazy { Channel(10_000) }
    private val logChannel: Channel<String> by lazy { Channel(10_000) }

    private var yOffset: Double = 0.0
    private var xOffset: Double = 0.0

    private val preferences: Preferences = Preferences.userNodeForPackage(Main::class.java)
    private var apiVersions = ApiVersions()

    private var analyser: EsoLibsAnalyser? = null
    private var selectedLib: EsoLibsAnalyser.Companion.Addon? = null

    private fun Node.onClick(action: suspend (MouseEvent) -> Unit) {
        // launch one actor to handle all events on this node
        val eventActor = GlobalScope.actor<MouseEvent>(Dispatchers.JavaFx) {
            for (event in channel) action(event) // pass event to action
        }
        // install a listener to offer events to this actor
        onMouseClicked = EventHandler { event ->
            eventActor.trySend(event)
        }
    }

    private fun ComboBoxBase<*>.onAction(action: suspend (ActionEvent) -> Unit) {
        // launch one actor to handle all events on this node
        val eventActor = GlobalScope.actor<ActionEvent>(Dispatchers.JavaFx) {
            for (event in channel) action(event) // pass event to action
        }
        // install a listener to offer events to this actor
        onAction = EventHandler { event ->
            eventActor.trySend(event)
        }
    }

    override fun initialize(location: URL?, resources: ResourceBundle?) {

        val folderLocation = preferences.get("addonFolderLocation", null)
        if (folderLocation != null) {
            folder.text = folderLocation
            analyse.isDisable = false
            analyse.requestFocus()
        }

        object : AnimationTimer() {
            override fun handle(now: Long) {
                appEvents.tryReceive().getOrNull()?.handle()
                logChannel.tryReceive().getOrNull()?.let { log.appendText("${it}\n") }
            }
        }.start()

        listOf(libsTable, addsTable, libsMissing, libsDuplicated).forEach { table ->
            lateinit var listener: ChangeListener<Boolean>
            val appEvent = AppEvent {
                listener.run {
                    table.needsLayoutProperty().removeListener(this)
                }
            }
            listener = ChangeListener { _, _, needsLayout ->
                run {
                    if (!needsLayout) {
                        table.lookupAll(".column-header")
                            .map { it as Region }
                            .forEach { updateFont(it) }
                        appEvents.trySend(appEvent)
                    }
                }
            }
            table.needsLayoutProperty().addListener(listener)
        }

        browse.onClick {
            onBrowseClient()
        }

        analyse.onClick {
            onAnalyseClick()
        }

        versions.onAction {
            onVersion()
        }

        libsTable.widthProperty()
            .addListener { _, _, newValue ->
                libsOptionalDependenciesColumm.setPrefWidth(
                    newValue.toDouble() - 550.0
                )
            }

        addsTable.widthProperty()
            .addListener { _, _, newValue ->
                val delta = newValue.toDouble() - 850.0
                if (delta > 400) {
                    addsDependenciesColumm.prefWidth = delta
                }
            }

        libsNameColumn.cellValueFactory = PropertyValueFactory("title")
        libsNameColumn.setCellFactory { column -> decorateLibName(column) }
        libsOODColumn.setCellFactory { decorateOOD() }
        libsOODColumn.cellValueFactory = PropertyValueFactory("maxApiVersion")
        libsVersionColumm.cellValueFactory = PropertyValueFactory("version")
        libsDependenciesColumm.setCellValueFactory { param -> ReadOnlyObjectWrapper(param.value.dependsOn) }
        libsDependenciesColumm.setCellFactory { DependenciesTableCell() }
        libsOptionalDependenciesColumm.setCellValueFactory { param -> ReadOnlyObjectWrapper(param.value.optionalDependsOn) }
        libsOptionalDependenciesColumm.setCellFactory { DependenciesTableCell() }

        addsNameColumn.cellValueFactory = PropertyValueFactory("title")
        addsNameColumn.setCellFactory { column -> decorateName() }
        addsOODColumn.setCellFactory { decorateOOD() }
        addsOODColumn.cellValueFactory = PropertyValueFactory("maxApiVersion")
        addsVersionColumm.cellValueFactory = PropertyValueFactory("version")
        addsDependenciesColumm.setCellValueFactory { param -> ReadOnlyObjectWrapper(param.value.dependsOn) }
        addsDependenciesColumm.setCellFactory { DependenciesTableCell() }
        addsOptionalDependenciesColumm.setCellValueFactory { param -> ReadOnlyObjectWrapper(param.value.optionalDependsOn) }
        addsOptionalDependenciesColumm.setCellFactory { DependenciesTableCell() }

        missNameColumn.cellValueFactory = PropertyValueFactory("name")
        dupsNameColumn.cellValueFactory = PropertyValueFactory("name")
        unreferencedNameColumn.cellValueFactory = PropertyValueFactory("name")

        apiVersions = ApiVersions()

        libsTable.selectionModel.selectedItemProperty()
            .addListener { _, _, newValue ->
                selectedLib = newValue
                addsTable.refresh()
            }
    }

    private fun decorateOOD(): TableCell<EsoLibsAnalyser.Companion.Addon, Int?> {
        return object : TableCell<EsoLibsAnalyser.Companion.Addon, Int?>() {
            override fun updateItem(item: Int?, empty: Boolean) {
                if (!empty && item != null) {
                    if (versions.value != null && versions.value.isNotEmpty()) {
                        val version = versions.value.substring(0, 6).toInt()
                        val delta = item - version
                        if (delta >= 0) {
                            text = "\uF582"
                            style =
                                "-fx-font-family: 'Font Awesome 5 Free Regular'; -fx-font-size: 24; -fx-background-color: rgba(0,128,0,0.15); -fx-alignment: center;"
                        } else {
                            if (delta <= -1) {
                                text = "\uF556"
                            } else {
                                text = "\uF11A"
                            }
                            style =
                                "-fx-font-family: 'Font Awesome 5 Free Regular'; -fx-font-size: 24; -fx-background-color: rgba(128,0,0,0.15); -fx-alignment: center;"
                        }
                        tooltip = Tooltip(apiVersions.getVersionFeature(item))
                    } else {
                        text = ""
                        style = "-fx-background-color: transparent; -fx-alignment: center-right; -fx-padding: 0 10 0 0;"
                        tooltip = null
                    }
                }
            }
        }
    }

    private fun decorateLibName(column: TableColumn<EsoLibsAnalyser.Companion.Addon, String>): TableCell<EsoLibsAnalyser.Companion.Addon, String?> {
        return object : TableCell<EsoLibsAnalyser.Companion.Addon, String?>() {
            override fun updateItem(item: String?, empty: Boolean) {
                val row = tableRow ?: return
                val addon = row.item ?: return
                val title = Text("${addon.title}\n")
                val paths: String = addon.paths.joinToString(", ") { p -> "[\\$p]" }
                val pathsForTT: String = addon.paths.joinToString("\n")
                val folder = Text(paths)
                folder.wrappingWidth = Double.MAX_VALUE
                if (!empty) {
                    decorate(title, folder, row.isSelected)
                }
                val flow = TextFlow(title, folder)
                flow.lineSpacing = 1.1
                graphic = flow
                tooltip = Tooltip(pathsForTT)
                row.selectedProperty()
                    .addListener { observable: ObservableValue<out Boolean>?, oldValue: Boolean?, newValue: Boolean ->
                        decorate(
                            title,
                            folder,
                            newValue
                        )
                    }
            }

            private fun decorate(title: Text, folder: Text, selected: Boolean) {
                analyser?.let { analyser ->
                    val row: TableRow<EsoLibsAnalyser.Companion.Addon> = tableRow
                    val addonOrNull: EsoLibsAnalyser.Companion.Addon? = row.item
                    addonOrNull?.let { addon ->
                        if (selected) {
                            if (analyser.isLibraryUnreferenced(addon) && analyser.isDuplicate(addon)) {
                                style = "-fx-alignment: top-left; -fx-background-color: rgba(0,128,0,0.15);"
                                title.style = "-fx-fill: #643200;"
                            } else if (analyser.isLibraryUnreferenced(addon)) {
                                style = "-fx-alignment: top-left; -fx-background-color: rgba(0,128,0,0.15);"
                                title.style = "-fx-fill: black;"
                            } else if (analyser.isDuplicate(addon)) {
                                style = "-fx-alignment: top-left; -fx-background-color: transparent;"
                                title.style = "-fx-fill: #643200;"
                            } else {
                                style = "-fx-alignment: top-left; -fx-background-color: transparent;"
                                title.style = "-fx-fill: black;"
                            }
                            if (addon.isEmbedded) {
                                folder.style = "-fx-font-size: 66%; -fx-fill: #643200"
                            } else {
                                folder.style = "-fx-font-size: 66%; -fx-fill: #2A2E37"
                            }
                        } else {
                            if (analyser.isLibraryUnreferenced(addon) && analyser.isDuplicate(addon)) {
                                style = "-fx-alignment: top-left; -fx-background-color: rgba(0,128,0,0.15);"
                                title.style = "-fx-fill: #fa7d00;"
                            } else if (analyser.isLibraryUnreferenced(addon)) {
                                style = "-fx-alignment: top-left; -fx-background-color: rgba(0,128,0,0.15);"
                                title.style = "-fx-fill: white;"
                            } else if (analyser.isDuplicate(addon)) {
                                style = "-fx-alignment: top-left; -fx-background-color: transparent;"
                                title.style = "-fx-fill: #fa7d00;"
                            } else {
                                style = "-fx-alignment: top-left; -fx-background-color: transparent;"
                                title.style = "-fx-fill: white;"
                            }
                            if (addon.isEmbedded) {
                                folder.style = "-fx-font-size: 66%; -fx-fill: #bd8b69"
                            } else {
                                folder.style = "-fx-font-size: 66%; -fx-fill: #B2B2B2"
                            }
                        }
                        val menuItems = buildContextMenu(addon)
                        contextMenu = if (menuItems.size > 1) {
                            ContextMenu(*menuItems.toTypedArray())
                        } else {
                            null
                        }
                    }
                }
            }
        }
    }

    private fun buildContextMenu(addon: EsoLibsAnalyser.Companion.Addon): List<MenuItem> {
        val menuItems: MutableList<MenuItem> = LinkedList()
        analyser?.let { analyser ->
            if (analyser.isDuplicate(addon)) {
                val titleItem = MenuItem("Actions")
                titleItem.isDisable = true
                titleItem.styleClass.add("context-menu-title")
                menuItems.add(titleItem)
                if (addon.paths.size > 1 && !addon.isEmbedded) {
                    val menuItem = MenuItem("compress")
                    menuItem.onAction = EventHandler {
                        val alert =
                            Alert(Alert.AlertType.CONFIRMATION)
                        alert.title = "WARNING! This cannot be undone! Are you sure?"
                        alert.headerText =
                            "You are about to remove all of the duplicate library folders nested in other addons for ${addon.title}"
                        alert.contentText = "Please make sure you have a backup of all your addons first!"
                        alert.showAndWait()
                            .ifPresent { result: ButtonType ->
                                if (result.buttonData.isDefaultButton) {
                                    GlobalScope.launch(Dispatchers.IO) { analyser.compress(addon, logChannel) }
                                        .invokeOnCompletion {
                                            GlobalScope.launch(Dispatchers.JavaFx) {
                                                onAnalyseClick()
                                            }
                                        }
                                }
                            }
                    }
                    menuItems.add(menuItem)
                } else if (addon.isEmbedded && analyser.isDeletable(addon)) {
                    val menuItem = MenuItem("delete")
                    menuItem.onAction = EventHandler {
                        val paths: String = addon.paths.joinToString(", ") { p -> "[\\$p]" }
                        val alert =
                            Alert(Alert.AlertType.CONFIRMATION)
                        alert.title = "WARNING!  This cannot be undone! Are you sure?"
                        alert.headerText = "You are about to delete nested library folder(s) for ${addon.title}: $paths"
                        alert.contentText = "Please make sure you have a backup of all your addons first!"
                        alert.showAndWait()
                            .ifPresent { result: ButtonType ->
                                if (result.buttonData.isDefaultButton) {
                                    GlobalScope.launch(Dispatchers.IO) {
                                        analyser.delete(addon, logChannel)
                                    }.invokeOnCompletion {
                                        GlobalScope.launch(Dispatchers.JavaFx) {
                                            onAnalyseClick()
                                        }
                                    }
                                }
                            }
                    }
                    menuItems.add(menuItem)
                } else if (addon.isEmbedded) {
                    val menuItem = MenuItem("copy down and compress")
                    menuItem.onAction = EventHandler {
                        val paths: String = addon.paths.joinToString(", ") { p -> "[\\$p]" }
                        val alert =
                            Alert(Alert.AlertType.CONFIRMATION)
                        alert.title = "WARNING!  This cannot be undone! Are you sure?"
                        alert.headerText =
                            "You are about to copy a nested library folder down to the root leve and remove the duplicate nested folder for ${addon.title}: $paths"
                        alert.contentText = "Please make sure you have a backup of all your addons first!"
                        alert.showAndWait()
                            .ifPresent { result: ButtonType ->
                                if (result.buttonData.isDefaultButton) {
                                    GlobalScope.launch(Dispatchers.IO) {
                                        analyser.copyDownAndCompress(addon, logChannel)
                                    }.invokeOnCompletion {
                                        GlobalScope.launch(Dispatchers.JavaFx) {
                                            onAnalyseClick()
                                        }
                                    }
                                }
                            }
                    }
                    menuItems.add(menuItem)
                }
            }
        }
        return menuItems
    }

    private fun decorateName(): TableCell<EsoLibsAnalyser.Companion.Addon, String?> {
        return object : TableCell<EsoLibsAnalyser.Companion.Addon, String?>() {
            override fun updateItem(item: String?, empty: Boolean) {
                if (tableRow == null) return
                val addon: EsoLibsAnalyser.Companion.Addon = tableRow.item ?: return
                val title = Text("${addon.title}\n")
                val paths: String = addon.paths.joinToString(", ") { p -> "[/$p]" }
                val pathsForTT: String = addon.paths.joinToString("\n")
                val folder = Text(paths)
                if (!empty) {
                    decorate(title, folder, tableRow.isSelected)
                }
                val flow = TextFlow(title, folder)
                flow.lineSpacing = 1.1
                style = "-fx-alignment: top-left"
                graphic = flow
                tooltip = Tooltip(pathsForTT)
                tableRow.selectedProperty()
                    .addListener { _, _, newValue ->
                        decorate(title, folder, newValue)
                    }
            }

            private fun decorate(title: Text, folder: Text, newValue: Boolean) {
                analyser?.let { analyser ->
                    val addonOrNull: EsoLibsAnalyser.Companion.Addon? = tableRow.item
                    addonOrNull?.let { addon ->
                        if (newValue) {
                            if (addon.references(selectedLib)) {
                                title.style = "-fx-fill: rgb(44,43,0);"
                            } else if (analyser.isDuplicate(addon)) {
                                title.style = "-fx-fill: #643200; -fx-background-color: transparent;"
                            } else {
                                title.style = "-fx-fill: black;"
                            }
                            folder.style = "-fx-font-size: 66%; -fx-fill: #2A2E37"
                        } else {
                            if (addon.references(selectedLib)) {
                                title.style = "-fx-fill: rgb(236,232,0);"
                            } else if (analyser.isDuplicate(addon)) {
                                title.style = "-fx-fill: #fa7d00; -fx-background-color: transparent;"
                            } else {
                                title.style = "-fx-fill: rgb(255,255,255);"
                            }
                            folder.style = "-fx-font-size: 66%; -fx-fill: #B2B2B2"
                        }
                        val menuItems = buildContextMenu(addon)
                        contextMenu = if (menuItems.size > 1) {
                            ContextMenu(*menuItems.toTypedArray())
                        } else {
                            null
                        }
                    }
                }
            }
        }
    }

    private fun disableActions(value: Boolean) {
        main.lookupAll(".button").forEach { it.isDisable = value }
    }

    private suspend fun onAnalyseClick() = coroutineScope {
        val analyser = EsoLibsAnalyser(folder.text)
        progress.isVisible = true
        disableActions(true)
        launch(Dispatchers.IO) { analyser.load(logChannel) }.invokeOnCompletion {
            launch(Dispatchers.JavaFx) {
                this@Controller.analyser = analyser
                libsTable.items = FXCollections.observableList(analyser.getLibs())
                addsTable.items = FXCollections.observableList(analyser.getAddons())
                libsMissing.items = FXCollections.observableList(analyser.getMissing())
                libsDuplicated.items = FXCollections.observableList(analyser.getDuplicates())
                libsUnreferenced.items = FXCollections.observableList(analyser.getUnreferenced())
                versions.items = FXCollections.observableList(analyser.getVersions())
                versions.isDisable = false
                versions.items.addListener(ListChangeListener { c: ListChangeListener.Change<out String> ->
                    while (c.next()) {
                        for (i in c.from until c.to) {
                            val s = c.list[i]
                            if (s.endsWith("[*]")) {
                                versions.value = s
                            }
                        }
                    }
                } as ListChangeListener<in String>)
                apiVersions.bind(versions.items)
                progress.isVisible = false
                disableActions(false)
                progress.isVisible = false
            }
        }
    }

    private suspend fun onBrowseClient() = coroutineScope {
        launch {
            val directoryChooser = DirectoryChooser()
            directoryChooser.title = "Choose ESO Addon Folder"
            if (folder.text.isNotEmpty()) {
                directoryChooser.initialDirectory = Paths.get(folder.text).toFile()
            }
            val selectedDirectory = directoryChooser.showDialog(stage)
            if (selectedDirectory != null) {
                val path = selectedDirectory.path
                folder.text = path
                preferences.put("addonFolderLocation", path)
                analyse.isDisable = false
                analyse.requestFocus()
            }
        }
    }

    fun interface AppEvent {
        fun handle()
    }

    private fun updateFont(column: Region) {
        column.lookupAll(".label").map { it as Label }
            .forEach { label: Label -> label.font = Font("Open Sans Condensed Regular", 16.0) }
    }

    fun onClose() {
        stage.close()
    }

    fun dragWindowStart(event: MouseEvent) {
        xOffset = stage.x - event.screenX;
        yOffset = stage.y - event.screenY;
    }

    fun dragWindowDrag(event: MouseEvent) {
        if (stage.isMaximized) {
            stage.isMaximized = false
            maximizeLabel.text = "\uF0C8"
        }
        stage.x = event.screenX + xOffset;
        stage.y = event.screenY + yOffset;
    }

    fun onMinimize() {
        stage.isIconified = true
    }

    fun onMaximize() {
        if (stage.isMaximized) {
            stage.isMaximized = false
            maximizeLabel.text = "\uF0C8"
        } else {
            stage.isMaximized = true
            maximizeLabel.text = "\uF24D"
        }
    }

    private suspend fun onVersion() = coroutineScope {
        launch {
            libsTable.refresh()
            addsTable.refresh()
        }
    }

    fun onWindowTitleDoubleClick(mouseEvent: MouseEvent) {
        if (mouseEvent.clickCount == 2) {
            onMaximize()
        }
    }

    inner class DependenciesTableCell :
        TableCell<EsoLibsAnalyser.Companion.Addon, List<EsoLibsAnalyser.Companion.AddonIdent>?>() {
        override fun updateItem(dependsOn: List<EsoLibsAnalyser.Companion.AddonIdent>?, empty: Boolean) {
            if (tableRow == null) return
            if (!empty) {
                val deps = dependsOn?.flatMap { dep ->
                    val text = Text(dep.name)
                    text.userData = dep
                    text.font = tableRow.font
                    decorate(tableRow.isSelected, text, dep)
                    val space = Text(", ")
                    space.font = tableRow.font
                    if (tableRow.isSelected) {
                        space.fill = Color.BLACK
                    } else {
                        space.fill = Color.WHITE
                    }
                    listOf(text, space)
                }
                deps?.let {
                    tableRow.selectedProperty()
                        .addListener { _, _, newValue ->
                            it.forEach { dep: Text ->
                                if (dep.userData == null) return@forEach
                                val lib: EsoLibsAnalyser.Companion.AddonIdent =
                                    dep.userData as EsoLibsAnalyser.Companion.AddonIdent
                                decorate(newValue, dep, lib)
                            }
                        }
                    val flow = TextFlow(*deps.take(if (deps.isNotEmpty()) deps.size - 1 else 0).toTypedArray())
                    flow.lineSpacing = 1.1
                    graphic = flow
                }
            }
        }

        private fun decorate(selected: Boolean, text: Text, lib: EsoLibsAnalyser.Companion.AddonIdent) {
            analyser?.let {
                if (selected) {
                    if (it.isAddonMissing(lib)) {
                        text.style = "-fx-fill:#690300;"
                    } else if (selectedLib != null && lib.name == selectedLib?.name) {
                        text.style = "-fx-fill:rgb(44,43,0);"
                    } else {
                        text.style = "-fx-fill: black"
                    }
                } else {
                    if (it.isAddonMissing(lib)) {
                        text.style = "-fx-fill:#ed5853;"
                    } else if (selectedLib != null && lib.name == selectedLib?.name) {
                        text.style = "-fx-fill:rgb(236,232,0);"
                    } else {
                        text.style = "-fx-fill: white"
                    }
                }
            }
        }
    }
}