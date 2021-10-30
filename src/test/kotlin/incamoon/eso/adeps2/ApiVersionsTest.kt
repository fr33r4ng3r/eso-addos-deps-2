package incamoon.eso.adeps2

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runBlockingTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*

@ExperimentalCoroutinesApi
@DelicateCoroutinesApi
internal class ApiVersionsTest {

    @Test
    fun testFetchWiki() = runBlocking {
        val versions = ApiVersions()
        val result = versions.fetchWiki()
        assertTrue(result)
        println(versions.getVersions())
    }

    @Test
    fun testTryUpdaterFromWiki() = runBlocking {
        val versions = ApiVersions()
        versions.tryUpdateFromWiki()
        println(versions.getVersions())
    }
}