package com.v2ray.ang.ui.po0

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.dto.Po0ResultState
import com.v2ray.ang.dto.Po0MaintenanceState
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class Po0SettingsActivity : BaseComponentActivity() {
    private val viewModel: Po0SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.onAction(Po0Action.Load)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    viewModel.onAction(Po0Action.RefreshStatus)?.join()
                    delay(5_000)
                }
            }
        }
    }

    @Composable
    override fun ScreenContent() {
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        Po0SettingsScreen(state, { viewModel.onAction(it) }, ::finish)
    }
}

@Composable
internal fun Po0SettingsScreen(state: Po0UiState, onAction: (Po0Action) -> Unit, onBack: () -> Unit) {
    var showTokens by rememberSaveable { mutableStateOf(false) }
    BackHandler { onAction(Po0Action.RequestBack) }
    LaunchedEffect(state.shouldClose) { if (state.shouldClose) onBack() }
    if (state.showExitDialog) {
        AlertDialog(
            onDismissRequest = { onAction(Po0Action.ContinueEditing) },
            title = { Text(stringResource(R.string.po0_unsaved_changes)) },
            text = { Text(stringResource(R.string.po0_unsaved_exit_hint)) },
            confirmButton = {
                TextButton(onClick = { onAction(Po0Action.SaveAndExit) }, enabled = state.editable) {
                    Text(stringResource(R.string.acc_save))
                }
            },
            dismissButton = {
                FlowRow {
                    TextButton(onClick = { onAction(Po0Action.DiscardAndExit) }) {
                        Text(stringResource(R.string.po0_discard_changes))
                    }
                    TextButton(onClick = { onAction(Po0Action.ContinueEditing) }) {
                        Text(stringResource(R.string.po0_continue_editing))
                    }
                }
            }
        )
    }
    if (state.showResetDialog) {
        AlertDialog(
            onDismissRequest = { onAction(Po0Action.CancelReset) },
            title = { Text(stringResource(R.string.po0_reset_credentials)) },
            text = { Text(stringResource(R.string.po0_reset_credentials_hint)) },
            confirmButton = {
                TextButton(onClick = { onAction(Po0Action.ConfirmReset) }) {
                    Text(stringResource(R.string.po0_reset_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(Po0Action.CancelReset) }) {
                    Text(stringResource(R.string.po0_cancel))
                }
            }
        )
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.po0_title),
                onBackClick = { onAction(Po0Action.RequestBack) },
                isLoading = state.loading || state.saving || state.requestingCheck
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val settingsEditable = state.editable && state.tokenEditor == null
            Text(stringResource(R.string.po0_scope_hint), style = MaterialTheme.typography.bodyMedium)
            Po0Switch(
                title = stringResource(R.string.po0_enable),
                checked = state.enabled,
                enabled = settingsEditable,
                onCheckedChange = { onAction(Po0Action.SetEnabled(it)) }
            )
            Text(stringResource(R.string.po0_tokens), style = MaterialTheme.typography.titleMedium)
            if (state.tokenEntries.isEmpty()) {
                Text(stringResource(R.string.po0_tokens_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                state.tokenEntries.forEach { item ->
                    key(item.id) {
                        Po0TokenCard(
                            item = item,
                            showToken = showTokens,
                            enabled = settingsEditable,
                            onEdit = { onAction(Po0Action.BeginEditToken(item.id)) },
                            onRemove = { onAction(Po0Action.RemoveToken(item.id)) },
                            onPausedChange = { onAction(Po0Action.SetTokenPaused(item.id, it)) },
                            canOperate = state.canOperate,
                            onQuery = { onAction(Po0Action.QueryToken(item.id)) },
                            onRetry = { onAction(Po0Action.RetryToken(item.id)) }
                        )
                    }
                }
            }
            TextButton(onClick = { showTokens = !showTokens }, enabled = settingsEditable && state.tokenEntries.isNotEmpty()) {
                Text(stringResource(if (showTokens) R.string.po0_hide_tokens else R.string.po0_show_tokens))
            }
            Text(stringResource(R.string.po0_remove_token_hint), style = MaterialTheme.typography.bodySmall)
            if (Po0FormError.TOKENS_REQUIRED in state.formErrors || Po0FormError.INVALID_TOKENS in state.formErrors) {
                Text(
                    stringResource(
                        if (Po0FormError.TOKENS_REQUIRED in state.formErrors) R.string.po0_tokens_required
                        else R.string.po0_tokens_invalid
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (Po0FormError.CONFLICTING_TOKENS in state.formErrors || state.tokenEntries.map { it.token.substringBefore('@') }.distinct().size != state.tokenEntries.size) {
                Text(stringResource(R.string.po0_server_conflict), color = MaterialTheme.colorScheme.error)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onAction(Po0Action.BeginAddToken) },
                    enabled = settingsEditable,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.po0_add_token)) }
                Button(
                    onClick = { onAction(Po0Action.BeginImportTokens) },
                    enabled = settingsEditable,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.po0_import_tokens)) }
            }
            state.tokenEditor?.let { editor ->
                Po0TokenEditorCard(editor, state.editable, onAction)
            }
            Text(stringResource(R.string.po0_credentials_hint), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.po0_slots_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = state.intervalText,
                onValueChange = { onAction(Po0Action.SetInterval(it)) },
                label = { Text(stringResource(R.string.po0_interval)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = settingsEditable,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = Po0FormError.INVALID_INTERVAL in state.formErrors,
                supportingText = { Text(stringResource(R.string.po0_interval_hint)) }
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { onAction(Po0Action.SetInterval("5")) },
                    enabled = settingsEditable,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.po0_interval_fast)) }
                TextButton(
                    onClick = { onAction(Po0Action.SetInterval("600")) },
                    enabled = settingsEditable,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.po0_interval_ten_minutes)) }
                TextButton(
                    onClick = { onAction(Po0Action.SetInterval("900")) },
                    enabled = settingsEditable,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.po0_interval_fifteen_minutes)) }
            }
            Po0Switch(
                title = stringResource(R.string.po0_network_change),
                checked = state.networkChangeEnabled,
                enabled = settingsEditable,
                onCheckedChange = { onAction(Po0Action.SetNetworkChangeEnabled(it)) }
            )
            Po0Switch(
                title = stringResource(R.string.po0_standalone),
                checked = state.standaloneEnabled,
                enabled = settingsEditable,
                onCheckedChange = { onAction(Po0Action.SetStandaloneEnabled(it)) }
            )
            Text(stringResource(R.string.po0_standalone_hint), style = MaterialTheme.typography.bodySmall)
            if (state.dirty) Text(stringResource(R.string.po0_unsaved_changes), color = MaterialTheme.colorScheme.tertiary)
            Button(
                onClick = { onAction(Po0Action.Save) },
                enabled = settingsEditable,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.acc_save)) }
            if (!state.loading && !state.loaded) {
                Button(onClick = { onAction(Po0Action.Load) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.po0_retry))
                }
            }
            state.message?.let { message ->
                Text(
                    text = stringResource(message.stringId()),
                    color = when (message) {
                        Po0Message.LOAD_FAILED, Po0Message.SAVE_FAILED, Po0Message.CHECK_REQUEST_FAILED,
                        Po0Message.RESET_FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Text(stringResource(R.string.po0_check_hint), style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = { onAction(Po0Action.Query) },
                enabled = state.canCheckNow,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.po0_query)) }
            Button(
                onClick = { onAction(Po0Action.CheckNow) },
                enabled = state.canCheckNow,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.po0_check_now)) }
            Po0MaintenanceStatus(state, onAction)
            Po0Status(state)
            TextButton(
                onClick = { onAction(Po0Action.RequestReset) },
                enabled = !state.loading && !state.saving && !state.requestingCheck
            ) { Text(stringResource(R.string.po0_reset_credentials)) }
            NavigationBarsSpacer()
        }
    }
}

