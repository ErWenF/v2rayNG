package com.v2ray.ang.ui.logcat

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogcatViewModel(application: Application) : BaseViewModel(application) {
    private val logsetsAll: MutableList<String> = mutableListOf()
    private var currentFilter: String = ""
    private val logcatMutex = Mutex()
    private val _isSharing = MutableStateFlow(false)
    val isSharing: StateFlow<Boolean> = _isSharing.asStateFlow()

    private val _filteredLogs = MutableStateFlow<List<String>>(emptyList())
    val filteredLogs: StateFlow<List<String>> = _filteredLogs.asStateFlow()
    val logEntries: StateFlow<List<LogcatEntry>> = filteredLogs
        .map(::createLogcatEntries)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun loadLogcat() {
        if (isLoading.value || isSharing.value) return
        launchLoading {
            try {
                logcatMutex.withLock { replaceLogs(readLogcatSnapshot()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                LogUtil.e(AppConfig.TAG, "Failed to get logcat", e)
                toastError(R.string.toast_failure)
            }
        }
    }

    /** Capture again at share time; a search or an old screen snapshot must not hide evidence. */
    internal suspend fun prepareLogcatShare(
        cacheDirectory: File = app.cacheDir,
        readLogs: suspend () -> List<String> = { readLogcatSnapshot() }
    ): File? {
        if (!_isSharing.compareAndSet(expect = false, update = true)) return null
        try {
            val snapshot = logcatMutex.withLock {
                readLogs().also {
                    if (it.isEmpty()) throw IOException("No application logs available")
                    currentCoroutineContext().ensureActive()
                    replaceLogs(it)
                }
            }
            return withContext(Dispatchers.IO) {
                val shareDir = File(cacheDirectory, "shared_logs")
                if (!shareDir.isDirectory && !shareDir.mkdirs()) {
                    throw IOException("Cannot create Logcat share directory")
                }
                // Keep recent exports readable while another app is still receiving the file.
                val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
                shareDir.listFiles()?.filter {
                    it.isFile && it.name.startsWith("v2rayNG_logcat_") &&
                        it.extension == "txt" && it.lastModified() < cutoff
                }?.forEach { it.delete() }
                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                val logFile = File.createTempFile("v2rayNG_logcat_${timestamp}_", ".txt", shareDir)
                try {
                    // Export oldest first for following the failure and recovery timeline.
                    logFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                        snapshot.asReversed().forEach {
                            currentCoroutineContext().ensureActive()
                            writer.appendLine(it)
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    logFile
                } catch (e: Exception) {
                    logFile.delete()
                    throw e
                }
            }
        } finally {
            _isSharing.value = false
        }
    }

    fun copyLogcat() {
        val all = filteredLogs.value.joinToString("\n")
        Utils.setClipboard(app, all)
        toast(R.string.toast_success)
    }

    fun clearLogcat() {
        if (isLoading.value || isSharing.value) return
        launchLoading {
            try {
                logcatMutex.withLock {
                    readLogcatOutput(listOf("logcat", "-c"))
                    replaceLogs(emptyList())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                LogUtil.e(AppConfig.TAG, "Failed to clear logcat", e)
                toastError(R.string.toast_failure)
            }
        }
    }

    fun filter(content: String?) {
        currentFilter = content?.trim() ?: ""
        applyFilter()
    }

    private fun applyFilter() {
        _filteredLogs.value = if (currentFilter.isEmpty()) {
            logsetsAll.toList()
        } else {
            logsetsAll.filter { it.contains(currentFilter) }
        }
    }

    private fun replaceLogs(logs: List<String>) {
        logsetsAll.clear()
        logsetsAll.addAll(logs)
        applyFilter()
    }
}

private suspend fun readLogcatSnapshot(): List<String> = readLogcatOutput(
    listOf(
        "logcat", "-d", "-v", "time", "-t", "10000", "-s",
        "GoLog", AppConfig.ANG_PACKAGE, "AndroidRuntime", "System.err"
    )
).asReversed()

/** The child is short lived and is also destroyed on timeout or lifecycle cancellation. */
internal suspend fun readLogcatOutput(
    command: List<String>,
    startProcess: (List<String>) -> Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
    timeoutMillis: Long = 10_000L
): List<String> = withContext(Dispatchers.IO) {
    coroutineScope {
        val process = startProcess(command)
        val output = async {
            runInterruptible {
                val lines = process.inputStream.bufferedReader().use { it.readLines() }
                val exitCode = process.waitFor()
                if (exitCode != 0) throw IOException("Logcat command failed (exit $exitCode)")
                lines
            }
        }
        try {
            withTimeout(timeoutMillis) { output.await() }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw IOException("Logcat command timed out", e)
        } finally {
            process.destroy()
            output.cancel()
        }
    }
}

data class LogcatEntry(val key: String, val text: String)

internal fun createLogcatEntries(logs: List<String>): List<LogcatEntry> {
    val occurrences = HashMap<String, Int>()
    // Identical lines are valid. Count oldest-first so filtering other lines or
    // prepending newer entries does not change the keys of existing occurrences.
    return logs.asReversed().map { line ->
        val occurrence = occurrences.getOrDefault(line, 0)
        occurrences[line] = occurrence + 1
        LogcatEntry("$occurrence:$line", line)
    }.asReversed()
}
