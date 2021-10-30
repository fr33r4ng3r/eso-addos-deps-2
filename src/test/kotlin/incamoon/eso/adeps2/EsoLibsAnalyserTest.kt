package incamoon.eso.adeps2

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runBlockingTest
import org.junit.jupiter.api.Test

@ExperimentalCoroutinesApi
internal class EsoLibsAnalyserTest {

    private suspend fun loadTestData() = coroutineScope {
        val target = EsoLibsAnalyser("""C:\Users\Ian\Documents\Elder Scrolls Online\live\AddOns.backup""")
        val logChannel = Channel<String>(10)
        launch(Dispatchers.Unconfined) {
            repeat(1) {
                println(logChannel.receive())
            }
        }
        target.load(logChannel)
        target
    }

    @Test
    fun load() = runBlockingTest {
        loadTestData()
    }

    @Test
    fun isEmbedded() = runBlockingTest {
        val target = loadTestData()
        target.getLibs().asSequence().filter { it.name == "LibAsync" }.forEach { with(it) { println("name: $name, version: $version, numericVersion: $addonVersion, level: $nestedLevel, embedded: $isEmbedded") } }
    }

    @Test
    fun getMissing() {
    }

    @Test
    fun getDuplicates() {
    }

    @Test
    fun isAddonMissing() {
    }

    @Test
    fun isLibraryUnreferenced() {
    }

    @Test
    fun isDuplicate() {
    }

    @Test
    fun getCount() {
    }

    @Test
    fun getVersions() {
    }
}