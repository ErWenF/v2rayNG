package com.v2ray.ang.ui.po0

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.Po0RunStatus
import com.v2ray.ang.dto.Po0MaintenanceState
import com.v2ray.ang.dto.Po0Settings
import com.v2ray.ang.dto.Po0TokenEntry
import com.v2ray.ang.handler.Po0ManualClient
import com.v2ray.ang.handler.Po0ServiceControl
import com.v2ray.ang.handler.Po0SettingsStore
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.parsePo0Tokens
import com.v2ray.ang.util.po0TokenId
import com.v2ray.ang.util.po0ServerId
import com.v2ray.ang.util.validatePo0Tokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

internal enum class Po0FormError { TOKENS_REQUIRED, INVALID_TOKENS, INVALID_INTERVAL, CONFLICTING_TOKENS }

internal enum class Po0TokenError { REQUIRED, INVALID, MULTIPLE, DUPLICATE, SERVER_CONFLICT }

internal data class Po0TokenItem(val id: String, val token: String, val name: String, val slot: Int?, val paused: Boolean = false)

internal data class Po0TokenEditor(
    val tokenId: String? = null,
    val name: String = "",
    val text: String = "",
    val importing: Boolean = false,
    val error: Po0TokenError? = null
)

private fun po0TokenItem(entry: Po0TokenEntry) = Po0TokenItem(
    id = po0TokenId(entry.token),
    token = entry.token,
    name = entry.name,
    slot = entry.token.substringAfter('@', "").toIntOrNull(),
    paused = entry.paused
)

private data class Po0TokenChange(val entries: List<Po0TokenItem>, val error: Po0TokenError? = null)

private fun po0TokenChange(editor: Po0TokenEditor, entries: List<Po0TokenItem>): Po0TokenChange {
    val tokens = parsePo0Tokens(editor.text)
    val error = when {
        tokens.isEmpty() -> Po0TokenError.REQUIRED
        !validatePo0Tokens(editor.text) -> Po0TokenError.INVALID
        !editor.importing && tokens.size != 1 -> Po0TokenError.MULTIPLE
        editor.importing && tokens.all { token -> entries.any { it.token == token } } -> Po0TokenError.DUPLICATE
        !editor.importing && entries.any { it.token == tokens.single() && it.id != editor.tokenId } -> Po0TokenError.DUPLICATE
        editor.importing && (entries.map { it.token } + tokens).distinct().let { values ->
            values.map(::po0ServerId).distinct().size != values.size
        } -> Po0TokenError.SERVER_CONFLICT
        !editor.importing && entries.any {
            it.id != editor.tokenId && po0ServerId(it.token) == po0ServerId(tokens.single())
        } -> Po0TokenError.SERVER_CONFLICT
        else -> null
    }
    if (error != null) return Po0TokenChange(entries, error)
    if (editor.importing) {
        val added = tokens.filter { token -> entries.none { it.token == token } }
            .map { po0TokenItem(Po0TokenEntry(it)) }
        return Po0TokenChange(entries + added)
    }
    val item = po0TokenItem(Po0TokenEntry(
        tokens.single(), editor.name.trim(), entries.find { it.id == editor.tokenId }?.paused ?: false
    ))
    return Po0TokenChange(
        if (editor.tokenId == null) entries + item
        else entries.map { if (it.id == editor.tokenId) item else it }
    )
}

internal enum class Po0Message {
    LOAD_FAILED, SAVE_FAILED, SAVED, SAVED_SERVICE_UNAVAILABLE,
    MANUAL_COMPLETED, CHECK_REQUEST_FAILED, RESET_COMPLETED, RESET_FAILED, MAINTENANCE_RESUMED
}

