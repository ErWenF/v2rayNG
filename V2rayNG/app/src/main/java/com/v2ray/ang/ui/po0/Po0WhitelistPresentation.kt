package com.v2ray.ang.ui.po0

import com.v2ray.ang.dto.Po0TokenResult
import com.v2ray.ang.dto.Po0TokenEntry
import com.v2ray.ang.dto.Po0RunStatus
import com.v2ray.ang.dto.Po0ResultState
import com.v2ray.ang.dto.Po0WhitelistEntry
import com.v2ray.ang.util.po0EntryMatchesCurrent
import com.v2ray.ang.util.po0TokenId

internal data class Po0WhitelistRow(
    val ip: String,
    val slot: Int?,
    val isCurrent: Boolean
)

internal data class Po0MachineUiState(
    val result: Po0TokenResult,
    val rows: List<Po0WhitelistRow>,
    val name: String = ""
)

/** Keep every API entry in response order and mark the current network without assigning a rank. */
internal fun po0MachineUiState(result: Po0TokenResult, name: String = ""): Po0MachineUiState {
    val entries = result.entries.ifEmpty { result.whitelist.map { Po0WhitelistEntry(it) } }
    val rows = entries.map { entry ->
        Po0WhitelistRow(
            ip = entry.ip,
            slot = entry.slot,
            isCurrent = po0EntryMatchesCurrent(result.currentIp, entry.ip)
        )
    }
    return Po0MachineUiState(result, rows, name)
}

/** Resolve names by credential identity, so renaming/removing an entry cannot relabel another result. */
internal fun po0MachineUiStates(results: List<Po0TokenResult>, entries: List<Po0TokenEntry>): List<Po0MachineUiState> {
    val names = entries.associate { po0TokenId(it.token) to it.name }
    return results.mapNotNull { result ->
        names[result.tokenId]?.let { name -> po0MachineUiState(result, name) }
    }
}

/** A previous service snapshot can still contain removed credentials after a settings change. */
internal fun po0ConfiguredStatus(status: Po0RunStatus, entries: List<Po0TokenEntry>): Po0RunStatus {
    if (status.results.isEmpty()) return status
    val ids = entries.map { po0TokenId(it.token) }.toSet()
    val results = status.results.filter { it.tokenId in ids }
    if (results.size == status.results.size) return status
    if (results.isEmpty()) return Po0RunStatus()
    return status.copy(
        successCount = results.count { it.state == Po0ResultState.APPLIED },
        totalCount = results.size,
        error = results.any { it.state != Po0ResultState.APPLIED },
        results = results
    )
}
