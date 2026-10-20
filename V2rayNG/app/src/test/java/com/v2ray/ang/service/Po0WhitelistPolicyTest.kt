package com.v2ray.ang.service

import com.v2ray.ang.dto.Po0ResultState
import com.v2ray.ang.dto.Po0Settings
import com.v2ray.ang.dto.Po0TokenEntry
import com.v2ray.ang.dto.Po0TokenResult
import com.v2ray.ang.handler.po0RunStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Po0WhitelistPolicyTest {
    @Test
    fun monitoringRequiresExplicitEnablementAndCredentials() {
        assertFalse(po0MonitoringEnabled(null))
        assertFalse(po0MonitoringEnabled(Po0Settings()))
        assertFalse(po0MonitoringEnabled(Po0Settings(tokenEntries = listOf(Po0TokenEntry("pgnfw_test", "Home")))))
        assertFalse(po0MonitoringEnabled(Po0Settings(enabled = true)))
        assertTrue(po0MonitoringEnabled(Po0Settings(enabled = true, tokenEntries = listOf(Po0TokenEntry("pgnfw_test", "Home")))))
    }

    @Test
    fun defaultOnlyUsesSixScheduledChecksPerHourWhileInteractive() {
        assertEquals(600_000L, po0PollDelayMillis(600, 0))
        assertEquals(5_000L, po0PollDelayMillis(5, 0))
    }

    @Test
    fun screenOffAndBatterySaverCannotRunASecondsPollingLoop() {
        assertEquals(900_000L, po0PollDelayMillis(5, 0, interactive = false))
        assertEquals(900_000L, po0PollDelayMillis(600, 0, batterySaver = true))
        assertEquals(3_600_000L, po0PollDelayMillis(3600, 0, interactive = false))
    }

    @Test
    fun failedApiBacksOffWithoutIncreasingWakeupFrequency() {
        assertEquals(1_200_000L, po0PollDelayMillis(600, 1))
        assertEquals(1_800_000L, po0PollDelayMillis(600, 50))
        assertEquals(3_600_000L, po0PollDelayMillis(3600, 50))
        assertEquals(600_000L, po0PollDelayMillis(600, 0))
        assertEquals(1_000L, po0PollDelayMillis(-1, 0))
    }

    @Test
    fun partialResultsAndSlotConflictsNeverReportAllMachinesWhitelisted() {
        val status = po0RunStatus(
            listOf(
                Po0TokenResult("machine-a", Po0ResultState.APPLIED),
                Po0TokenResult("machine-b", Po0ResultState.CONFLICT),
                Po0TokenResult("machine-c", Po0ResultState.ERROR)
            ), 1234
        )
        assertEquals(1234L, status.checkedAt)
        assertEquals(1, status.successCount)
        assertEquals(3, status.totalCount)
        assertTrue(status.error)
    }
}