internal data class Po0UiState(
    val loading: Boolean = true,
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val requestingCheck: Boolean = false,
    val enabled: Boolean = false,
    val tokenEntries: List<Po0TokenItem> = emptyList(),
    val tokenEditor: Po0TokenEditor? = null,
    val intervalText: String = "600",
    val networkChangeEnabled: Boolean = true,
    val standaloneEnabled: Boolean = false,
    val dirty: Boolean = false,
    val formErrors: Set<Po0FormError> = emptySet(),
    val message: Po0Message? = null,
    val status: Po0RunStatus = Po0RunStatus(),
    val machines: List<Po0MachineUiState> = emptyList(),
    val statusReadFailed: Boolean = false,
    val runtimeStatus: Po0RunStatus? = null,
    val runtimeLoaded: Boolean = false,
    val runtimeReadFailed: Boolean = false,
    val runtimeProbeSequence: Long = 0,
    val showExitDialog: Boolean = false,
    val showResetDialog: Boolean = false,
    val shouldClose: Boolean = false
) {
    val editable: Boolean get() = loaded && !loading && !saving && !requestingCheck
    val canOperate: Boolean get() = editable && !dirty && tokenEditor == null
    val canCheckNow: Boolean get() = canOperate && tokenEntries.any { !it.paused } &&
        tokenEntries.map { po0ServerId(it.token) }.distinct().size == tokenEntries.size
    val hasUnsavedChanges: Boolean get() = dirty || tokenEditor != null
    val canResume: Boolean get() = canOperate && enabled && standaloneEnabled && tokenEntries.any { !it.paused } &&
        runtimeLoaded && !runtimeReadFailed &&
        (runtimeStatus == null || runtimeStatus.maintenance in setOf(Po0MaintenanceState.STOPPED, Po0MaintenanceState.NEEDS_ATTENTION))
}

internal fun validatePo0Form(state: Po0UiState): Set<Po0FormError> = buildSet {
    val tokens = state.tokenEntries.map { it.token }
    if (state.enabled && tokens.isEmpty()) add(Po0FormError.TOKENS_REQUIRED)
    if (tokens.any { parsePo0Tokens(it) != listOf(it) || !validatePo0Tokens(it) }) add(Po0FormError.INVALID_TOKENS)
    if (tokens.map(::po0ServerId).distinct().size != tokens.size) add(Po0FormError.CONFLICTING_TOKENS)
    val interval = state.intervalText.trim().toIntOrNull()
    if (interval == null || interval !in 1..3600) add(Po0FormError.INVALID_INTERVAL)
}

