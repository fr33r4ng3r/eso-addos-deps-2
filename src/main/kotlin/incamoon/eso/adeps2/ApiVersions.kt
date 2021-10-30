package incamoon.eso.adeps2

import com.google.gson.Gson
import javafx.collections.ObservableList
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger
import java.util.regex.MatchResult
import java.util.regex.Pattern

@DelicateCoroutinesApi
class ApiVersions {

    private val filePath: Path
    private val apiVersions = Properties()
    private var versionList: ObservableList<String>? = null

    internal suspend fun tryUpdateFromWiki() {
        val success = fetchWiki()
        if (success) {
            apiVersions.setProperty(
                LAST_UPDATED,
                LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
            )
            if (!writePropertiesFile()) return
            refresh()
        }
    }

    private suspend fun writePropertiesFile(): Boolean {
        try {
            withContext(Dispatchers.IO) { Files.createDirectories(filePath.parent) }
        } catch (e: IOException) {
            LOG.log(Level.SEVERE, e) { "Unable to create property store directories" }
            return false
        }
        try {
            withContext(Dispatchers.IO) {
                Files.newOutputStream(filePath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { stream ->
                    apiVersions.store(
                        stream,
                        "ESO API Versions"
                    )
                }
            }
        } catch (e: IOException) {
            LOG.log(
                Level.SEVERE, e
            ) { "Unable to read api-versions.properties file - friendly names will be unavailable" }
            return false
        }
        return true
    }

    internal suspend fun fetchWiki(): Boolean {
        val map =
            fetch(URI.create("https://wiki.esoui.com/w/api.php?action=query&prop=revisions&titles=APIVersion&rvslots=*&rvprop=content&formatversion=2&format=json"))

        val page = map?.get("query")
            ?.let { (it as Map<*, *>)["pages"] }
            ?.let { (it as Map<*, *>).values.first() }
            ?.let { (it as Map<*, *>)["revisions"] }
            ?.let { (it as List<*>)[0] }
            ?.let { (it as Map<*, *>)["*"] }
            ?.let { (it as String) }

        return try {
            page?.let { parseWiki(it) }
            true
        } catch (e: Throwable) {
            LOG.log(Level.SEVERE, e) { "Unable to get results from wiki" }
            false
        }
    }

    private fun parseWiki(page: String): Boolean {
        val reLiveVersion = Pattern.compile("== live API version ==\\n\\n===(\\d*)===", Pattern.MULTILINE)
        val rePTSVersion = Pattern.compile("== PTS API version ==\\n\\n===(\\d*)===", Pattern.MULTILINE)
        val reVersionSection = Pattern.compile("===(\\d*)===(.*?)(?:<br>|\n)\n\n", Pattern.DOTALL)
        val reSectionUpdate = Pattern.compile("^\\* '''Update''': ([^\\['\\n]*)", Pattern.MULTILINE)
        val reSectionFeature = Pattern.compile("^\\* '''Features''': ([^\\['\\n]*)", Pattern.MULTILINE)
        try {
            val reLiveMatcher = reLiveVersion.matcher(page)
            if (reLiveMatcher.find()) {
                val liveVersion = reLiveMatcher.group(1)
                apiVersions.setProperty("live-version", liveVersion)
            }
            val rePTSMatcher = rePTSVersion.matcher(page)
            if (rePTSMatcher.find()) {
                val ptsVersion = rePTSMatcher.group(1)
                apiVersions.setProperty("pts-version", ptsVersion)
            }
            val reSectionMatcher = reVersionSection.matcher(page)
            reSectionMatcher.results().forEach { matchResult: MatchResult ->
                val versionNumber = matchResult.group(1)
                val section = matchResult.group(2)
                val updateMatcher = reSectionUpdate.matcher(section)
                if (updateMatcher.find()) {
                    val update = updateMatcher.group(1).trim { it <= ' ' }
                    apiVersions.setProperty("v$versionNumber.update", update)
                }
                val featureMatcher = reSectionFeature.matcher(section)
                if (featureMatcher.find()) {
                    val feature = featureMatcher.group(1).trim { it <= ' ' }
                    apiVersions.setProperty("v$versionNumber.feature", feature)
                }
            }
        } catch (e: Exception) {
            LOG.log(Level.SEVERE, e) { "Unable to parse Wiki response" }
            return false
        }
        return true
    }

    private suspend fun fetch(uri: URI): Map<*, *>? {
        val gson = Gson()
        val client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(5))
            .executor(Dispatchers.IO.asExecutor())
            .build();
        val request = HttpRequest.newBuilder(uri)
            .header("Accept", "application/json")
            .build()
        return try {
            val result = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            gson.fromJson(result.body(), Map::class.java)
        } catch (e: Throwable) {
            LOG.log(
                Level.SEVERE,
                e
            ) { "Unable to get results from wiki" }
            null
        }
    }

