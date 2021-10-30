package incamoon.eso.adeps2

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.UncheckedIOException
import java.math.BigDecimal
import java.nio.charset.Charset
import java.nio.charset.MalformedInputException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.io.path.*
import kotlin.math.min

class EsoLibsAnalyser(private val addonFolder: String) {

    companion object {

        private val LOG: Logger = Logger.getLogger(EsoLibsAnalyser::class.java.name)

        private val MINUS_ONE: BigDecimal = BigDecimal(-1)

        data class AddonIdent(val name: String, val addonVersion: BigDecimal)
        private data class VersionAndDirectory(val version: BigDecimal, val directory: Path)

        data class Addon(
            val name: String,
            val title: String,
            val version: String?,
            val apiVersion: String?,
            val addonVersion: BigDecimal,
            val isLib: Boolean?,
            val fInfo: List<Path>,
            val nestedLevel: Int,
            val dependsOn: List<AddonIdent>,
            val optionalDependsOn: List<AddonIdent>,
        ) {

            data class Builder(
                var name: String? = null,
                var title: String? = null,
                var version: String? = null,
                var apiVersion: String? = null,
                var addonVersion: BigDecimal? = null,
                var isLib: Boolean? = null,
                var fInfo: List<Path> = emptyList(),
                var nestedLevel: Int? = null,
                var dependsOn: MutableList<AddonIdent> = LinkedList(),
                var optionalDependsOn: MutableList<AddonIdent> = LinkedList(),
            ) {
                fun name(value: String) = apply { this.name = value }
                fun title(value: String) = apply { this.title = value }
                fun version(value: String) = apply { this.version = value }
                fun apiVersion(value: String) = apply { this.apiVersion = value }
                fun addonVersion(value: BigDecimal) = apply { this.addonVersion = value }
                fun isLib(value: Boolean) = apply { this.isLib = value }
                fun fInfo(value: List<Path>) = apply { this.fInfo = value }
                fun nestedLevel(value: Int) = apply { this.nestedLevel = value }
                fun dependsOn(value: List<AddonIdent>) = apply { this.dependsOn.addAll(value) }
                fun optionalDependsOn(value: List<AddonIdent>) = apply { this.optionalDependsOn.addAll(value) }
                fun build(): Addon {
                    val title = when (this.title) {
                        null -> name!!
                        else -> {
                            val colorMatcher: Matcher = reColor.matcher(this.title!!)
                            val step1 = if (colorMatcher.find()) {
                                colorMatcher.replaceAll("")
                            } else {
                                this.title!!
                            }
                            val textureMatcher = reTexture.matcher(step1)
                            if (textureMatcher.find()) {
                                textureMatcher.replaceAll("")
                            } else {
                                step1
                            }
                        }
                    }
                    return Addon(
                        name!!,
                        title,
                        version,
                        apiVersion,
                        addonVersion ?: BigDecimal.ZERO,
                        isLib,
                        fInfo,
                        nestedLevel ?: -1,
                        dependsOn,
                        optionalDependsOn
                    )
                }
            }

            val dependencies
                get() = dependsOn.joinToString(", ") { it.name }

            val optionalDependencies
                get() = optionalDependsOn.joinToString(", ") { it.name }

            val apiVersions: Sequence<String>
                get() {
                    return apiVersion?.split(API_VRS_RE)?.filter { s: String -> s.isNotBlank() }?.asSequence()
                        ?: emptySequence()
                }

            fun getMaxApiVersion(): Int {
                return apiVersions.asSequence().map { it.toInt() }.maxOrNull() ?: 0
            }

            fun references(dep: Addon?): Boolean {
                if (dep == null) return false
                return (dependsOn.asSequence() + optionalDependsOn.asSequence()).any { a -> (a.name == dep.name) }
            }

            fun toBuilder(): Builder {
                return Builder(
                    name,
                    title,
                    version,
                    apiVersion,
                    addonVersion,
                    isLib,
                    fInfo,
                    nestedLevel,
                    dependsOn.toMutableList(),
                    optionalDependsOn.toMutableList()
                )
            }

            val paths: List<String>
                get() {
                    return fInfo.map { p -> p.parent.toString() }
                }

            val isEmbedded: Boolean
                get() {
                    return nestedLevel > 0
                }

            private companion object {
                // |c00FF00FCO |cFFFF00ItemSaver|t32:32:FCOItemSaver/FCOIS.dds|t
                private val reColor: Pattern by lazy { Pattern.compile("\\|c[0-9A-Fa-f]{6}|\\|r|\\|$") }
                private val reTexture: Pattern by lazy { Pattern.compile("\\|t\\d+:\\d+:.*\\|t") }
            }
        }

        const val DEPENDS_ON = "## DependsOn: "
        const val OPTIONAL_DEPENDS_ON = "## OptionalDependsOn: "
        const val API_VERSION = "## APIVersion: "
        const val TITLE = "## Title: "
        const val VERSION = "## Version: "
        const val ADDON_VERSION = "## AddOnVersion: "
        const val IS_LIBRARY = "## IsLibrary: "

        val DEPS_RE: Pattern by lazy { Pattern.compile("[\\s,\\r\\n]+", Pattern.MULTILINE) }
        val API_VRS_RE: Pattern by lazy { Pattern.compile("[\\s,\\r\\n]+", Pattern.MULTILINE) }
    }