class Po0SettingsViewModel internal constructor(
    application: Application,
    private val readSettings: () -> Po0Settings,
    private val writeSettings: (Po0Settings) -> Unit,
    private val readRunStatus: () -> Po0RunStatus,
    private val applyServices: (Po0Settings) -> Unit,
    private val notifySettingsChanged: () -> Unit,
    private val runManualCheck: suspend (Po0Settings, Boolean) -> Po0RunStatus,
    private val reportFailure: (String, Exception) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val probeRuntime: suspend () -> Po0RunStatus? = { null },
    private val resetSettings: () -> Unit = { Po0SettingsStore.reset() }
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application,
        Po0SettingsStore::read,
        Po0SettingsStore::save,
        Po0SettingsStore::readStatus,
        { settings -> Po0ServiceControl.apply(application, settings) },
        { Po0ServiceControl.notifySettingsChanged(application) },
        { settings, queryOnly -> Po0ManualClient.run(application, settings, queryOnly) },
        { operation, error ->
            // Storage/JSON exceptions may include credentials; retain only the exception type.
            val safeError = IOException("Po0 UI failure (${error.javaClass.simpleName})")
            LogUtil.e(AppConfig.TAG, "Po0 settings UI: $operation", safeError)
        },
        probeRuntime = { Po0ServiceControl.status(application) },
        resetSettings = {
            Po0SettingsStore.reset {
                // An absent configuration reads as disabled even when the old key cannot decrypt.
                // Tell the daemon to invalidate the old session before deleting that key.
                Po0ServiceControl.notifyCredentialsReset(application)
                Po0ServiceControl.apply(application, Po0Settings())
            }
        }
    )

    private val _uiState = MutableStateFlow(Po0UiState())
    internal val uiState = _uiState.asStateFlow()
    private var loadJob: Job? = null
    @Volatile
    private var savedSettings: Po0Settings? = null
    @Volatile
    private var manualBaselineRevision: String? = null
    private val statusGate = Any()
    private var statusEpoch: Long = 0
    private var manuallyCheckedIds: Set<String> = emptySet()

    internal fun onAction(action: Po0Action): Job? {
        when (action) {
            Po0Action.Load -> return load()
            Po0Action.Save -> return save()
            Po0Action.SaveAndExit -> return save(exitAfter = true, includeEditor = true)
            Po0Action.CheckNow -> return checkNow(queryOnly = false)
            Po0Action.Query -> return checkNow(queryOnly = true)
            is Po0Action.QueryToken -> return checkNow(queryOnly = true, tokenId = action.tokenId)
            is Po0Action.RetryToken -> return checkNow(queryOnly = false, tokenId = action.tokenId)
            Po0Action.RefreshStatus -> return refreshStatus()
            Po0Action.ConfirmToken -> return confirmToken()
            Po0Action.ResumeMaintenance -> return resumeMaintenance()
            Po0Action.ConfirmReset -> return reset()
            Po0Action.RequestReset -> {
                if (!_uiState.value.loading && !_uiState.value.saving && !_uiState.value.requestingCheck) {
                    _uiState.update { it.copy(showResetDialog = true) }
                }
            }
            Po0Action.CancelReset -> _uiState.update { it.copy(showResetDialog = false) }
            Po0Action.RequestBack -> _uiState.update {
                if (it.saving) it
                else if (it.hasUnsavedChanges) it.copy(showExitDialog = true)
                else it.copy(shouldClose = true)
            }
            Po0Action.ContinueEditing -> _uiState.update { it.copy(showExitDialog = false) }
            Po0Action.DiscardAndExit -> _uiState.update { it.copy(showExitDialog = false, shouldClose = true) }
            else -> {
                if (!_uiState.value.editable) return null
                _uiState.update { state ->
                    val editor = state.tokenEditor
                    when (action) {
                        Po0Action.CancelTokenEdit -> return@update state.copy(tokenEditor = null)
                        is Po0Action.SetTokenName -> return@update state.copy(
                            tokenEditor = editor?.copy(name = action.text, error = null)
                        )
                        is Po0Action.SetTokenInput -> return@update state.copy(
                            tokenEditor = editor?.copy(text = action.text, error = null)
                        )
                        else -> if (editor != null) return@update state
                    }
                    when (action) {
                        Po0Action.BeginAddToken -> return@update state.copy(tokenEditor = Po0TokenEditor())
                        Po0Action.BeginImportTokens -> return@update state.copy(tokenEditor = Po0TokenEditor(importing = true))
                        is Po0Action.BeginEditToken -> {
                            val entry = state.tokenEntries.find { it.id == action.tokenId } ?: return@update state
                            return@update state.copy(tokenEditor = Po0TokenEditor(entry.id, entry.name, entry.token))
                        }
                        is Po0Action.RemoveToken -> {
                            if (state.tokenEntries.none { it.id == action.tokenId }) return@update state
                            return@update state.copy(
                                tokenEntries = state.tokenEntries.filterNot { it.id == action.tokenId },
                                dirty = true, formErrors = emptySet(), message = null
                            )
                        }
                        is Po0Action.SetTokenPaused -> {
                            if (state.tokenEntries.none { it.id == action.tokenId }) return@update state
                            return@update state.copy(
                                tokenEntries = state.tokenEntries.map {
                                    if (it.id == action.tokenId) it.copy(paused = action.paused) else it
                                },
                                dirty = true, formErrors = emptySet(), message = null
                            )
                        }
                        else -> Unit
                    }
                    val edited = when (action) {
                        is Po0Action.SetEnabled -> state.copy(enabled = action.enabled)
                        is Po0Action.SetInterval -> state.copy(intervalText = action.text)
                        is Po0Action.SetNetworkChangeEnabled -> state.copy(networkChangeEnabled = action.enabled)
                        is Po0Action.SetStandaloneEnabled -> state.copy(standaloneEnabled = action.enabled)
                    }
                    edited.copy(dirty = true, formErrors = emptySet(), message = null)
                }
            }
        }
        return null
    }

    private fun load(): Job? {
        if (_uiState.value.loaded || loadJob?.isActive == true) return null
        _uiState.update { it.copy(loading = true, message = null) }
        return viewModelScope.launch(ioDispatcher) {
            try {
                val settings = readSettings()
                val entries = withContext(Dispatchers.Default) { settings.tokenEntries.map(::po0TokenItem) }
                savedSettings = settings
                val (status, machines) = withContext(Dispatchers.Default) {
                    val status = po0ConfiguredStatus(Po0RunStatus(), settings.tokenEntries)
                    status to po0MachineUiStates(status.results, settings.tokenEntries)
                }
                _uiState.update {
                    it.copy(
                        loading = false,
                        loaded = true,
                        enabled = settings.enabled,
                        tokenEntries = entries,
                        intervalText = settings.intervalSeconds.toString(),
                        networkChangeEnabled = settings.networkChangeEnabled,
                        standaloneEnabled = settings.standaloneEnabled,
                        status = status,
                        machines = machines
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                reportFailure("load", error)
                _uiState.update { it.copy(loading = false, message = Po0Message.LOAD_FAILED) }
            }
        }.also { loadJob = it }
    }

    private fun confirmToken(): Job? {
        val snapshot = _uiState.value
        val editor = snapshot.tokenEditor ?: return null
        if (!snapshot.editable) return null
        _uiState.update { it.copy(saving = true, message = null) }
        return viewModelScope.launch(ioDispatcher) {
            try {
                val change = withContext(Dispatchers.Default) { po0TokenChange(editor, snapshot.tokenEntries) }
                _uiState.update {
                    if (change.error != null) it.copy(saving = false, tokenEditor = editor.copy(error = change.error))
                    else it.copy(
                        saving = false, tokenEntries = change.entries, tokenEditor = null,
                        dirty = true, formErrors = emptySet()
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                reportFailure("edit token", error)
                _uiState.update { it.copy(saving = false, tokenEditor = editor.copy(error = Po0TokenError.INVALID)) }
            }
        }
    }

    private fun save(exitAfter: Boolean = false, includeEditor: Boolean = false): Job? {
        val initial = _uiState.value
        if (!initial.editable || initial.tokenEditor != null && !includeEditor) return null
        _uiState.update { it.copy(saving = true, message = null, showExitDialog = false) }
        return viewModelScope.launch(ioDispatcher) {
            try {
                val snapshot = if (initial.tokenEditor != null) {
                    val change = withContext(Dispatchers.Default) { po0TokenChange(initial.tokenEditor, initial.tokenEntries) }
                    if (change.error != null) {
                        _uiState.update {
                            it.copy(saving = false, tokenEditor = initial.tokenEditor.copy(error = change.error))
                        }
                        return@launch
                    }
                    initial.copy(tokenEntries = change.entries, tokenEditor = null, dirty = true).also { edited ->
                        _uiState.update { it.copy(tokenEntries = edited.tokenEntries, tokenEditor = null, dirty = true) }
                    }
                } else initial
                val errors = withContext(Dispatchers.Default) { validatePo0Form(snapshot) }
                if (errors.isNotEmpty()) {
                    _uiState.update { it.copy(saving = false, formErrors = errors) }
                    return@launch
                }
                val settings = withContext(Dispatchers.Default) {
                    Po0Settings(
                        enabled = snapshot.enabled,
                        tokenEntries = snapshot.tokenEntries.map { Po0TokenEntry(it.token, it.name, it.paused) },
                        intervalSeconds = snapshot.intervalText.trim().toInt(),
                        networkChangeEnabled = snapshot.networkChangeEnabled,
                        standaloneEnabled = snapshot.standaloneEnabled
                    )
                }
                writeSettings(settings)
                val (status, machines) = withContext(Dispatchers.Default) {
                    val status = po0ConfiguredStatus(_uiState.value.status, settings.tokenEntries)
                    status to po0MachineUiStates(status.results, settings.tokenEntries)
                }
                synchronized(statusGate) {
                    savedSettings = settings
                    statusEpoch++
                    manualBaselineRevision = null
                    manuallyCheckedIds = emptySet()
                    _uiState.update {
                        it.copy(
                            machines = machines,
                            status = status,
                            intervalText = settings.intervalSeconds.toString(),
                            dirty = false,
                            formErrors = emptySet(),
                            message = Po0Message.SAVED
                        )
                    }
                }
                var servicesUpdated = true
                try {
                    applyServices(settings)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    reportFailure("apply saved settings", error)
                    servicesUpdated = false
                    _uiState.update { it.copy(message = Po0Message.SAVED_SERVICE_UNAVAILABLE) }
                }
                try {
                    notifySettingsChanged()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    reportFailure("notify saved settings", error)
                    servicesUpdated = false
                    _uiState.update { it.copy(message = Po0Message.SAVED_SERVICE_UNAVAILABLE) }
                }
                _uiState.update { it.copy(saving = false, shouldClose = exitAfter && servicesUpdated) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                reportFailure("save", error)
                _uiState.update { it.copy(saving = false, message = Po0Message.SAVE_FAILED) }
            }
        }
    }

    private fun checkNow(queryOnly: Boolean, tokenId: String? = null): Job? {
        val snapshot = _uiState.value
        if (!snapshot.canOperate || tokenId == null && !snapshot.canCheckNow) return null
        val configured = savedSettings ?: return null
        val selected = if (tokenId == null) configured.tokenEntries.filterNot(Po0TokenEntry::paused)
            else listOfNotNull(configured.tokenEntries.find { po0TokenId(it.token) == tokenId }?.copy(paused = false))
        if (selected.isEmpty()) return null
        val settings = configured.copy(tokenEntries = selected)
        val requestedIds = selected.map { po0TokenId(it.token) }.toSet()
        synchronized(statusGate) {
            statusEpoch++
            _uiState.update { it.copy(requestingCheck = true, message = null) }
        }
        return viewModelScope.launch(ioDispatcher) {
            try {
                val startedSettings = readSettings()
                if (configured.copy(storageIdentity = startedSettings.storageIdentity) != startedSettings ||
                    configured.storageIdentity.isNotEmpty() && configured.storageIdentity != startedSettings.storageIdentity) {
                    throw IOException("Po0 configuration changed before the manual request")
                }
                val result = runManualCheck(settings, queryOnly)
                val incoming = if (result.checkedAt > 0) result else result.copy(checkedAt = System.currentTimeMillis())
                // Pin the daemon revision observed after this operation. Wall-clock adjustments
                // cannot make a later daemon update look older than a manual result.
                val baseline = try {
                    readRunStatus().takeIf {
                        it.configurationId.isEmpty() || it.configurationId == startedSettings.storageIdentity
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    reportFailure("read manual result revision", error)
                    null
                }
                if (readSettings() != startedSettings) throw IOException("Po0 configuration changed during the manual request")
                val baselineRevision = baseline?.revision ?: _uiState.value.status.revision
                val (explicitIds, retainedManualIds) = synchronized(statusGate) {
                    (manuallyCheckedIds + requestedIds) to
                        if (baseline != null && baseline.revision == manualBaselineRevision) manuallyCheckedIds else emptySet()
                }
                val (status, machines) = withContext(Dispatchers.Default) {
                    val previous = _uiState.value.status
                    // The pinned revision may already contain newer results for other servers.
                    // Keep prior manual overrides only while that daemon revision is unchanged.
                    val refreshed = if (baseline == null) previous else po0MergeStatus(
                        previous, baseline, baseline.results.map { it.tokenId }.toSet() - requestedIds - retainedManualIds,
                        configured.tokenEntries, explicitIds
                    )
                    val merged = po0MergeStatus(refreshed, incoming, requestedIds, configured.tokenEntries, explicitIds)
                    merged to po0MachineUiStates(merged.results, configured.tokenEntries)
                }
                synchronized(statusGate) {
                    statusEpoch++
                    manualBaselineRevision = baselineRevision
                    manuallyCheckedIds = explicitIds
                    _uiState.update {
                        it.copy(
                            requestingCheck = false,
                            status = status,
                            machines = machines,
                            statusReadFailed = false,
                            message = Po0Message.MANUAL_COMPLETED
                        )
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                reportFailure("request check", error)
                synchronized(statusGate) {
                    statusEpoch++
                    _uiState.update { it.copy(requestingCheck = false, message = Po0Message.CHECK_REQUEST_FAILED) }
                }
            } finally {
                // A cancelled manual child may leave this ViewModel alive. Release its form lock
                // on every exit; activity finish still cancels the owning request through its scope.
                synchronized(statusGate) {
                    if (_uiState.value.requestingCheck) {
                        statusEpoch++
                        _uiState.update { it.copy(requestingCheck = false) }
                    }
                }
            }
        }
    }

    private fun refreshStatus(): Job = viewModelScope.launch(ioDispatcher) {
        if (!_uiState.value.loaded || _uiState.value.saving) return@launch
        val epoch = synchronized(statusGate) { statusEpoch }
        val settings = savedSettings
        try {
            val snapshot = readRunStatus()
            val (status, machines) = withContext(Dispatchers.Default) {
                val entries = settings?.tokenEntries.orEmpty()
                val status = po0ConfiguredStatus(snapshot, entries)
                status to po0MachineUiStates(status.results, entries)
            }
            synchronized(statusGate) {
                if (statusEpoch == epoch && savedSettings === settings && !_uiState.value.saving && !_uiState.value.requestingCheck) {
                    if (manualBaselineRevision != null && snapshot.revision == manualBaselineRevision) {
                        _uiState.update { it.copy(statusReadFailed = false) }
                    } else {
                        manualBaselineRevision = null
                        manuallyCheckedIds = emptySet()
                        _uiState.update { it.copy(status = status, machines = machines, statusReadFailed = false) }
                    }
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            reportFailure("read check status", error)
            synchronized(statusGate) {
                if (statusEpoch == epoch && savedSettings === settings) _uiState.update { it.copy(statusReadFailed = true) }
            }
        }
        try {
            val runtime = probeRuntime()
            synchronized(statusGate) {
                if (statusEpoch == epoch && savedSettings === settings && !_uiState.value.saving) {
                    _uiState.update {
                        it.copy(runtimeStatus = runtime, runtimeLoaded = true, runtimeReadFailed = false,
                            runtimeProbeSequence = it.runtimeProbeSequence + 1)
                    }
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            reportFailure("read maintenance state", error)
            synchronized(statusGate) {
                if (statusEpoch == epoch && savedSettings === settings) {
                    _uiState.update { it.copy(runtimeLoaded = true, runtimeReadFailed = true) }
                }
            }
        }
    }

    private fun resumeMaintenance(): Job? {
        if (!_uiState.value.canResume) return null
        val settings = savedSettings ?: return null
        _uiState.update { it.copy(saving = true, message = null) }
        return viewModelScope.launch(ioDispatcher) {
            try {
                applyServices(settings)
                notifySettingsChanged()
                _uiState.update { it.copy(saving = false, message = Po0Message.MAINTENANCE_RESUMED) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                reportFailure("resume maintenance", error)
                _uiState.update { it.copy(saving = false, message = Po0Message.SAVED_SERVICE_UNAVAILABLE) }
            }
        }
    }

    private fun reset(): Job? {
        val snapshot = _uiState.value
        if (!snapshot.showResetDialog || snapshot.loading || snapshot.saving || snapshot.requestingCheck) return null
        _uiState.update { it.copy(saving = true, showResetDialog = false, message = null) }
        return viewModelScope.launch(ioDispatcher) {
            try {
                resetSettings()
                synchronized(statusGate) {
                    statusEpoch++
                    savedSettings = Po0Settings()
                    manualBaselineRevision = null
                    manuallyCheckedIds = emptySet()
                    _uiState.value = Po0UiState(loading = false, loaded = true, message = Po0Message.RESET_COMPLETED)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                reportFailure("reset credentials", error)
                _uiState.update { it.copy(saving = false, message = Po0Message.RESET_FAILED) }
            }
        }
    }
}
