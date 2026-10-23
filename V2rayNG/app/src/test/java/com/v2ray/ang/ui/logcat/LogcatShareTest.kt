package com.v2ray.ang.ui.logcat

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LogcatShareTest {
    @TempDir
    lateinit var cacheDirectory: File

    private val viewModel = LogcatViewModel(mock<Application>())

    @Test
    fun shareCapturesFreshLogsWithoutTheSearchFilterAndExportsOldestFirst() = runBlocking {
        assertTrue(viewModel.filteredLogs.value.isEmpty())
        assertFalse(viewModel.isSharing.value)
        val previous = viewModel.prepareLogcatShare(cacheDirectory) { listOf("old snapshot") }!!
        viewModel.filter("YouTube")

        val shared = viewModel.prepareLogcatShare(cacheDirectory) {
            listOf("new YouTube error", "new VPN event", "older DNS request")
        }!!

        assertEquals(listOf("older DNS request", "new VPN event", "new YouTube error"), shared.readLines())
        assertEquals(listOf("new YouTube error"), viewModel.filteredLogs.value)
        assertNotEquals(previous, shared)
        assertEquals("old snapshot", previous.readText().trim())
        assertFalse(viewModel.isSharing.value)
    }

    @Test
    fun shareWorksBeforeTheLogScreenHasBeenRefreshed() = runBlocking {
        viewModel.filter("no matches")
        val shared = viewModel.prepareLogcatShare(cacheDirectory) { listOf("fresh message") }!!

        assertEquals(listOf("fresh message"), shared.readLines())
        assertTrue(viewModel.filteredLogs.value.isEmpty())
        assertFalse(viewModel.isSharing.value)
    }

    @Test
    fun anEmptyCaptureDoesNotExportAnOldSnapshotOrAnEmptyFile() {
        runBlocking { viewModel.prepareLogcatShare(cacheDirectory) { listOf("old snapshot") } }
        assertThrows(IOException::class.java) {
            runBlocking { viewModel.prepareLogcatShare(cacheDirectory) { emptyList() } }
        }

        assertEquals(1, File(cacheDirectory, "shared_logs").listFiles()!!.size)
        assertFalse(viewModel.isSharing.value)
    }

    @Test
    fun readFailureDoesNotShareStaleLogsAndAllowsRetry() {
        runBlocking { viewModel.prepareLogcatShare(cacheDirectory) { listOf("old snapshot") } }
        assertThrows(IOException::class.java) {
            runBlocking { viewModel.prepareLogcatShare(cacheDirectory) { throw IOException("capture failed") } }
        }

        assertEquals(listOf("old snapshot"), viewModel.filteredLogs.value)
        assertFalse(viewModel.isSharing.value)
        val retry = runBlocking { viewModel.prepareLogcatShare(cacheDirectory) { listOf("retry succeeded") } }!!
        assertEquals(listOf("retry succeeded"), retry.readLines())
    }

    @Test
    fun anUnavailableShareDirectoryFailsAndResetsTheProgressState() {
        File(cacheDirectory, "shared_logs").writeText("not a directory")

        assertThrows(IOException::class.java) {
            runBlocking { viewModel.prepareLogcatShare(cacheDirectory) { listOf("fresh message") } }
        }
        assertFalse(viewModel.isSharing.value)
    }

    @Test
    fun repeatedShareClicksDoNotStartAnotherCapture() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val first = async {
            viewModel.prepareLogcatShare(cacheDirectory) {
                started.complete(Unit)
                finish.await()
                listOf("fresh message")
            }
        }
        started.await()

        assertTrue(viewModel.isSharing.value)
        assertNull(viewModel.prepareLogcatShare(cacheDirectory) { error("duplicate capture") })
        finish.complete(Unit)
        assertNotNull(first.await())
        assertFalse(viewModel.isSharing.value)
        assertEquals(1, File(cacheDirectory, "shared_logs").listFiles()!!.size)
    }

    @Test
    fun cancellingCaptureDoesNotProduceAReportAndAllowsAnotherShare() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val first = async {
            viewModel.prepareLogcatShare(cacheDirectory) {
                started.complete(Unit)
                CompletableDeferred<List<String>>().await()
            }
        }
        started.await()
        first.cancelAndJoin()

        assertFalse(viewModel.isSharing.value)
        assertFalse(File(cacheDirectory, "shared_logs").exists())
        assertNotNull(viewModel.prepareLogcatShare(cacheDirectory) { listOf("retry") })
    }

    @Test
    fun cleanupOnlyRemovesExpiredLogExportsAndKeepsRecentAttachments() = runBlocking {
        val directory = File(cacheDirectory, "shared_logs").apply { mkdirs() }
        val expired = File(directory, "v2rayNG_logcat_expired.txt").apply {
            writeText("expired")
            assertTrue(setLastModified(System.currentTimeMillis() - 48 * 60 * 60 * 1000L))
        }
        val unrelated = File(directory, "other.txt").apply {
            writeText("keep")
            assertTrue(setLastModified(expired.lastModified()))
        }
        val recent = File(directory, "v2rayNG_logcat_recent.txt").apply { writeText("keep") }

        assertNotNull(viewModel.prepareLogcatShare(cacheDirectory) { listOf("fresh message") })
        assertFalse(expired.exists())
        assertTrue(unrelated.exists())
        assertTrue(recent.exists())
    }

    @Test
    fun successfulProcessCapturePreservesLinesAndReleasesTheChild() = runBlocking {
        val process = StubProcess("older\nnewer\n".byteInputStream())
        val lines = readLogcatOutput(listOf("logcat"), startProcess = { process })

        assertEquals(listOf("older", "newer"), lines)
        assertTrue(process.destroyed)
    }

    @Test
    fun failedProcessCaptureDoesNotTreatErrorOutputAsLogs() {
        val process = StubProcess("permission denied".byteInputStream(), exitCode = 1)

        assertThrows(IOException::class.java) {
            runBlocking { readLogcatOutput(listOf("logcat"), startProcess = { process }) }
        }
        assertTrue(process.destroyed)
    }

    @Test
    fun captureTimeoutDestroysTheChildInsteadOfWaitingIndefinitely() {
        val stream = BlockingInputStream()
        val process = StubProcess(stream)

        assertThrows(IOException::class.java) {
            runBlocking {
                readLogcatOutput(listOf("logcat"), startProcess = { process }, timeoutMillis = 100)
            }
        }
        assertTrue(process.destroyed)
    }

    @Test
    fun cancellingProcessCaptureDestroysTheChild() = runBlocking {
        val stream = BlockingInputStream()
        val process = StubProcess(stream)
        val capture = async { readLogcatOutput(listOf("logcat"), startProcess = { process }) }
        withContext(Dispatchers.IO) { assertTrue(stream.started.await(5, TimeUnit.SECONDS)) }
        capture.cancelAndJoin()

        assertTrue(process.destroyed)
    }

    private class StubProcess(
        private val output: InputStream,
        private val exitCode: Int = 0
    ) : Process() {
        @Volatile
        var destroyed = false
            private set

        override fun getInputStream(): InputStream = output
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor(): Int = exitCode
        override fun exitValue(): Int = exitCode
        override fun destroy() {
            destroyed = true
            output.close()
        }
    }

    private class BlockingInputStream : InputStream() {
        val started = CountDownLatch(1)
        private val released = CountDownLatch(1)

        override fun read(): Int {
            started.countDown()
            released.await()
            return -1
        }

        override fun close() { released.countDown() }
    }
}
