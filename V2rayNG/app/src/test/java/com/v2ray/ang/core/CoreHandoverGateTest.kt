package com.v2ray.ang.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoreHandoverGateTest {
    @Test
    fun successfulHandoverStopsBeforeStarting() = runBlocking {
        val gate = CoreHandoverGate()
        assertNull(gate.ticket())
        gate.activate()
        val operations = mutableListOf<String>()
        assertTrue(gate.reload(gate.ticket()!!, { true }, { operations += "stop" }) { checkCurrent ->
            checkCurrent()
            operations += "start"
        })
        assertEquals(listOf("stop", "start"), operations)
    }

    @Test
    fun inactiveCoreAndAnOldSessionCannotEnterNativeReload() = runBlocking {
        val gate = CoreHandoverGate()
        gate.activate()
        val oldTicket = gate.ticket()!!
        assertFalse(gate.reload(oldTicket, { false }, { error("unexpected stop") }) { error("unexpected start") })
        gate.invalidate()
        gate.activate()
        assertFalse(gate.reload(oldTicket, { true }, { error("unexpected stop") }) { error("unexpected start") })
    }

    @Test
    fun stopDuringNativeStopPreventsRestartAndQueuesCleanup() = runBlocking {
        val gate = CoreHandoverGate()
        gate.activate()
        val stopped = CompletableDeferred<Unit>()
        val finishNativeStop = CompletableDeferred<Unit>()
        val operations = mutableListOf<String>()
        val reload = async(start = CoroutineStart.UNDISPATCHED) {
            gate.reload(gate.ticket()!!, { true }, {
                operations += "reload-stop"
                stopped.complete(Unit)
                finishNativeStop.await()
            }) { operations += "restart" }
        }
        stopped.await()
        gate.invalidate()
        gate.invalidate() // Repeated teardown keeps the old request invalid.
        val cleanup = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.teardown { operations += "cleanup" }
        }
        assertEquals(listOf("reload-stop"), operations)
        finishNativeStop.complete(Unit)
        assertFalse(reload.await())
        cleanup.join()
        assertEquals(listOf("reload-stop", "cleanup"), operations)
    }

    @Test
    fun stopDuringConfigurationPreventsNativeStart() = runBlocking {
        val gate = CoreHandoverGate()
        gate.activate()
        val building = CompletableDeferred<Unit>()
        val finishBuilding = CompletableDeferred<Unit>()
        var restarted = false
        val reload = async(start = CoroutineStart.UNDISPATCHED) {
            try {
                gate.reload(gate.ticket()!!, { true }, {}) { checkCurrent ->
                    building.complete(Unit)
                    finishBuilding.await()
                    checkCurrent()
                    restarted = true
                }
            } catch (_: CancellationException) {
                false
            }
        }
        building.await()
        gate.invalidate()
        finishBuilding.complete(Unit)
        assertFalse(reload.await())
        assertFalse(restarted)
    }

    @Test
    fun teardownRunsAfterAnAlreadyEnteredNativeStart() = runBlocking {
        val gate = CoreHandoverGate()
        gate.activate()
        val starting = CompletableDeferred<Unit>()
        val finishStarting = CompletableDeferred<Unit>()
        val operations = mutableListOf<String>()
        val reload = async(start = CoroutineStart.UNDISPATCHED) {
            gate.reload(gate.ticket()!!, { true }, {}) {
                operations += "native-start"
                starting.complete(Unit)
                finishStarting.await()
            }
        }
        starting.await()
        gate.invalidate()
        val cleanup = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.teardown { operations += "native-stop" }
        }
        finishStarting.complete(Unit)
        assertFalse(reload.await())
        cleanup.join()
        assertEquals(listOf("native-start", "native-stop"), operations)
    }

    @Test
    fun pendingHandoverIsRejectedAfterStop() = runBlocking {
        val gate = CoreHandoverGate()
        gate.activate()
        val ticket = gate.ticket()!!
        val releaseCleanup = CompletableDeferred<Unit>()
        val cleanup = launch(start = CoroutineStart.UNDISPATCHED) { gate.teardown { releaseCleanup.await() } }
        val reload = async(start = CoroutineStart.UNDISPATCHED) {
            gate.reload(ticket, { true }, { error("unexpected stop") }) { error("unexpected restart") }
        }
        gate.invalidate()
        releaseCleanup.complete(Unit)
        cleanup.join()
        assertFalse(reload.await())
    }
}
