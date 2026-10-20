package com.v2ray.ang.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** Invalidating a session never waits for serialization, MMKV, or an older manager's disk write. */
internal class Po0StatusWriteToken {
    private val newestSubmission = AtomicLong()

    fun invalidate() { newestSubmission.set(-1L) }

    fun nextSubmission(): Long? {
        while (true) {
            val previous = newestSubmission.get()
            if (previous < 0) return null
            if (newestSubmission.compareAndSet(previous, previous + 1)) return previous + 1
        }
    }

    fun isCurrent(submission: Long): Boolean = newestSubmission.get() == submission
}

/**
 * Shared across daemon managers so an uninterruptible old commit always precedes a newer commit.
 * The writer owns no scope: every job belongs to the submitting manager and is cancelled on close.
 */
internal class Po0StatusWriter<T>(
    private val write: suspend (T) -> Unit,
    private val reportFailure: (Exception) -> Unit,
) {
    private val commit = Mutex()

    fun submit(owner: CoroutineScope, token: Po0StatusWriteToken, value: T): Job? {
        val submission = token.nextSubmission() ?: return null
        return owner.launch {
            try {
                commit.withLock {
                    coroutineContext.ensureActive()
                    // Drop superseded queued work before any JSON or disk access.
                    if (token.isCurrent(submission)) write(value)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reportFailure(error)
            }
        }
    }
}
