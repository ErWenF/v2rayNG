package com.v2ray.ang.ui.po0

import com.v2ray.ang.dto.Po0ResultState
import com.v2ray.ang.dto.Po0TokenResult
import com.v2ray.ang.dto.Po0WhitelistEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Po0WhitelistPresentationTest {
    @Test
    fun emptyWhitelistHasNoRows() {
        val ui = po0MachineUiState(result())
        assertTrue(ui.rows.isEmpty())
    }

    @Test
    fun responseOrderAndFixedSlotsRemainIndependent() {
        val entries = listOf(
            Po0WhitelistEntry("198.51.100.0/24", 4),
            Po0WhitelistEntry("203.0.113.0/24"),
            Po0WhitelistEntry("192.0.2.0/24", 0),
            Po0WhitelistEntry("198.18.0.0/24"),
            Po0WhitelistEntry("198.19.0.0/24", 2)
        )
        val ui = po0MachineUiState(result(currentIp = "203.0.113.42", entries = entries))

        assertEquals(entries.map { it.ip }, ui.rows.map { it.ip })
        assertEquals(5, ui.rows.size)
        assertEquals(listOf(4, null, 0, null, 2), ui.rows.map { it.slot })
        assertEquals(listOf(false, true, false, false, false), ui.rows.map { it.isCurrent })
    }

    @Test
    fun allEntriesStayVisibleWhenCurrentNetworkIsAbsent() {
        val entries = listOf(Po0WhitelistEntry("198.51.100.0/24"), Po0WhitelistEntry("192.0.2.0/24", 2))
        val ui = po0MachineUiState(result(currentIp = "203.0.113.42", entries = entries))

        assertEquals(2, ui.rows.size)
        assertTrue(ui.rows.none { it.isCurrent })
    }

    @Test
    fun fallbackIpListPreservesEveryEntryAndOrder() {
        val source = result(currentIp = "192.0.2.12").copy(
            whitelist = listOf("203.0.113.0/24", "192.0.2.0/24", "198.51.100.0/24")
        )
        val ui = po0MachineUiState(source)

        assertEquals(source.whitelist, ui.rows.map { it.ip })
        assertEquals(listOf(false, true, false), ui.rows.map { it.isCurrent })
        assertTrue(ui.rows.all { it.slot == null })
    }

    @Test
    fun currentFixedRecordRetainsSlotAndCurrentMarker() {
        val ui = po0MachineUiState(
            result(currentIp = "192.0.2.42", entries = listOf(Po0WhitelistEntry("192.0.2.0/24", 4)))
        )
        assertEquals(4, ui.rows.single().slot)
        assertTrue(ui.rows.single().isCurrent)
    }

    @Test
    fun unavailableCurrentIpDoesNotMarkAnyRow() {
        val ui = po0MachineUiState(result(entries = listOf(Po0WhitelistEntry("192.0.2.0/24"))))
        assertFalse(ui.rows.single().isCurrent)
    }

    @Test
    fun matchingRowsKeepApiOrder() {
        val ui = po0MachineUiState(
            result(
                currentIp = "192.0.2.42",
                entries = listOf(
                    Po0WhitelistEntry("192.0.2.0/24", 3),
                    Po0WhitelistEntry("198.51.100.0/24"),
                    Po0WhitelistEntry("192.0.2.42")
                )
            )
        )
        assertEquals(listOf("192.0.2.0/24", "198.51.100.0/24", "192.0.2.42"), ui.rows.map { it.ip })
        assertEquals(listOf(true, false, true), ui.rows.map { it.isCurrent })
    }

    private fun result(currentIp: String? = null, entries: List<Po0WhitelistEntry> = emptyList()) = Po0TokenResult(
        tokenId = "safe-id",
        state = Po0ResultState.APPLIED,
        currentIp = currentIp,
        whitelist = entries.map { it.ip },
        entries = entries
    )
}