    suspend fun bind(vss: ObservableList<String>?) {
        versionList = vss
        refresh()
    }

    fun getVersionFeature(number: Int): String {
        return apiVersions.getProperty("v$number.feature", number.toString())
    }

    private suspend fun refresh() {
        if (versionList == null) return
        val liveVersion = apiVersions.getProperty("live-version") ?: return
        val ptsVersion = apiVersions.getProperty("pts-version")
        for (i in versionList!!.indices) {
            val version = versionList!![i]
            if (version.length == 6) {
                val feature = apiVersions.getProperty("v$version.feature")
                val update = apiVersions.getProperty("v$version.update")
                val isLive = version == liveVersion
                val isPTS = version == ptsVersion
                if (feature != null && update != null) {
                    versionList!![i] =
                        version + " (" + update + " " + feature + ") " + if (isLive) "[*]" else if (isPTS) "[^]" else " "
                } else {
                    if (LocalDate.parse(
                            apiVersions.getProperty(
                                LAST_UPDATED,
                                LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                            ), DateTimeFormatter.BASIC_ISO_DATE
                        ).isBefore(
                            LocalDate.now().minusDays(
                                MISSING_VERSION_FREQUENCY_DAYS.toLong()
                            )
                        )
                    ) {
                        tryUpdateFromWiki()
                    }
                }
            }
        }
    }

    fun getVersions(): Set<String> {
        return apiVersions.stringPropertyNames().asSequence().map {
            it.split(".")[0]
        }.filter { it.startsWith("v") }.toSet()
    }

    companion object {
        private val LOG = Logger.getLogger(ApiVersions::class.java.name)

        const val UPDATE_FREQUENCY_DAYS = 5
        private const val MISSING_VERSION_FREQUENCY_DAYS = 1
        const val LAST_UPDATED = "last-updated"
    }

    init {
        val userHome = System.getProperty("user.home")
        filePath = Paths.get(userHome, ".eso-addon-deps-2", "api-versions.properties")
        if (Files.exists(filePath)) {
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    withContext(Dispatchers.IO) {
                        Files.newInputStream(filePath, StandardOpenOption.READ).use { stream ->
                            apiVersions.load(stream)
                            if (LocalDate.parse(
                                    apiVersions.getProperty(
                                        LAST_UPDATED,
                                        LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                                    ), DateTimeFormatter.BASIC_ISO_DATE
                                ).isBefore(LocalDate.now().minusDays(UPDATE_FREQUENCY_DAYS.toLong()))
                            ) {
                                tryUpdateFromWiki()
                            }
                        }
                    }
                } catch (e: IOException) {
                    LOG.log(
                        Level.SEVERE, e
                    ) { "Unable to read api-versions.properties file - friendly names will be unavailable" }
                }
            }
        } else {
            try {
                javaClass.getResourceAsStream("api-versions.default.properties")
                    ?.use { stream -> apiVersions.load(stream) }
            } catch (e: IOException) {
                LOG.log(Level.SEVERE, e) { "Unable to copy default properties" }
            }
            GlobalScope.launch(Dispatchers.IO) {
                writePropertiesFile()
                tryUpdateFromWiki()
            }
        }
    }
}