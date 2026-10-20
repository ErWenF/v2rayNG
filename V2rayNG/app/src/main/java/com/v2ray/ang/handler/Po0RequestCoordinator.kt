package com.v2ray.ang.handler

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.Po0ResultState
import com.v2ray.ang.dto.Po0TokenResult
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.po0ServerId
import com.v2ray.ang.util.po0TokenId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.ConcurrentHashMap

/**
 * A UI-only manual batch can outlive the absence of an automatic daemon. This owner serializes
 * their complete query/write transactions across processes and shares server cooldowns, without
 * creating a background service. Files contain only a hashed server ID and boot-scoped deadline.
 */
internal class Po0RequestCoordinator private constructor(
    private val directory: () -> File,
    private val elapsedRealtime: () -> Long,
    private val bootCount: () -> Int?,
    private val acquireTimeoutMillis: Long,
    private val reportFailure: (String, Exception) -> Unit,
) {
    constructor(context: Context) : this(
        directory = { File(context.applicationContext.noBackupFilesDir, "po0-requests") },
        elapsedRealtime = SystemClock::elapsedRealtime,
        bootCount = { Settings.Global.getInt(context.applicationContext.contentResolver, Settings.Global.BOOT_COUNT) },
        acquireTimeoutMillis = 90_000L,
        reportFailure = { id, error ->
            LogUtil.w(AppConfig.TAG, "Po0 request coordination serverId=$id failed", IOException(error.javaClass.simpleName))
        },
    )

    internal constructor(
        directory: File,
        elapsedRealtime: () -> Long,
        bootCount: () -> Int?,
        acquireTimeoutMillis: Long = 90_000L,
        reportFailure: (String, Exception) -> Unit = { _, _ -> },
    ) : this({ directory }, elapsedRealtime, bootCount, acquireTimeoutMillis, reportFailure)

    suspend fun run(token: String, block: suspend () -> Po0TokenResult): Po0TokenResult = withContext(Dispatchers.IO) {
        val serverId = po0ServerId(token)
        val lockDirectory = directory().absoluteFile.normalize()
        val lockFile = File(lockDirectory, "$serverId.lock")
        val local = localLocks.getOrPut(lockFile.path) { Mutex() }
        var localAcquired = false
        var storage: RandomAccessFile? = null
        var fileLock: FileLock? = null
        try {
            val acquired = withTimeoutOrNull(acquireTimeoutMillis.coerceIn(1L, 90_000L)) {
                local.lock()
                localAcquired = true
                currentCoroutineContext().ensureActive()
                if (!lockDirectory.isDirectory && !lockDirectory.mkdirs() && !lockDirectory.isDirectory) {
                    throw IOException("Po0 request directory unavailable")
                }
                val opened = RandomAccessFile(lockFile, "rw")
                storage = opened
                var contentionDelay = 50L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    fileLock = try {
                        opened.channel.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        null
                    }
                    if (fileLock != null) break
                    delay(contentionDelay)
                    contentionDelay = (contentionDelay * 2).coerceAtMost(250L)
                }
                true
            } ?: false
            if (!acquired) throw IOException("Po0 request coordination timed out")
            currentCoroutineContext().ensureActive()
            val opened = checkNotNull(storage)
            val boot = try {
                bootCount()?.takeIf { it >= 0 }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reportFailure(serverId, error)
                null
            }
            val now = elapsedRealtime().coerceAtLeast(0)
            val notBefore = readDeadline(opened, boot, now, serverId)
            if (notBefore > now) return@withContext Po0TokenResult(
                po0TokenId(token), Po0ResultState.ERROR, httpStatus = 429,
                requestedSlot = token.substringAfterLast('@', "").toIntOrNull(), retryAfterMillis = notBefore - now,
            )
            val result = block()
            if (result.retryAfterMillis > 0 && boot != null) {
                val completedAt = elapsedRealtime().coerceAtLeast(0)
                val deadline = if (result.retryAfterMillis > Long.MAX_VALUE - completedAt) Long.MAX_VALUE
                    else completedAt + result.retryAfterMillis
                opened.setLength(0)
                opened.seek(0)
                opened.writeInt(METADATA_VERSION)
                opened.writeInt(boot)
                opened.writeLong(deadline)
                opened.channel.force(true)
            }
            result
        } finally {
            try {
                fileLock?.release()
            } catch (error: IOException) {
                reportFailure(serverId, error)
            } finally {
                try {
                    storage?.close()
                } catch (error: IOException) {
                    reportFailure(serverId, error)
                } finally {
                    if (localAcquired) local.unlock()
                }
            }
        }
    }

    private fun readDeadline(storage: RandomAccessFile, boot: Int?, now: Long, serverId: String): Long {
        if (storage.length() == 0L) return 0L
        if (boot != null && storage.length() == 16L) {
            storage.seek(0)
            val version = storage.readInt()
            val storedBoot = storage.readInt()
            val deadline = storage.readLong()
            if (version == METADATA_VERSION && storedBoot >= 0 && deadline >= 0) {
                if (storedBoot == boot && deadline > now) return deadline
                storage.setLength(0)
                return 0L
            }
        } else if (boot == null) {
            // No reliable boot identity: never interpret persisted elapsed time after a reboot.
            storage.setLength(0)
            return 0L
        }
        reportFailure(serverId, IOException("Invalid Po0 coordination metadata"))
        storage.setLength(0)
        return 0L
    }

    private companion object {
        const val METADATA_VERSION = 1
        // FileLock is process-scoped: one local owner opens a channel at a time, while the OS lock
        // serializes the other app process. Keep lock files stable; unlinking them can split owners.
        val localLocks = ConcurrentHashMap<String, Mutex>()
    }
}
