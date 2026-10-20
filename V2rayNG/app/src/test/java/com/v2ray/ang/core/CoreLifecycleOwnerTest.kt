package com.v2ray.ang.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoreLifecycleOwnerTest {
    @Test
    fun duplicateStartCommandsOwnOnlyOneSetupForEachMode() = runBlocking {
        for (mode in listOf("vpn", "proxy", "root")) {
            val owner = CoreLifecycleOwner(coroutineContext)
            val entered = CompletableDeferred<Unit>()
            val ready = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val operations = mutableListOf<String>()
            assertTrue(owner.start {
                entered.complete(Unit)
                release.await()
                operations += "$mode-core"
                operations += "$mode-resources"
                ready.complete(Unit)
            })
            entered.await()
            assertFalse(owner.start { error("duplicate in-flight setup") })
            release.complete(Unit)
            ready.await()
            assertFalse(owner.start { error("duplicate completed setup") })
            owner.stop { operations += "$mode-stop" }
            owner.awaitStopped()
            assertEquals(listOf("$mode-core", "$mode-resources", "$mode-stop"), operations)
        }
    }

    @Test
    fun stoppingDuringPreflightCancelsItBeforeStartingNative() = runBlocking {
        val owner = CoreLifecycleOwner(coroutineContext)
        val entered = CompletableDeferred<Unit>()
        val preflight = CompletableDeferred<Unit>()
        val operations = mutableListOf<String>()
        owner.start {
            operations += "attach"
            entered.complete(Unit)
            try {
                preflight.await()
                operations += "native-start"
            } finally {
                operations += "preflight-ended"
            }
        }
        entered.await()
        val cleanup = owner.stop { operations += "detach" }
        assertTrue(owner.isStopRequested)
        assertSame(cleanup, owner.stop { error("duplicate cleanup") })
        preflight.complete(Unit)
        owner.awaitStopped()
        assertEquals(listOf("attach", "preflight-ended", "detach"), operations)
        assertFalse(owner.start { error("stopped owner must not restart") })
    }

    @Test
    fun cleanupWaitsForCancelledBlockingSetupThenRemovesRoutingBeforeNative() = runBlocking {
        val owner = CoreLifecycleOwner(coroutineContext)
        val acquired = CompletableDeferred<Unit>()
        val blockingCallFinishes = CompletableDeferred<Unit>()
        val operations = mutableListOf<String>()
        owner.start {
            operations += "native-started"
            // Models a blocking su/native call which cancellation cannot interrupt safely.
            withContext(NonCancellable) {
                acquired.complete(Unit)
                blockingCallFinishes.await()
                operations += "route-installed"
            }
            currentCoroutineContext().ensureActive()
            operations += "report-ready"
        }
        acquired.await()
        val cleanup = owner.stop {
            operations += "remove-routing"
            operations += "native-stopped"
            operations += "close-descriptor"
            operations += "detach"
        }
        assertFalse(cleanup.isCompleted)
        assertFalse(owner.start { error("stopping owner cannot acquire more resources") })
        blockingCallFinishes.complete(Unit)
        owner.awaitStopped()
        assertEquals(
            listOf("native-started", "route-installed", "remove-routing", "native-stopped", "close-descriptor", "detach"),
            operations,
        )
    }

    @Test
    fun aFailedSetupCanRequestItsOwnCleanupWithoutJoiningItself() = runBlocking {
        val owner = CoreLifecycleOwner(coroutineContext)
        val failed = CompletableDeferred<Unit>()
        val resources = mutableListOf<String>()
        owner.start {
            resources += "descriptor"
            try {
                error("native start failed")
            } catch (_: IllegalStateException) {
                owner.stop { resources.clear() }
                failed.complete(Unit)
            }
        }
        failed.await()
        owner.awaitStopped()
        assertTrue(resources.isEmpty())
    }

    @Test
    fun stopBeforeTheQueuedSetupStartsPreventsAllAcquisition() = runBlocking {
        val owner = CoreLifecycleOwner(coroutineContext)
        var setupRan = false
        owner.start { setupRan = true }
        owner.stop {}
        owner.awaitStopped()
        assertFalse(setupRan)
        assertFalse(owner.start { error("cannot revive a completed stop") })
    }

    @Test
    fun explicitStopCancelsRestartWhilePreflightIsStillEnding() = runBlocking {
        val owner = CoreLifecycleOwner(coroutineContext)
        val entered = CompletableDeferred<Unit>()
        val ending = CompletableDeferred<Unit>()
        var restarted = false
        owner.start {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { ending.await() }
            }
        }
        entered.await()
        val restart = launch(start = CoroutineStart.UNDISPATCHED) {
            owner.awaitStopped()
            restarted = true
        }
        owner.stop {}
        restart.cancel()
        ending.complete(Unit)
        owner.awaitStopped()
        restart.join()
        assertFalse(restarted)
    }

    @Test
    fun repeatedStopSharesOneCleanup() = runBlocking {
        val owner = CoreLifecycleOwner(coroutineContext)
        val release = CompletableDeferred<Unit>()
        var cleanups = 0
        val first = owner.stop { cleanups++; release.await() }
        val second = owner.stop { error("duplicate cleanup") }
        assertSame(first, second)
        assertTrue(owner.isStopping)
        release.complete(Unit)
        owner.awaitStopped()
        assertEquals(1, cleanups)
        assertFalse(owner.isStopping)
    }

    @Test
    fun aNewFailedStartOwnsItsCleanupAfterThePreviousStop() = runBlocking {
        val previous = CoreLifecycleOwner(coroutineContext)
        previous.stop {}
        previous.awaitStopped()
        val nextAttempt = CoreLifecycleOwner(coroutineContext)
        val resources = mutableListOf("receiver", "partial-native-start")
        nextAttempt.stop { resources.clear() }
        nextAttempt.awaitStopped()
        assertTrue(resources.isEmpty())
    }

    @Test
    fun restartWaitsForRoutingAndNativeCleanupInsteadOfAFixedDelay() = runBlocking {
        val owner = CoreLifecycleOwner(coroutineContext)
        val nativeStop = CompletableDeferred<Unit>()
        val operations = mutableListOf<String>()
        val restart = async(start = CoroutineStart.UNDISPATCHED) {
            owner.awaitStopped()
            operations += "restart"
        }
        assertFalse(restart.isCompleted)
        // Root routing cleanup occurs before the manager is asked to stop its native listener.
        operations += "remove-routing"
        owner.stop { nativeStop.await(); operations += "native-stopped" }
        assertFalse(restart.isCompleted)
        nativeStop.complete(Unit)
        restart.await()
        assertEquals(listOf("remove-routing", "native-stopped", "restart"), operations)
    }

    @Test
    fun anExplicitStopCanCancelAPendingRestartWhileCleanupFinishes() = runBlocking {
        val owner = CoreLifecycleOwner(coroutineContext)
        val release = CompletableDeferred<Unit>()
        var restarted = false
        val restart = launch(start = CoroutineStart.UNDISPATCHED) {
            owner.awaitStopped()
            restarted = true
        }
        owner.stop { release.await() }
        restart.cancel()
        release.complete(Unit)
        owner.awaitStopped()
        restart.join()
        assertFalse(restarted)
    }
}
