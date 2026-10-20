package com.v2ray.ang.service

import com.v2ray.ang.dto.Po0ResultState
import com.v2ray.ang.dto.Po0TokenResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Po0CheckScheduleTest {
    private val delay: (Int) -> Long = { po0PollDelayMillis(600, it) }

    @Test fun failingServerDoesNotDelayHealthyServer() {
        val plan = Po0CheckSchedule()
        plan.record("ok", Po0TokenResult("ok", Po0ResultState.APPLIED), 100)
        plan.record("bad", Po0TokenResult("bad", Po0ResultState.ERROR), 100)
        assertEquals(600_100L, plan.dueAt("ok", delay))
        assertEquals(1_200_100L, plan.dueAt("bad", delay))
        assertTrue(plan.shouldCheck("ok", 600_100, false, delay))
        assertFalse(plan.shouldCheck("bad", 600_100, false, delay))
    }

    @Test fun eventsReuseRecentSuccessAndNeverResetFailureBackoff() {
        val plan = Po0CheckSchedule()
        assertTrue(plan.shouldCheck("new", 100, true, delay))
        plan.record("a", Po0TokenResult("a", Po0ResultState.APPLIED), 100)
        assertFalse(plan.shouldCheck("a", 20_099, true, delay))
        assertTrue(plan.shouldCheck("a", 20_100, true, delay))
        plan.record("a", Po0TokenResult("a", Po0ResultState.ERROR), 20_100)
        assertFalse(plan.shouldCheck("a", 40_100, true, delay))
        assertTrue(plan.shouldCheck("a", 1_220_100, true, delay))
    }

    @Test fun newNetworkUsesNewScheduleWithoutRecentSuccess() {
        val old = Po0CheckSchedule()
        old.record("a", Po0TokenResult("a", Po0ResultState.APPLIED), 100)
        assertFalse(old.shouldCheck("a", 101, true, delay))
        assertTrue(Po0CheckSchedule().shouldCheck("a", 101, true, delay))
    }

    @Test fun networkChangesKeepConfigurationBackoffButRetryTransportFailures() {
        val previous = Po0CheckSchedule()
        previous.record("rejected", Po0TokenResult("rejected", Po0ResultState.REJECTED), 100)
        previous.record("disabled", Po0TokenResult("disabled", Po0ResultState.DISABLED), 100)
        previous.record("slot", Po0TokenResult("slot", Po0ResultState.CONFLICT, slotConflict = true), 100)
        previous.record("transport", Po0TokenResult("transport", Po0ResultState.ERROR), 100)
        previous.record("local", Po0TokenResult("local", Po0ResultState.CONFLICT, localConflict = true), 100)
        previous.record("ok", Po0TokenResult("ok", Po0ResultState.APPLIED), 100)
        val current = Po0CheckSchedule().apply {
            inheritConfigurationFailures(previous, setOf("rejected", "disabled", "slot", "transport", "local", "ok"))
        }
        for (id in listOf("rejected", "disabled", "slot")) {
            assertFalse(current.shouldCheck(id, 1_000, true, delay))
            assertEquals(1_800_100L, current.dueAt(id, delay))
            assertNotNull(current.configurationFailure(id))
        }
        for (id in listOf("transport", "local", "ok")) assertTrue(current.shouldCheck(id, 1_000, true, delay))
        assertTrue(current.shouldCheck("rejected", 1_800_100, false, delay))
    }

    @Test fun credentialCorrectionAndExplicitRecoveryDoNotInheritOldConfigurationFailure() {
        val previous = Po0CheckSchedule()
        previous.record("old", Po0TokenResult("old", Po0ResultState.REJECTED), 100)
        previous.retain(setOf("new"))
        val current = Po0CheckSchedule().apply { inheritConfigurationFailures(previous, setOf("old", "new")) }
        assertTrue(current.shouldCheck("old", 101, true, delay))
        assertTrue(current.shouldCheck("new", 101, true, delay))
        previous.record("new", Po0TokenResult("new", Po0ResultState.DISABLED), 100)
        previous.record("new", Po0TokenResult("new", Po0ResultState.APPLIED), 200)
        current.inheritConfigurationFailures(previous, setOf("new"))
        assertNull(current.configurationFailure("new"))
        assertTrue(current.shouldCheck("new", 201, true, delay))
    }

    @Test fun retryAfterAndPermanentConfigurationFailuresHaveIndependentDeadlines() {
        val plan = Po0CheckSchedule()
        plan.record("a", Po0TokenResult("a", Po0ResultState.ERROR, retryAfterMillis = 7_200_000), 100)
        plan.record("b", Po0TokenResult("b", Po0ResultState.CONFLICT), 100)
        assertEquals(7_200_100L, plan.dueAt("a", delay))
        assertEquals(1_800_100L, plan.dueAt("b", delay))
        assertFalse(plan.shouldCheck("a", 2_000_000, true, delay))
        assertEquals(Long.MAX_VALUE, po0Deadline(100, Long.MAX_VALUE))
    }

    @Test fun powerPolicyRecalculatesDeadlineWithoutNewAttempt() {
        val plan = Po0CheckSchedule()
        plan.record("a", Po0TokenResult("a", Po0ResultState.APPLIED), 100)
        assertEquals(900_100L, plan.dueAt("a") { po0PollDelayMillis(600, it, interactive = false) })
        assertEquals(600_100L, plan.dueAt("a", delay))
    }

    @Test fun readOnlyDiscoveryOfMissingExitInvalidatesRecentSuccessWithoutAddingFailureBackoff() {
        val plan = Po0CheckSchedule()
        plan.record("a", Po0TokenResult("a", Po0ResultState.APPLIED), 100)
        assertFalse(plan.shouldCheck("a", 101, true, delay))
        plan.invalidate("a")
        assertTrue(plan.shouldCheck("a", 101, false, delay))
        assertEquals(0L, plan.dueAt("a", delay))
    }

    @Test fun addressOrDefaultRouteChangesOnlyInvalidateOnSemanticDifference() {
        val first = po0LinkFingerprint("wlan0", listOf("10.0.0.2/24", "fe80::1/64"), listOf("default via 10.0.0.1"))
        assertEquals(first, po0LinkFingerprint("wlan0", listOf("fe80::1/64", "10.0.0.2/24", "10.0.0.2/24"), listOf("default via 10.0.0.1")))
        assertNotEquals(first, po0LinkFingerprint("wlan0", listOf("10.0.0.3/24"), listOf("default via 10.0.0.1")))
        assertNotEquals(first, po0LinkFingerprint("wlan0", listOf("10.0.0.2/24", "fe80::1/64"), listOf("default via 10.0.0.254")))
    }
}
