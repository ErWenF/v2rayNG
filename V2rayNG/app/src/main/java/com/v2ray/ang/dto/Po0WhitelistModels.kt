package com.v2ray.ang.dto

data class Po0TokenEntry(val token: String, val name: String = "")

data class Po0Settings(
    val enabled: Boolean = false,
    val tokenEntries: List<Po0TokenEntry> = emptyList(),
    val intervalSeconds: Int = 600,
    val networkChangeEnabled: Boolean = true,
    val standaloneEnabled: Boolean = false,
) {
    val tokens: List<String> get() = tokenEntries.map(Po0TokenEntry::token)
}

enum class Po0ResultState { APPLIED, NOT_APPLIED, DISABLED, REJECTED, CONFLICT, ERROR }

data class Po0WhitelistEntry(val ip: String, val slot: Int? = null)

/** Safe to persist as UI status: neither raw credentials nor raw server responses are retained. */
data class Po0TokenResult(
    val tokenId: String,
    val state: Po0ResultState,
    val currentIp: String? = null,
    val whitelist: List<String> = emptyList(),
    val limit: Int? = null,
    val httpStatus: Int? = null,
    val added: Boolean = false,
    val entries: List<Po0WhitelistEntry> = emptyList(),
    val requestedSlot: Int? = null,
    val slotConflict: Boolean = false,
)

data class Po0RunStatus(
    val checkedAt: Long = 0,
    val successCount: Int = 0,
    val totalCount: Int = 0,
    val running: Boolean = false,
    val error: Boolean = false,
    val results: List<Po0TokenResult> = emptyList(),
)
