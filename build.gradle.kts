import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.apache.tools.ant.filters.ReplaceTokens
import java.io.FileInputStream
import java.util.Properties

plugins {
    kotlin("jvm") version "1.5.31"
    application
    id("org.openjfx.javafxplugin") version "0.0.10"
    id("org.beryx.jlink") version "2.24.2"
}

repositories {
    mavenCentral()
}

dependencies {

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.2-native-mt")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-javafx:1.5.2-native-mt")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.5.2-native-mt")
    implementation("com.jfoenix:jfoenix:9.0.10")
    implementation("com.google.code.gson:gson:2.8.8")

    testImplementation("org.junit.jupiter:junit-jupiter:5.8.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.5.2-native-mt")
}

val tag: String = System.getenv().getOrDefault("TAG", "")

tasks.withType<Copy> {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    filesMatching("**/version.properties") {
        expand ("version" to tag)
    }
}

val compileKotlin: KotlinCompile by tasks
val compileJava: JavaCompile by tasks
sourceSets {
    main {
        compileKotlin.destinationDirectory.set(compileJava.destinationDirectory)
        output.setResourcesDir(compileJava.destinationDirectory)
    }
    test {
        compileKotlin.destinationDirectory.set(compileJava.destinationDirectory)
        output.setResourcesDir(compileJava.destinationDirectory)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(16))
    }
    modularity.inferModulePath.set(true)
}

kotlin {
    jvmToolchain {
        (this as JavaToolchainSpec).languageVersion.set(JavaLanguageVersion.of(16))
    }
}

javafx {
    version = "16"
    modules = listOf("javafx.controls", "javafx.fxml", "javafx.graphics", "javafx.base")
}

application {
    mainModule.set("incamoon.eso.adeps2.main")
    mainClass.set("incamoon.eso.adeps2.Main")
}

jlink {
    options.addAll("--strip-debug", "--compress", "2", "--no-header-files", "--no-man-pages")
    addExtraDependencies("javafx")
    launcher {
        name = "eso-addon-deps-2"
        version = tag
    }
    jpackage {
        imageOptions.addAll(arrayOf("--resource-dir", "${projectDir}\\jpackage", "--verbose"))
        installerOptions.addAll(
            arrayOf(
                "--win-per-user-install",
                "--win-dir-chooser",
                "--type",
                "msi",
                "--win-shortcut",
                "--win-menu",
                "--win-menu-group",
                "fr33t00lz"
            )
        )
    }
}
