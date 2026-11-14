package app.lectrice.core.data

import app.lectrice.core.model.ErrorName
import app.lectrice.core.model.LectriceError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class ModelInstallerTest {
    @get:Rule val folder = TemporaryFolder()
    private val spec = ModelSpec.Fast

    private class Device : InstallerDevice {
        override fun freeBytes() = Long.MAX_VALUE / 2
        override fun isMetered() = false
        override fun hasNetwork(wifiOnly: Boolean) = true
    }

    /** Answers each request with the next of [answers] (status, body), recording the Range header asked for. */
    private class Server(private val answers: List<Pair<Int, ByteArray>>) {
        val ranges = mutableListOf<String?>()

        fun connect(url: String): HttpURLConnection = object : HttpURLConnection(URL(url)) {
            private val answer = answers[ranges.size.coerceAtMost(answers.lastIndex)].also { ranges += null }

            override fun setRequestProperty(key: String, value: String) {
                if (key == "Range") ranges[ranges.lastIndex] = value
            }

            override fun getResponseCode() = answer.first
            override fun getInputStream(): InputStream = ByteArrayInputStream(answer.second)
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
        }
    }

    private fun installer(server: Server, unpacked: MutableList<ModelSpec> = mutableListOf()) =
        ModelInstaller(ModelStore(folder.root), Device(), server::connect) { _, spec, _ -> unpacked += spec }

    private suspend fun failure(block: suspend () -> Unit): ErrorName {
        try {
            block()
        } catch (error: LectriceError) {
            return error.name
        }
        fail("no error")
        error("unreachable")
    }

    @Test
    fun `a download resumes from the part file and a wrong archive is fetched again once`() = runTest {
        val server = Server(listOf(HttpURLConnection.HTTP_PARTIAL to ByteArray(10), HttpURLConnection.HTTP_OK to ByteArray(20)))
        val unpacked = mutableListOf<ModelSpec>()
        val installer = installer(server, unpacked)
        installer.partFile(spec).apply { parentFile.mkdirs() }.writeBytes(ByteArray(5))

        val name = failure { installer.install(spec, wifiOnly = false) }

        assertEquals(ErrorName.ModelChecksumMismatch, name)
        assertEquals(listOf("bytes=5-", null), server.ranges)
        assertFalse(installer.partFile(spec).exists())
        assertEquals(emptyList<ModelSpec>(), unpacked)
    }

    @Test
    fun `server errors are retried three times, then the download fails`() = runTest {
        val server = Server(listOf(HttpURLConnection.HTTP_UNAVAILABLE to ByteArray(0)))

        val name = failure { installer(server).install(spec, wifiOnly = false) }

        assertEquals(ErrorName.ModelDownloadFailed, name)
        assertEquals(4, server.ranges.size)
    }

    @Test
    fun `an imported file that is no known model is refused and not kept`() {
        val installer = installer(Server(emptyList()))

        val name = try {
            installer.importArchive { ByteArrayInputStream(ByteArray(64)) }
            null
        } catch (error: LectriceError) {
            error.name
        }

        assertEquals(ErrorName.ModelChecksumMismatch, name)
        assertNull(folder.root.resolve("models/.download").listFiles()?.firstOrNull())
    }
}