    private val addonVersions = HashMap<String, MutableSet<VersionAndDirectory>>()
    private val addons = HashMap<AddonIdent, Addon>()

    suspend fun load(logChannel: Channel<String>) = coroutineScope {
        val path: Path = Paths.get(addonFolder)
        if (!Files.exists(path)) {
            logChannel.send("ERROR! ESO Addon Path not found!$addonFolder")
            throw FileNotFoundException("ESO Addon Path not found!$addonFolder")
        }
        load(path, 0, logChannel)
        logChannel.send("Loaded " + addons.size + " AddOns")
        LOG.log(Level.INFO, "Loaded " + addons.size + " AddOns")
    }

    private suspend fun load(path: Path, level: Int, logChannel: Channel<String>): Unit = coroutineScope {
        withContext(Dispatchers.IO) { path.resolve(".").listDirectoryEntries() }.forEach { p ->
            if (!p.isDirectory()) {
                return@forEach
            }
            val name = p.fileName.name
            if (name.startsWith(".")) {
                return@forEach
            }
            val fInfo: Path = p.resolve("$name.txt")
            if (!Files.exists(fInfo)) {
                if (level == 0) {
                    LOG.log(Level.FINE, "directory $name does not have an info txt file.  Ignoring")
                }
                load(p, level + 1, logChannel)
                return@forEach
            }
            LOG.log(Level.INFO, "Loading: $name as level $level")
            val result = read(name, fInfo, StandardCharsets.UTF_8, level, logChannel)
            if (!result) {
                LOG.log(Level.INFO, "Re-Loading: $name")
                if (!read(name, fInfo, StandardCharsets.ISO_8859_1, level, logChannel)) {
                    LOG.log(Level.WARNING, "WARNING!  Failed to load Addon: $name bad charset")
                    logChannel.send("ERROR!  Failed to load Addon: $name bad charset")
                }
            } else {
                LOG.log(
                    Level.INFO,
                    "Addon: $name at " + Paths.get(addonFolder)
                        .relativize(fInfo) + " had " + addonVersions.getOrDefault(
                        name,
                        emptyList()
                    ).size + " versions"
                )
            }
            load(p, level + 1, logChannel)
        }
    }

