package incamoon.eso.adeps2

import javafx.application.Application
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.scene.text.Font
import javafx.stage.Stage
import javafx.stage.StageStyle
import java.lang.Thread.UncaughtExceptionHandler
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger


class Main : Application() {
    override fun start(stage: Stage) {

        stage.initStyle(StageStyle.UNDECORATED)

        Thread.currentThread().uncaughtExceptionHandler =
            UncaughtExceptionHandler { _: Thread?, throwable: Throwable ->
                LOG.log(Level.SEVERE, throwable) { throwable.message }
            }

        Font.loadFont(Main::class.java.getResourceAsStream("/incamoon/eso/adeps2/webfonts/fa-regular-400.ttf"), 24.0)
        Font.loadFont(Main::class.java.getResourceAsStream("/incamoon/eso/adeps2/webfonts/fa-solid-900.ttf"), 24.0)
        Font.loadFont(Main::class.java.getResourceAsStream("/incamoon/eso/adeps2/webfonts/OpenSans-Condensed-Regular.ttf"), 24.0)
        Font.loadFont(Main::class.java.getResourceAsStream("/incamoon/eso/adeps2/webfonts/OpenSans-Condensed-Bold.ttf"), 24.0)

        val bundle: ResourceBundle = ResourceBundle.getBundle("incamoon.eso.adeps2.main")
        val loader = FXMLLoader(sceneUrl, bundle)

        val root = loader.load<Parent>()

        val scene = Scene(root)

        val props = Properties()
        props.load(javaClass.getResourceAsStream("/version.properties"))
        stage.title = "The Elder Scrolls Online Addon Dependency Analyser by fr33r4ng3r version ${props.getProperty("version")})"
        stage.scene = scene
        stage.icons.add(Image(Main::class.java.getResourceAsStream("/fr33r4ng3r.png")))

        ResizeHelper.addResizeListener(stage)

        stage.show()
    }

    companion object {

        private val sceneUrl = Main::class.java.getResource("/incamoon/eso/adeps2/scene.fxml")
        private val LOG = Logger.getLogger(Main::class.java.name)

        @JvmStatic
        fun main(args: Array<String>) {
            launch(Main::class.java, *args)
        }

    }
}
