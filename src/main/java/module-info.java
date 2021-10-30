module incamoon.eso.adeps2.main {

    requires kotlin.stdlib;
    requires kotlin.stdlib.jdk8;
    requires kotlin.stdlib.jdk7;
    requires kotlinx.coroutines.core.jvm;
    requires javafx.graphics;
    requires javafx.controls;
    requires javafx.fxml;
    requires com.jfoenix;
    requires java.logging;
    requires java.prefs;
    requires java.net.http;
    requires com.google.gson;
    requires kotlinx.coroutines.jdk8;
    requires kotlinx.coroutines.javafx;

    opens incamoon.eso.adeps2 to javafx.base, javafx.fxml;
    exports incamoon.eso.adeps2 to javafx.fxml, javafx.graphics;

}