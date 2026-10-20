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
    val name: String = "",
    val paused: Boolean = false
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
    val configured = entries.associateBy { po0TokenId(it.token) }
    return results.mapNotNull { result ->
        configured[result.tokenId]?.let { entry ->
            po0MachineUiState(result, entry.name).copy(paused = entry.paused)
        }
    }
}

/** A previous service snapshot can still contain removed credentials after a settings change. */
internal fun po0ConfiguredStatus(
    status: Po0RunStatus,
    entries: List<Po0TokenEntry>,
    manuallyCheckedIds: Set<String> = emptySet(),
): Po0RunStatus {
    if (entries.isEmpty()) return Po0RunStatus()
    val configured = entries.associateBy { po0TokenId(it.token) }
    val previous = status.results.associateBy(Po0TokenResult::tokenId)
    // Preserve result order, then append newly configured servers as explicitly unqueried.
    val ids = (status.results.map(Po0TokenResult::tokenId) + configured.keys).distinct().filter(configured::containsKey)
    val results = ids.map { id ->
        val entry = configured.getValue(id)
        val result = previous[id] ?: Po0TokenResult(id, Po0ResultState.UNCHECKED)
        when {
            entry.paused && id !in manuallyCheckedIds -> result.copy(state = Po0ResultState.PAUSED, added = false)
            !entry.paused && result.state == Po0ResultState.PAUSED -> Po0TokenResult(id, Po0ResultState.UNCHECKED)
            else -> result
        }
    }
    return status.copy(
        successCount = results.count { it.state == Po0ResultState.APPLIED },
        totalCount = results.size,
        error = results.any { it.state !in setOf(Po0ResultState.APPLIED, Po0ResultState.UNCHECKED, Po0ResultState.PAUSED) } ||
            (status.error && status.results.isEmpty()),
        results = results
    )
}

/** A single-server request updates its own result and retains the other servers' last results. */
internal fun po0MergeStatus(
    previous: Po0RunStatus,
    incoming: Po0RunStatus,
    requestedIds: Set<String>,
    entries: List<Po0TokenEntry>,
    manuallyCheckedIds: Set<String>,
): Po0RunStatus {
    val replacements = incoming.results.associateBy(Po0TokenResult::tokenId)
    val oldIds = previous.results.map(Po0TokenResult::tokenId).toSet()
    if (entries.all { po0TokenId(it.token) in requestedIds }) {
        val results = incoming.results + (requestedIds - replacements.keys).map { Po0TokenResult(it, Po0ResultState.ERROR) }
        return po0ConfiguredStatus(incoming.copy(results = results), entries, manuallyCheckedIds)
    }
    val results = previous.results.map { result ->
        if (result.tokenId !in requestedIds) result
        else replacements[result.tokenId] ?: Po0TokenResult(result.tokenId, Po0ResultState.ERROR)
    } + incoming.results.filter { it.tokenId !in oldIds } +
        (requestedIds - oldIds - replacements.keys).map { Po0TokenResult(it, Po0ResultState.ERROR) }
    return po0ConfiguredStatus(incoming.copy(results = results), entries, manuallyCheckedIds)
}
