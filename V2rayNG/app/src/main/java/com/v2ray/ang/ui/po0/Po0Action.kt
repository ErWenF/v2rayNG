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
    data class SetInterval(val text: String) : Po0Action
    data class SetNetworkChangeEnabled(val enabled: Boolean) : Po0Action
    data class SetStandaloneEnabled(val enabled: Boolean) : Po0Action
    data object Save : Po0Action
    data object Query : Po0Action
    data object CheckNow : Po0Action
    data object RefreshStatus : Po0Action
}