@Composable
private fun Po0TokenCard(
    item: Po0TokenItem,
    showToken: Boolean,
    enabled: Boolean,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onPausedChange: (Boolean) -> Unit,
    canOperate: Boolean,
    onQuery: () -> Unit,
    onRetry: () -> Unit
) {
    val displayName = item.name.ifBlank { stringResource(R.string.po0_machine, item.id.take(8)) }
    val editDescription = stringResource(R.string.po0_edit_token_accessibility, displayName)
    val deleteDescription = stringResource(R.string.po0_delete_token_accessibility, displayName)
    val queryDescription = stringResource(R.string.po0_query_token_accessibility, displayName)
    val retryDescription = stringResource(R.string.po0_retry_token_accessibility, displayName)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                if (item.slot == null) stringResource(R.string.po0_entry_fifo)
                else stringResource(R.string.po0_entry_fixed, item.slot)
            )
            Po0Switch(
                title = stringResource(R.string.po0_server_maintenance, displayName),
                checked = !item.paused,
                enabled = enabled,
                onCheckedChange = { onPausedChange(!it) }
            )
            Text(
                text = if (showToken) item.token else stringResource(R.string.po0_token_hidden),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onEdit,
                    enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = editDescription }
                ) { Text(stringResource(R.string.po0_edit_token)) }
                TextButton(
                    onClick = onRemove,
                    enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = deleteDescription }
                ) { Text(stringResource(R.string.po0_delete_token)) }
                TextButton(
                    onClick = onQuery,
                    enabled = canOperate,
                    modifier = Modifier.semantics { contentDescription = queryDescription }
                ) { Text(stringResource(R.string.po0_query)) }
                TextButton(
                    onClick = onRetry,
                    enabled = canOperate,
                    modifier = Modifier.semantics { contentDescription = retryDescription }
                ) { Text(stringResource(R.string.po0_check_now)) }
            }
        }
    }
}

