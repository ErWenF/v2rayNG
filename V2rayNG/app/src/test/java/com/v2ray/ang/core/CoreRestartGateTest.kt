package com.v2ray.ang.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoreRestartGateTest {
    @Test
    fun externalAdmissionAllowsAnotherRestartWhileSupersededIoIsStillUnwinding() = runBlocking {
        val gate = CoreRestartGate()
        val oldTicket = gate.request()!!
        val entered = CompletableDeferred<Unit>()
        val unwinding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val oldPreparation = launch {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    unwinding.complete(Unit)
                    release.await()
                    gate.cancel(oldTicket)
                }
            }
        }
        entered.await()
        assertTrue(gate.accept(null))
        oldPreparation.cancel()
        unwinding.await()
        assertFalse(oldPreparation.isActive)
        assertFalse(oldPreparation.isCompleted)
        val nextTicket = gate.request()!!
        assertTrue(gate.canDispatch(nextTicket, previousDestroyed = true))
        release.complete(Unit)
        oldPreparation.join()
        assertTrue(gate.accept(nextTicket))
    }

    @Test
    fun replacementNeedsDestructionAndIsAcceptedOnceForEveryMode() {
        for (mode in listOf("vpn", "proxy", "root")) {
            val gate = CoreRestartGate()
            val ticket = gate.request()!!
            assertNull(gate.request(), "$mode duplicate restart")
            assertFalse(gate.canDispatch(ticket, previousDestroyed = false), mode)
            assertTrue(gate.canDispatch(ticket, previousDestroyed = true), mode)
            assertTrue(gate.accept(ticket), mode)
            assertFalse(gate.accept(ticket), "$mode replacement delivered twice")
            assertNotNull(gate.request(), "$mode next restart")
        }
    }

    @Test
    fun stopDuringValidationOrAfterDispatchRejectsQueuedReplacement() {
        for (mode in listOf("vpn", "proxy", "root")) {
            for (alreadyDispatched in listOf(false, true)) {
                val gate = CoreRestartGate()
                val ticket = gate.request()!!
                if (alreadyDispatched) assertTrue(gate.canDispatch(ticket, previousDestroyed = true))
                gate.invalidate()
                assertFalse(gate.canDispatch(ticket, previousDestroyed = true), mode)
                assertFalse(gate.accept(ticket), "$mode STOP must survive delayed Android delivery")
            }
        }
    }

    @Test
    fun timedOutDeliveryCannotReviveAfterTheReceiverIsReleased() {
        val gate = CoreRestartGate()
        val ticket = gate.request()!!
        gate.cancel(ticket)
        assertFalse(gate.accept(ticket))
        assertTrue(gate.accept(null))
    }

    @Test
    fun explicitOrSystemStartSupersedesAnOlderReplacement() {
        val gate = CoreRestartGate()
        val ticket = gate.request()!!
        assertTrue(gate.accept(null))
        assertFalse(gate.accept(ticket))
        assertFalse(gate.canDispatch(ticket, previousDestroyed = true))
    }

    @Test
    fun previousRestartFinallyCannotInvalidateANewerRequest() {
        val gate = CoreRestartGate()
        val previous = gate.request()!!
        gate.invalidate()
        val next = gate.request()!!
        gate.cancel(previous)
        assertTrue(gate.canDispatch(next, previousDestroyed = true))
        assertTrue(gate.accept(next))
    }
}
