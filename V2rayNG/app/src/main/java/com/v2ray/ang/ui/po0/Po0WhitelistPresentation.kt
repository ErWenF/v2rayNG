package com.v2ray.ang.ui.po0

import com.v2ray.ang.dto.Po0TokenResult
import com.v2ray.ang.dto.Po0WhitelistEntry
import com.v2ray.ang.util.po0EntryMatchesCurrent

internal data class Po0WhitelistRow(
    val ip: String,
    val slot: Int?,
    val isCurrent: Boolean
)

internal data class Po0MachineUiState(
    val result: Po0TokenResult,
    val rows: List<Po0WhitelistRow>
)

/** Keep every API entry in response order and mark the current network without assigning a rank. */
internal fun po0MachineUiState(result: Po0TokenResult): Po0MachineUiState {
    val entries = result.entries.ifEmpty { result.whitelist.map { Po0WhitelistEntry(it) } }
    val rows = entries.map { entry ->
        Po0WhitelistRow(
            ip = entry.ip,
            slot = entry.slot,
            isCurrent = po0EntryMatchesCurrent(result.currentIp, entry.ip)
        )
    }
    return Po0MachineUiState(result, rows)
}