@Composable
private fun Po0TokenEditorCard(editor: Po0TokenEditor, enabled: Boolean, onAction: (Po0Action) -> Unit) {
    var showEditorToken by rememberSaveable(editor.tokenId, editor.importing) { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(
                    when {
                        editor.importing -> R.string.po0_import_tokens
                        editor.tokenId == null -> R.string.po0_add_token
                        else -> R.string.po0_edit_token
                    }
                ),
                style = MaterialTheme.typography.titleMedium
            )
            if (!editor.importing) {
                OutlinedTextField(
                    value = editor.name,
                    onValueChange = { onAction(Po0Action.SetTokenName(it)) },
                    label = { Text(stringResource(R.string.po0_token_name)) },
                    placeholder = { Text(stringResource(R.string.po0_token_name_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    singleLine = true
                )
            }
            OutlinedTextField(
                value = editor.text,
                onValueChange = { onAction(Po0Action.SetTokenInput(it)) },
                label = { Text(stringResource(R.string.po0_tokens)) },
                supportingText = {
                    Text(stringResource(if (editor.importing) R.string.po0_import_tokens_hint else R.string.po0_token_input_hint))
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                minLines = if (editor.importing) 5 else 3,
                visualTransformation = if (showEditorToken) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                isError = editor.error != null,
                trailingIcon = {
                    TextButton(onClick = { showEditorToken = !showEditorToken }, enabled = enabled) {
                        Text(stringResource(if (showEditorToken) R.string.po0_hide_tokens else R.string.po0_show_tokens))
                    }
                }
            )
            editor.error?.let { error ->
                Text(
                    stringResource(
                        when (error) {
                            Po0TokenError.REQUIRED -> R.string.po0_token_required
                            Po0TokenError.INVALID -> R.string.po0_token_invalid
                            Po0TokenError.MULTIPLE -> R.string.po0_token_multiple
                            Po0TokenError.DUPLICATE -> R.string.po0_token_duplicate
                            Po0TokenError.SERVER_CONFLICT -> R.string.po0_server_conflict
                        }
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onAction(Po0Action.CancelTokenEdit) }, enabled = enabled) {
                    Text(stringResource(R.string.po0_cancel))
                }
                Button(onClick = { onAction(Po0Action.ConfirmToken) }, enabled = enabled) {
                    Text(
                        stringResource(
                            when {
                                editor.importing -> R.string.po0_import
                                editor.tokenId == null -> R.string.po0_add
                                else -> R.string.po0_complete
                            }
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun Po0Switch(title: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .semantics(mergeDescendants = true) {}
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
private fun Po0MaintenanceStatus(state: Po0UiState, onAction: (Po0Action) -> Unit) {
    val runtime = state.runtimeStatus
    val label = when {
        state.runtimeReadFailed -> R.string.po0_maintenance_unknown
        !state.runtimeLoaded -> R.string.po0_maintenance_loading
        runtime == null -> R.string.po0_maintenance_stopped
        else -> when (runtime.maintenance) {
            Po0MaintenanceState.STOPPED -> R.string.po0_maintenance_stopped
            Po0MaintenanceState.STARTING -> R.string.po0_maintenance_starting
            Po0MaintenanceState.WAITING_NETWORK -> R.string.po0_maintenance_waiting_network
            Po0MaintenanceState.CHECKING -> R.string.po0_maintenance_checking
            Po0MaintenanceState.SCHEDULED -> R.string.po0_maintenance_scheduled
            Po0MaintenanceState.RETRYING -> R.string.po0_maintenance_retrying
            Po0MaintenanceState.NEEDS_ATTENTION -> R.string.po0_maintenance_needs_attention
        }
    }
    Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
    if (state.runtimeLoaded && !state.runtimeReadFailed && runtime != null &&
        runtime.nextCheckAtElapsed > 0 && runtime.maintenance in setOf(Po0MaintenanceState.SCHEDULED, Po0MaintenanceState.RETRYING)) {
        val seconds = ((runtime.nextCheckAtElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 999) / 1000
        Text(stringResource(R.string.po0_next_check, seconds), style = MaterialTheme.typography.bodySmall)
    }
    if (state.enabled && !state.standaloneEnabled && state.runtimeLoaded && runtime == null && !state.runtimeReadFailed) {
        Text(stringResource(R.string.po0_maintenance_follows_proxy), style = MaterialTheme.typography.bodySmall)
    }
    if (state.canResume) {
        Button(onClick = { onAction(Po0Action.ResumeMaintenance) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.po0_resume_maintenance))
        }
    }
}

@Composable
private fun Po0Status(state: Po0UiState) {
    val checkedAt = state.status.checkedAt
    val formattedTime = remember(checkedAt) {
        if (checkedAt > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(checkedAt)) else null
    }
    Text(stringResource(R.string.po0_last_check, formattedTime ?: stringResource(R.string.po0_never_checked)))
    if (state.requestingCheck) {
        Text(stringResource(R.string.po0_checking))
    } else if (checkedAt > 0) {
        Text(stringResource(R.string.po0_result, state.status.successCount, state.status.totalCount))
    }
    if ((state.status.error && state.status.results.isEmpty()) || state.statusReadFailed) {
        Text(stringResource(R.string.po0_check_failed), color = MaterialTheme.colorScheme.error)
    }
    state.machines.forEach { machine ->
        key(machine.result.tokenId) { Po0MachineStatus(machine) }
    }
}

@Composable
private fun Po0MachineStatus(machine: Po0MachineUiState) {
    val result = machine.result
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                machine.name.ifBlank { stringResource(R.string.po0_machine, result.tokenId.take(8)) },
                style = MaterialTheme.typography.titleSmall
            )
            Text(stringResource(if (result.localConflict) R.string.po0_server_conflict else result.state.stringId()))
            if (machine.paused && result.state != Po0ResultState.PAUSED) {
                Text(stringResource(R.string.po0_state_paused), style = MaterialTheme.typography.bodySmall)
            }
            result.currentIp?.let { Text(stringResource(R.string.po0_current_ip, it)) }
            if (result.currentIp != null || result.limit != null || machine.rows.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.po0_whitelist_count,
                        machine.rows.size,
                        result.limit?.toString() ?: stringResource(R.string.value_unknown)
                    )
                )
            }
            if (machine.rows.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    machine.rows.forEach { row -> Po0WhitelistEntryChip(row) }
                }
            } else if (result.state !in setOf(Po0ResultState.UNCHECKED, Po0ResultState.PAUSED, Po0ResultState.ERROR, Po0ResultState.REJECTED)) {
                Text(stringResource(R.string.po0_whitelist_empty))
            }
        }
    }
}

@Composable
private fun Po0WhitelistEntryChip(row: Po0WhitelistRow) {
    val currentDescription = if (row.isCurrent) stringResource(R.string.po0_entry_current) else null
    Surface(
        modifier = Modifier.semantics(mergeDescendants = true) {
            currentDescription?.let { stateDescription = it }
        },
        shape = MaterialTheme.shapes.small,
        color = if (row.isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (row.isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val icon = when {
                row.isCurrent -> R.drawable.ic_my_location_24dp
                row.slot != null -> R.drawable.ic_push_pin_24dp
                else -> null
            }
            icon?.let { Icon(painterResource(it), contentDescription = null, modifier = Modifier.size(16.dp)) }
            Text(row.ip, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            Text(
                if (row.slot == null) stringResource(R.string.po0_entry_fifo)
                else stringResource(R.string.po0_entry_fixed, row.slot),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

private fun Po0ResultState.stringId(): Int = when (this) {
    Po0ResultState.APPLIED -> R.string.po0_state_applied
    Po0ResultState.NOT_APPLIED -> R.string.po0_state_not_applied
    Po0ResultState.DISABLED -> R.string.po0_state_disabled
    Po0ResultState.REJECTED -> R.string.po0_state_rejected
    Po0ResultState.CONFLICT -> R.string.po0_state_conflict
    Po0ResultState.ERROR -> R.string.po0_check_failed
    Po0ResultState.UNCHECKED -> R.string.po0_state_unchecked
    Po0ResultState.PAUSED -> R.string.po0_state_paused
}

private fun Po0Message.stringId(): Int = when (this) {
    Po0Message.LOAD_FAILED -> R.string.po0_load_failed
    Po0Message.SAVE_FAILED -> R.string.po0_save_failed
    Po0Message.SAVED -> R.string.po0_saved
    Po0Message.SAVED_SERVICE_UNAVAILABLE -> R.string.po0_saved_service_unavailable
    Po0Message.MANUAL_COMPLETED -> R.string.po0_manual_completed
    Po0Message.CHECK_REQUEST_FAILED -> R.string.po0_check_request_failed
    Po0Message.RESET_COMPLETED -> R.string.po0_reset_completed
    Po0Message.RESET_FAILED -> R.string.po0_reset_failed
    Po0Message.MAINTENANCE_RESUMED -> R.string.po0_resume_requested
}
