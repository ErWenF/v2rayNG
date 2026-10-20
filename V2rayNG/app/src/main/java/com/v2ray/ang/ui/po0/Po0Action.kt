package com.v2ray.ang.ui.po0

internal sealed interface Po0Action {
    data object Load : Po0Action
    data class SetEnabled(val enabled: Boolean) : Po0Action
    data object BeginAddToken : Po0Action
    data object BeginImportTokens : Po0Action
    data class BeginEditToken(val tokenId: String) : Po0Action
    data class SetTokenName(val text: String) : Po0Action
    data class SetTokenInput(val text: String) : Po0Action
    data object ConfirmToken : Po0Action
    data object CancelTokenEdit : Po0Action
    data class RemoveToken(val tokenId: String) : Po0Action
    data class SetTokenPaused(val tokenId: String, val paused: Boolean) : Po0Action
    data class QueryToken(val tokenId: String) : Po0Action
    data class RetryToken(val tokenId: String) : Po0Action
    data class SetInterval(val text: String) : Po0Action
    data class SetNetworkChangeEnabled(val enabled: Boolean) : Po0Action
    data class SetStandaloneEnabled(val enabled: Boolean) : Po0Action
    data object Save : Po0Action
    data object Query : Po0Action
    data object CheckNow : Po0Action
    data object RefreshStatus : Po0Action
    data object ResumeMaintenance : Po0Action
    data object RequestReset : Po0Action
    data object CancelReset : Po0Action
    data object ConfirmReset : Po0Action
    data object RequestBack : Po0Action
    data object ContinueEditing : Po0Action
    data object DiscardAndExit : Po0Action
    data object SaveAndExit : Po0Action
}
