package com.v2ray.ang.handler

import com.v2ray.ang.dto.Po0ResultState
import com.v2ray.ang.dto.Po0TokenResult
import com.v2ray.ang.util.po0ServerId
import com.v2ray.ang.util.po0TokenId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class Po0RequestCoordinatorTest {
    @TempDir
    lateinit var temporary: Path

    private val token = "pgnfw_private-server"
    private val clock = AtomicLong(1_000L)
    private val boot = AtomicInteger(42)
    private val directory: File get() = temporary.resolve("coordination").toFile()

    private fun coordinator(timeout: Long = 90_000L, readBoot: () -> Int? = boot::get) = Po0RequestCoordinator(
        directory, clock::get, readBoot, timeout,
    )

    private fun applied(value: String = token) = Po0TokenResult(po0TokenId(value), Po0ResultState.APPLIED)

    @Test
    fun twoInstancesSerializeTheWholeTransactionIncludingDifferentSlotsOfOneServer() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val first = async {
            coordinator().run("$token@0") {
                firstEntered.complete(Unit)
                releaseFirst.await()
                applied("$token@0")
            }
        }
        firstEntered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator().run("$token@4") {
                secondEntered.complete(Unit)
                applied("$token@4")
            }
        }
        assertNull(withTimeoutOrNull(100L) { secondEntered.await() })
        releaseFirst.complete(Unit)
        first.await()
        assertEquals(Po0ResultState.APPLIED, withTimeout(2_000L) { second.await() }.state)
        assertTrue(secondEntered.isCompleted)
    }

    @Test
    fun unrelatedServersCanProceedWhileAnotherServerIsLocked() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val first = async {
            coordinator().run(token) {
                firstEntered.complete(Unit)
                releaseFirst.await()
                applied()
            }
        }
        firstEntered.await()
        assertEquals(Po0ResultState.APPLIED, withTimeout(2_000L) {
            coordinator().run("pgnfw_other") { applied("pgnfw_other") }
        }.state)
        assertFalse(first.isCompleted)
        releaseFirst.complete(Unit)
        assertEquals(Po0ResultState.APPLIED, first.await().state)
    }

    @Test
    fun cancellingAWaiterNeverRunsItsRequestAndDoesNotLeakTheLock() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val first = async {
            coordinator().run(token) {
                firstEntered.complete(Unit)
                releaseFirst.await()
                applied()
            }
        }
        firstEntered.await()
        var waiterRan = false
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator().run(token) { waiterRan = true; applied() }
        }
        waiter.cancelAndJoin()
        assertFalse(waiterRan)
        assertFalse(first.isCompleted)
        releaseFirst.complete(Unit)
        first.await()
        assertEquals(Po0ResultState.APPLIED, withTimeout(2_000L) { coordinator().run(token) { applied() } }.state)
    }

    @Test
    fun cancellingAnOwnerReleasesBothProcessAndFileLocks() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val first = async {
            coordinator().run(token) { entered.complete(Unit); awaitCancellation() }
        }
        entered.await()
        first.cancelAndJoin()
        assertEquals(Po0ResultState.APPLIED, withTimeout(2_000L) { coordinator().run(token) { applied() } }.state)
    }

    @Test
    fun failedRequestReleasesTheLockForAnotherCoordinatorInstance() {
        assertThrows(IOException::class.java) {
            runBlocking { coordinator().run(token) { throw IOException("failed request") } }
        }
        runBlocking {
            assertEquals(Po0ResultState.APPLIED, withTimeout(2_000L) { coordinator().run(token) { applied() } }.state)
        }
    }

    @Test
    fun cooldownIsSharedBetweenInstancesWithoutBeingExtendedByRepeatedReads() = runBlocking {
        val limited = Po0TokenResult(po0TokenId(token), Po0ResultState.ERROR, httpStatus = 429, retryAfterMillis = 10_000L)
        assertEquals(limited, coordinator().run(token) { limited })
        clock.set(2_000L)
        var called = false
        val second = coordinator().run("$token@1") { called = true; applied() }
        assertFalse(called)
        assertEquals(9_000L, second.retryAfterMillis)
        assertEquals(1, second.requestedSlot)
        clock.set(5_000L)
        assertEquals(6_000L, coordinator().run(token) { throw AssertionError("must honor cooldown") }.retryAfterMillis)
        clock.set(11_000L)
        assertEquals(Po0ResultState.APPLIED, coordinator().run(token) { applied() }.state)
    }

    @Test
    fun aNewBootDiscardsElapsedDeadlinesFromThePreviousBoot() = runBlocking {
        coordinator().run(token) { Po0TokenResult(po0TokenId(token), Po0ResultState.ERROR, retryAfterMillis = 100_000L) }
        boot.incrementAndGet()
        clock.set(50L)
        var called = false
        assertEquals(Po0ResultState.APPLIED, coordinator().run(token) { called = true; applied() }.state)
        assertTrue(called)
    }

    @Test
    fun anUnavailableBootIdentityDoesNotTrustOrPersistBootScopedDeadlines() = runBlocking {
        coordinator().run(token) { Po0TokenResult(po0TokenId(token), Po0ResultState.ERROR, retryAfterMillis = 100_000L) }
        var calls = 0
        val unknownBoot = coordinator(readBoot = { null })
        unknownBoot.run(token) { calls++; Po0TokenResult(po0TokenId(token), Po0ResultState.ERROR, retryAfterMillis = 100_000L) }
        unknownBoot.run(token) { calls++; applied() }
        assertEquals(2, calls)
        assertEquals(0L, File(directory, "${po0ServerId(token)}.lock").length())
    }

    @Test
    fun anUnreadableBootCountStillRunsSafelyWithoutPersistingElapsedTime() = runBlocking {
        val failures = mutableListOf<String>()
        val coordinated = Po0RequestCoordinator(directory, clock::get, { throw IllegalStateException("unavailable") },
            reportFailure = { id, _ -> failures.add(id) })
        val limited = Po0TokenResult(po0TokenId(token), Po0ResultState.ERROR, retryAfterMillis = 10_000L)
        assertEquals(limited, coordinated.run(token) { limited })
        assertEquals(listOf(po0ServerId(token)), failures)
        assertEquals(0L, File(directory, "${po0ServerId(token)}.lock").length())
    }

    @Test
    fun cooldownDeadlineAdditionSaturatesInsteadOfOverflowingOrShorteningTheWait() = runBlocking {
        coordinator().run(token) { Po0TokenResult(po0TokenId(token), Po0ResultState.ERROR, retryAfterMillis = Long.MAX_VALUE) }
        clock.set(2_000L)
        val result = coordinator().run(token) { throw AssertionError("must honor saturated cooldown") }
        assertEquals(Long.MAX_VALUE - 2_000L, result.retryAfterMillis)
    }

    @Test
    fun invalidMetadataIsResetBeforeTheRequestRuns() = runBlocking {
        directory.mkdirs()
        val file = File(directory, "${po0ServerId(token)}.lock")
        file.writeBytes(byteArrayOf(1, 2, 3))
        val failures = mutableListOf<String>()
        val coordinated = Po0RequestCoordinator(directory, clock::get, boot::get, reportFailure = { id, _ -> failures.add(id) })
        assertEquals(Po0ResultState.APPLIED, coordinated.run(token) { applied() }.state)
        assertEquals(listOf(po0ServerId(token)), failures)
        assertEquals(0L, file.length())
    }

    @Test
    fun externallyHeldLockTimesOutAsIOExceptionAndCanBeAcquiredAfterRelease() {
        directory.mkdirs()
        RandomAccessFile(File(directory, "${po0ServerId(token)}.lock"), "rw").use { storage ->
            storage.channel.lock().use { held ->
                val failure = runBlocking {
                    runCatching { coordinator(timeout = 30L).run(token) { throw AssertionError("request must not run") } }.exceptionOrNull()
                }
                assertInstanceOf(IOException::class.java, failure)
                assertTrue(held.isValid)
            }
        }
        runBlocking {
            assertEquals(Po0ResultState.APPLIED, withTimeout(2_000L) { coordinator().run(token) { applied() } }.state)
        }
    }

    @Test
    fun persistedMetadataContainsOnlyVersionBootAndDeadline() = runBlocking {
        coordinator().run(token) { Po0TokenResult(po0TokenId(token), Po0ResultState.ERROR, retryAfterMillis = 10_000L) }
        val children = directory.listFiles()
        assertNotNull(children)
        val files = checkNotNull(children).toList()
        assertEquals(listOf("${po0ServerId(token)}.lock"), files.map(File::getName))
        val file = files.single()
        assertEquals(16L, file.length())
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(token))
        RandomAccessFile(file, "r").use { storage ->
            assertEquals(1, storage.readInt())
            assertEquals(42, storage.readInt())
            assertEquals(11_000L, storage.readLong())
        }
    }
}