    private suspend fun read(
        name: String,
        fInfo: Path,
        charset: Charset,
        level: Int,
        logChannel: Channel<String>
    ): Boolean = coroutineScope {
        async {
            fInfo.useLines(charset) { lines ->
                try {
                    val addonBuilder = Addon.Builder()
                        .name(name)
                        .fInfo(listOf(Paths.get(addonFolder).relativize(fInfo)))
                        .nestedLevel(level)
                    lines.forEach { line ->
                        if (line.startsWith(TITLE)) {
                            val title = line.substring(TITLE.length)
                            addonBuilder.title(title)
                        } else if (line.startsWith(VERSION)) {
                            val version = line.substring(VERSION.length)
                            addonBuilder.version(version)
                        } else if (line.startsWith(API_VERSION)) {
                            val version = line.substring(API_VERSION.length)
                            addonBuilder.apiVersion(version)
                        } else if (line.startsWith(ADDON_VERSION)) {
                            val version = line.substring(ADDON_VERSION.length)
                            try {
                                addonBuilder.addonVersion(BigDecimal(version))
                            } catch (e: NumberFormatException) {
                                logChannel.send("Addon + $name has incorrect addon version number : $version")
                                LOG.log(
                                    Level.WARNING,
                                    e
                                ) { "Addon + $name has incorrect addon version number : $version" }
                            }
                        } else if (line.startsWith(IS_LIBRARY)) {
                            val islib = line.substring(IS_LIBRARY.length)
                            addonBuilder.isLib("true".equals(islib, ignoreCase = true))
                        } else if (line.startsWith(DEPENDS_ON)) {
                            val deps = line.substring(DEPENDS_ON.length).split(DEPS_RE)
                            addonBuilder.dependsOn(
                                deps
                                    .map { obj -> obj.trim { it <= ' ' } }
                                    .filter { s -> s.isNotBlank() }
                                    .map { d -> parseDependency(name, logChannel, d) }
                            )
                        } else if (line.startsWith(OPTIONAL_DEPENDS_ON)) {
                            val deps = line.substring(OPTIONAL_DEPENDS_ON.length).split(DEPS_RE)
                            addonBuilder.optionalDependsOn(
                                deps
                                    .map { obj -> obj.trim { it <= ' ' } }
                                    .filter { s -> s.isNotBlank() }
                                    .map { d -> parseDependency(name, logChannel, d) }
                            )
                        }
                    }
                    val addon: Addon = addonBuilder.build()
                    addonVersions.computeIfAbsent(addon.name) { HashSet() }.add(
                        VersionAndDirectory(
                            addon.addonVersion,
                            Paths.get(addonFolder).relativize(fInfo.parent)
                        )
                    )
                    addons.merge(
                        AddonIdent(addon.name, addon.addonVersion),
                        addon
                    ) { old: Addon, new: Addon ->
                        LOG.info("merging ${old.name}:${old.version}:${old.fInfo} with ${new.name}:${new.version}:${new.fInfo} at levels: ${old.nestedLevel} : ${new.nestedLevel}")
                        val fI = HashSet((old.fInfo + new.fInfo))
                        old.toBuilder()
                            .fInfo(fI.toList())
                            .nestedLevel(min(old.nestedLevel, new.nestedLevel))
                            .build()
                    }
                    return@async true
                } catch (e: MalformedInputException) {
                    return@async false
                } catch (e: UncheckedIOException) {
                    logChannel.send("Error opening + " + fInfo.toString() + ".  " + e.message)
                    LOG.log(
                        Level.WARNING,
                        "Error opening + " + fInfo.toString() + " bad charset.  skipping... (" + e.message + ")"
                    )
                    return@async true
                }
            }
        }
    }.await()

    private suspend fun parseDependency(name: String, logChannel: Channel<String>, d: String): AddonIdent {
        return if (d.indexOf(">=") > 0) {
            val a = d.split(">=")
            try {
                AddonIdent(a[0].trim(), BigDecimal(a[1].trim()))
            } catch (e: NumberFormatException) {
                logChannel.send("Addon + $name has incorrect addon version number dependency: $d")
                LOG.log(
                    Level.WARNING, e
                ) { "Addon + $name has incorrect addon version number dependency : $d" }
                AddonIdent(a[0], BigDecimal.ZERO)
            }
        } else {
            AddonIdent(d, BigDecimal.ZERO)
        }
    }

    internal fun getLibs(): List<Addon> {
        return addons.values.asSequence().filter { a: Addon -> a.isLib != null && a.isLib }.sortedBy { it.name }
            .toList()
    }

    internal fun getAddons(): List<Addon> {
        return addons.values.asSequence().filter { a: Addon -> a.isLib == null || !a.isLib }.sortedBy { it.name }
            .toList()
    }

    internal fun getMissing(): List<AddonIdent> {
        val allDependencies = addons.values.asSequence().flatMap { a: Addon ->
            a.dependsOn.asSequence()
        }.distinct()
        val missingDependencies = allDependencies.filter { dep: AddonIdent ->
            isAddonMissing(dep)
        }
        val deduped = missingDependencies.groupBy { it.name }
        return deduped.keys.asSequence().map { k: String ->
            deduped[k]!![0]
        }.sortedBy { it.name }.toList()
    }

    internal fun getDuplicates(): List<AddonIdent> {
        return addonVersions.asSequence()
            .filter {
                val potential: Int = it.value.size
                if (potential <= 1) return@filter false
                val selfNested: Int = it.value.asSequence().map { p1 ->
                    it.value.asSequence().map { p2 ->
                        if (p1.directory != p2.directory && p2.directory.startsWith(p1.directory)) {
                            1
                        } else {
                            0
                        }
                    }.sum()
                }.sum()
                return@filter (potential - selfNested) > 1
            }.map {
                AddonIdent(
                    it.key,
                    it.value.map { v -> v.version }.maxOrNull() ?: BigDecimal.ZERO
                )
            }.toList()
    }

