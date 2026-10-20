package com.v2ray.ang.ui.po0

import android.os.Bundle
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
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.po0_title),
                onBackClick = onBack,
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
            Text(stringResource(R.string.po0_scope_hint), style = MaterialTheme.typography.bodyMedium)
            Po0Switch(
                title = stringResource(R.string.po0_enable),
                checked = state.enabled,
                enabled = state.editable,
                onCheckedChange = { onAction(Po0Action.SetEnabled(it)) }
            )
            OutlinedTextField(
                value = state.tokenText,
                onValueChange = { onAction(Po0Action.SetTokenText(it)) },
                label = { Text(stringResource(R.string.po0_tokens)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.editable,
                minLines = 3,
                visualTransformation = if (showTokens) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                isError = Po0FormError.TOKENS_REQUIRED in state.formErrors || Po0FormError.INVALID_TOKENS in state.formErrors,
                supportingText = {
                    Text(
                        stringResource(
                            when {
                                Po0FormError.TOKENS_REQUIRED in state.formErrors -> R.string.po0_tokens_required
                                Po0FormError.INVALID_TOKENS in state.formErrors -> R.string.po0_tokens_invalid
                                else -> R.string.po0_tokens_hint
                            }
                        )
                    )
                }
            )
            TextButton(onClick = { showTokens = !showTokens }, enabled = state.editable) {
                Text(stringResource(if (showTokens) R.string.po0_hide_tokens else R.string.po0_show_tokens))
            }
            Text(stringResource(R.string.po0_credentials_hint), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.po0_slots_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = state.intervalText,
                onValueChange = { onAction(Po0Action.SetInterval(it)) },
                label = { Text(stringResource(R.string.po0_interval)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.editable,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = Po0FormError.INVALID_INTERVAL in state.formErrors,
                supportingText = { Text(stringResource(R.string.po0_interval_hint)) }
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { onAction(Po0Action.SetInterval("5")) },
                    enabled = state.editable,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.po0_interval_fast)) }
                TextButton(
                    onClick = { onAction(Po0Action.SetInterval("600")) },
                    enabled = state.editable,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.po0_interval_ten_minutes)) }
                TextButton(
                    onClick = { onAction(Po0Action.SetInterval("900")) },
                    enabled = state.editable,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.po0_interval_fifteen_minutes)) }
            }
            Po0Switch(
                title = stringResource(R.string.po0_network_change),
                checked = state.networkChangeEnabled,
                enabled = state.editable,
                onCheckedChange = { onAction(Po0Action.SetNetworkChangeEnabled(it)) }
            )
            Po0Switch(
                title = stringResource(R.string.po0_standalone),
                checked = state.standaloneEnabled,
                enabled = state.editable,
                onCheckedChange = { onAction(Po0Action.SetStandaloneEnabled(it)) }
            )
            Text(stringResource(R.string.po0_standalone_hint), style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = { onAction(Po0Action.Save) },
                enabled = state.editable,
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
                        Po0Message.LOAD_FAILED, Po0Message.SAVE_FAILED, Po0Message.CHECK_REQUEST_FAILED -> MaterialTheme.colorScheme.error
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
            Po0Status(state)
            NavigationBarsSpacer()
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
private fun Po0Status(state: Po0UiState) {
    val checkedAt = state.status.checkedAt
    val formattedTime = remember(checkedAt) {
        if (checkedAt > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(checkedAt)) else null
    }
    Text(stringResource(R.string.po0_last_check, formattedTime ?: stringResource(R.string.po0_never_checked)))
    if (state.status.running || state.requestingCheck) {
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
            Text(stringResource(R.string.po0_machine, result.tokenId.take(8)), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(result.state.stringId()))
            result.currentIp?.let { Text(stringResource(R.string.po0_current_ip, it)) }
            Text(
                stringResource(
                    R.string.po0_whitelist_count,
                    machine.rows.size,
                    result.limit?.toString() ?: stringResource(R.string.value_unknown)
                )
            )
            if (machine.rows.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    machine.rows.forEach { row -> Po0WhitelistEntryChip(row) }
                }
            } else {
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
}

private fun Po0Message.stringId(): Int = when (this) {
    Po0Message.LOAD_FAILED -> R.string.po0_load_failed
    Po0Message.SAVE_FAILED -> R.string.po0_save_failed
    Po0Message.SAVED -> R.string.po0_saved
    Po0Message.SAVED_SERVICE_UNAVAILABLE -> R.string.po0_saved_service_unavailable
    Po0Message.MANUAL_COMPLETED -> R.string.po0_manual_completed
    Po0Message.CHECK_REQUEST_FAILED -> R.string.po0_check_request_failed
}
