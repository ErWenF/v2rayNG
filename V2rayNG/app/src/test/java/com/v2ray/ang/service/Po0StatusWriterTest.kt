package com.v2ray.ang.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class Po0StatusWriterTest {
    @Test
    fun aQueuedOldSessionIsDiscardedBeforeWriting() {
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val commits = mutableListOf<String>()
            val writer = Po0StatusWriter<String>(write = { value ->
                if (value == "already-writing") {
                    entered.complete(Unit)
                    release.await()
                }
                commits += value
            }, reportFailure = { throw it })
            val blocker = writer.submit(this, Po0StatusWriteToken(), "already-writing")!!
            entered.await()
            val oldSession = Po0StatusWriteToken()
            val old = writer.submit(this, oldSession, "stale-session")!!
            oldSession.invalidate()
            val current = writer.submit(this, Po0StatusWriteToken(), "current-session")!!
            release.complete(Unit)
            blocker.join()
            old.join()
            current.join()
            assertEquals(listOf("already-writing", "current-session"), commits)
        }
    }

    @Test
    fun anOlderQueuedSnapshotCannotReplaceANewerSnapshotOfTheSameSession() {
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val commits = mutableListOf<String>()
            val writer = Po0StatusWriter<String>(write = { value ->
                if (value == "already-writing") {
                    entered.complete(Unit)
                    release.await()
                }
                commits += value
            }, reportFailure = { throw it })
            val token = Po0StatusWriteToken()
            val blocker = writer.submit(this, token, "already-writing")!!
            entered.await()
            val old = writer.submit(this, token, "old-result")!!
            val current = writer.submit(this, token, "new-result")!!
            release.complete(Unit)
            blocker.join()
            old.join()
            current.join()
            assertEquals(listOf("already-writing", "new-result"), commits)
        }
    }

    @Test
    fun closingAnOldManagerDuringUninterruptibleCommitStillLetsTheNewManagerWriteLast() {
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val commits = mutableListOf<String>()
            val writer = Po0StatusWriter<String>(write = { value ->
                if (value == "old-manager") {
                    // Models a synchronous MMKV commit that has already entered and cannot be cancelled.
                    withContext(NonCancellable) {
                        entered.complete(Unit)
                        release.await()
                        commits += value
                    }
                } else commits += value
            }, reportFailure = { throw it })
            val oldOwner = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
            val newOwner = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
            try {
                val oldToken = Po0StatusWriteToken()
                val old = writer.submit(oldOwner, oldToken, "old-manager")!!
                entered.await()
                oldToken.invalidate()
                oldOwner.cancel()
                val current = writer.submit(newOwner, Po0StatusWriteToken(), "new-manager")!!
                assertTrue(commits.isEmpty())
                // A late callback from the closed instance cannot supersede the new pending snapshot.
                assertNull(writer.submit(oldOwner, oldToken, "late-old-manager"))
                release.complete(Unit)
                old.join()
                current.join()
                assertEquals(listOf("old-manager", "new-manager"), commits)
            } finally {
                oldOwner.cancel()
                newOwner.cancel()
            }
        }
    }

    @Test
    fun aFailedCommitIsReportedAndDoesNotBlockTheNextSession() {
        runBlocking {
            val commits = mutableListOf<String>()
            val failures = mutableListOf<Exception>()
            val writer = Po0StatusWriter<String>(write = { value ->
                if (value == "failure") throw IOException("disk unavailable")
                commits += value
            }, reportFailure = { failures += it })
            writer.submit(this, Po0StatusWriteToken(), "failure")!!.join()
            writer.submit(this, Po0StatusWriteToken(), "recovered")!!.join()
            assertEquals(1, failures.size)
            assertEquals(listOf("recovered"), commits)
        }
    }
}