    internal fun getUnreferenced(): List<AddonIdent> {
        return getLibs().asSequence()
            .filter { isLibraryUnreferenced(it) }.map {
                AddonIdent(
                    it.name,
                    it.addonVersion
                )
            }.toList()
    }

    fun isAddonMissing(dep: AddonIdent): Boolean {
        val availableVersion =
            addonVersions.getOrDefault(dep.name, emptyList()).asSequence().map { it.version }.maxOrNull() ?: MINUS_ONE
        return availableVersion < dep.addonVersion
    }

    fun isLibraryUnreferenced(dep: Addon): Boolean {
        if (dep.nestedLevel > 0) return false
        return !addons.values.asSequence().flatMap { v: Addon ->
            (v.dependsOn + v.optionalDependsOn)
        }.distinct().filter { a -> (a.name == dep.name) }.any()
    }

    fun isDuplicate(addon: Addon): Boolean {
        val duplicates: Set<VersionAndDirectory> = addonVersions.getOrDefault(addon.name, emptySet())
        val potential: Int = duplicates.size
        if (potential <= 1) return false
        val selfNested: Int = duplicates.asSequence().map { p1 ->
            duplicates.asSequence().map { p2 ->
                if (p1.directory != p2.directory && p2.directory.startsWith(p1.directory)) {
                    1
                } else {
                    0
                }
            }.sum()
        }.sum()
        return (potential - selfNested) > 1
    }

    fun isDeletable(addon: Addon): Boolean {
        return addons.filterValues { it.name == addon.name && it.nestedLevel == 0 && it.addonVersion >= addon.addonVersion }
            .any()
    }

    fun getCount(): Int {
        return addons.size
    }

    fun getVersions(): List<String> {
        return addons.values.asSequence().flatMap { it.apiVersions }.map { v: String -> v.substring(0, 6) }
            .distinct().sortedDescending().toList()
    }

    suspend fun compress(addon: Addon, logChannel: Channel<String>) {
        val adds: Path = Paths.get(addonFolder)
        val root: Path = addon.fInfo.map { it.parent }.minByOrNull { it.nameCount }?.let { adds.resolve(it) } ?: return
        LOG.log(Level.INFO, "Compression with preserve $root")
        addon.fInfo.asSequence().map { it.parent }.map { adds.resolve(it) }.forEach { path ->
            if (path != root) {
                LOG.log(Level.WARNING, "DELETING $path")
                logChannel.send("DELETING: " + adds.relativize(path))
                deleteDirectory(path)
            }
        }
    }

    suspend fun delete(addon: Addon, logChannel: Channel<String>) {
        val adds: Path = Paths.get(addonFolder)
        addon.fInfo.asSequence().map { it.parent }.map { adds.resolve(it) }.forEach { path ->
            LOG.log(Level.WARNING, "DELETING $path")
            logChannel.send("DELETING: " + adds.relativize(path))
            deleteDirectory(path)
        }
    }

    private suspend fun deleteDirectory(path: Path): Boolean = coroutineScope {
        return@coroutineScope withContext(Dispatchers.IO) {
            try {
                Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .map { obj: Path -> obj.toFile() }
                    .allMatch { obj: File -> obj.delete() }
            } catch (e: IOException) {
                LOG.log(Level.SEVERE, e) { "Unable to delete $path" }
                false
            }
        }
    }

    suspend fun copyDownAndCompress(addon: Addon, logChannel: Channel<String>) {
        val adds: Path = Paths.get(addonFolder)
        val root: Path = addon.fInfo.map { it.parent }.minByOrNull { it.nameCount }?.let { adds.resolve(it) } ?: return
        val newRoot = adds.resolve(root.name)
        LOG.log(Level.INFO, "Copying $root down to $newRoot and ")
        logChannel.send("MOVING: ${adds.relativize(root)} to Addons Folder")
        withContext(Dispatchers.IO) { root.moveTo(newRoot, false) }
        addon.fInfo.asSequence().map { it.parent }.map { adds.resolve(it) }.forEach { path ->
            if (path != root) {
                LOG.log(Level.WARNING, "DELETING $path")
                logChannel.send("DELETING: ${adds.relativize(path)}")
                deleteDirectory(path)
            }
        }
    }
}