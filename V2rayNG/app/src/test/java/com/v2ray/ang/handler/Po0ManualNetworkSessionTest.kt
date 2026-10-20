package com.v2ray.ang.handler

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class Po0ManualNetworkSessionTest {
    private fun selectedWifi() = Po0ManualNetworkSession<String>().apply {
        onAvailable("wifi")
        onCapabilitiesChanged("wifi", true)
    }

    @Test
    fun onlyCapabilitiesOfTheLatestBestNetworkCanStartRequests() {
        runBlocking {
            val session = Po0ManualNetworkSession<String>()
            session.onAvailable("wifi")
            session.onAvailable("cellular")
            val result = async(start = CoroutineStart.UNDISPATCHED) { session.run { it } }
            session.onCapabilitiesChanged("wifi", true)
            assertFalse(result.isCompleted)
            session.onCapabilitiesChanged("cellular", true)
            assertEquals("cellular", result.await())
        }
    }

    @Test
    fun aHandoverBetweenSelectionAndDispatchNeverStartsTheOldBatch() {
        val session = selectedWifi()
        session.onAvailable("cellular")
        var calls = 0
        assertThrows(IOException::class.java) {
            runBlocking { session.run { calls++; "stale result" } }
        }
        assertEquals(0, calls)
    }

    @Test
    fun losingTheSelectedNetworkRequiresANewManualRequest() {
        val session = selectedWifi()
        session.onLost("wifi")
        assertThrows(IOException::class.java) {
            runBlocking { session.run { "stale result" } }
        }
    }

    @Test
    fun losingValidationInvalidatesTheWholeGenerationEvenIfItReturns() {
        val session = selectedWifi()
        session.onCapabilitiesChanged("wifi", false)
        session.onCapabilitiesChanged("wifi", true)
        assertThrows(IOException::class.java) {
            runBlocking { session.run { "stale result" } }
        }
    }

    @Test
    fun lateEventsForAnotherNetworkAndDuplicateCallbacksKeepTheCurrentBatch() {
        runBlocking {
            val session = selectedWifi()
            session.onLost("old-cellular")
            session.onCapabilitiesChanged("old-cellular", false)
            session.onAvailable("wifi")
            session.onCapabilitiesChanged("wifi", true)
            assertEquals("wifi", session.run { it })
        }
    }

    @Test
    fun inFlightHandoverCancelsHttpChildAndReturnsRetryableFailureWithoutCancellingItsOwner() {
        runBlocking {
            val session = selectedWifi()
            var requestClosed = false
            val error = runCatching {
                session.run {
                    try {
                        session.onAvailable("cellular")
                        awaitCancellation()
                    } finally {
                        requestClosed = true
                    }
                }
            }.exceptionOrNull()
            assertInstanceOf(IOException::class.java, error)
            assertTrue(error!!.message!!.contains("retry"))
            assertTrue(requestClosed)
            currentCoroutineContext().ensureActive()
        }
    }

    @Test
    fun ownerCancellationStillPropagatesToTheHttpChild() {
        runBlocking {
            val session = selectedWifi()
            val started = CompletableDeferred<Unit>()
            val task = async {
                session.run {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
            started.await()
            task.cancel(CancellationException("manual screen closed"))
            assertInstanceOf(CancellationException::class.java, runCatching { task.await() }.exceptionOrNull())
            assertTrue(task.isCancelled)
            currentCoroutineContext().ensureActive()
        }
    }
}
