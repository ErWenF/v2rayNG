package com.v2ray.ang.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoreLifecycleOwnerTest {
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
