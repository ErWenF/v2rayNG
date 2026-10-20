package com.v2ray.ang.ui.po0

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.Po0RunStatus
import com.v2ray.ang.dto.Po0Settings
import com.v2ray.ang.handler.Po0ManualClient
import com.v2ray.ang.handler.Po0ServiceControl
import com.v2ray.ang.handler.Po0SettingsStore
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.parsePo0Tokens
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

internal enum class Po0FormError { TOKENS_REQUIRED, INVALID_TOKENS, INVALID_INTERVAL }

internal enum class Po0Message {
    LOAD_FAILED, SAVE_FAILED, SAVED, SAVED_SERVICE_UNAVAILABLE,
    MANUAL_COMPLETED, CHECK_REQUEST_FAILED
}

internal data class Po0UiState(
    val loading: Boolean = true,
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val requestingCheck: Boolean = false,
    val enabled: Boolean = false,
    val tokenText: String = "",
    val intervalText: String = "600",
    val networkChangeEnabled: Boolean = true,
    val standaloneEnabled: Boolean = false,
    val dirty: Boolean = false,
    val formErrors: Set<Po0FormError> = emptySet(),
    val message: Po0Message? = null,
    val status: Po0RunStatus = Po0RunStatus(),
    val machines: List<Po0MachineUiState> = emptyList(),
    val statusReadFailed: Boolean = false
) {
    val editable: Boolean get() = loaded && !loading && !saving && !requestingCheck
    val canCheckNow: Boolean get() = editable && !dirty && tokenText.isNotBlank()
}

internal fun validatePo0Form(state: Po0UiState): Set<Po0FormError> = buildSet {
    val tokens = parsePo0Tokens(state.tokenText)
    if (state.enabled && tokens.isEmpty()) add(Po0FormError.TOKENS_REQUIRED)
    if (!validatePo0Tokens(state.tokenText)) add(Po0FormError.INVALID_TOKENS)
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application,
        Po0SettingsStore::read,
        Po0SettingsStore::save,
        Po0SettingsStore::readStatus,
        { settings -> Po0ServiceControl.apply(application, settings) },
        { MessageHelper.sendMsg2Service(application, AppConfig.MSG_PO0_SETTINGS_CHANGED, "") },
        { settings, queryOnly -> Po0ManualClient.run(application, settings, queryOnly) },
        { operation, error ->
            // Storage/JSON exceptions may include credentials; retain only the exception type.
            val safeError = IOException("Po0 UI failure (${error.javaClass.simpleName})")
            LogUtil.e(AppConfig.TAG, "Po0 settings UI: $operation", safeError)
        }
    )

    private val _uiState = MutableStateFlow(Po0UiState())
    internal val uiState = _uiState.asStateFlow()
    private var loadJob: Job? = null
    private var savedSettings: Po0Settings? = null
    @Volatile
    private var manualCheckedAt: Long = 0

    internal fun onAction(action: Po0Action): Job? {
        when (action) {
            Po0Action.Load -> return load()
            Po0Action.Save -> return save()
            Po0Action.CheckNow -> return checkNow(queryOnly = false)
            Po0Action.Query -> return checkNow(queryOnly = true)
            Po0Action.RefreshStatus -> return refreshStatus()
            else -> {
                if (!_uiState.value.editable) return null
                _uiState.update { state ->
                    val edited = when (action) {
                        is Po0Action.SetEnabled -> state.copy(enabled = action.enabled)
                        is Po0Action.SetTokenText -> state.copy(tokenText = action.text)
                        is Po0Action.SetInterval -> state.copy(intervalText = action.text)
                        is Po0Action.SetNetworkChangeEnabled -> state.copy(networkChangeEnabled = action.enabled)
                        is Po0Action.SetStandaloneEnabled -> state.copy(standaloneEnabled = action.enabled)
                        else -> state
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
                savedSettings = settings
                _uiState.update {
                    it.copy(
                        loading = false,
                        loaded = true,
                        enabled = settings.enabled,
                        tokenText = settings.tokens.joinToString("\n"),
                        intervalText = settings.intervalSeconds.toString(),
                        networkChangeEnabled = settings.networkChangeEnabled,
                        standaloneEnabled = settings.standaloneEnabled
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                reportFailure("load", error)
                _uiState.update { it.copy(loading = false, message = Po0Message.LOAD_FAILED) }
            }
        }.also { loadJob = it }
    }

    private fun save(): Job? {
        val snapshot = _uiState.value
        if (!snapshot.editable) return null
        _uiState.update { it.copy(saving = true, message = null) }
        return viewModelScope.launch(ioDispatcher) {
            try {
                val errors = withContext(Dispatchers.Default) { validatePo0Form(snapshot) }
                if (errors.isNotEmpty()) {
                    _uiState.update { it.copy(saving = false, formErrors = errors) }
                    return@launch
                }
                val settings = withContext(Dispatchers.Default) {
                    Po0Settings(
                        enabled = snapshot.enabled,
                        tokens = parsePo0Tokens(snapshot.tokenText),
                        intervalSeconds = snapshot.intervalText.trim().toInt(),
                        networkChangeEnabled = snapshot.networkChangeEnabled,
                        standaloneEnabled = snapshot.standaloneEnabled
                    )
                }
                writeSettings(settings)
                savedSettings = settings
                _uiState.update {
                    it.copy(
                        tokenText = settings.tokens.joinToString("\n"),
                        intervalText = settings.intervalSeconds.toString(),
                        dirty = false,
                        formErrors = emptySet(),
                        message = Po0Message.SAVED
                    )
                }
                try {
                    applyServices(settings)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    reportFailure("apply saved settings", error)
                    _uiState.update { it.copy(message = Po0Message.SAVED_SERVICE_UNAVAILABLE) }
                }
                try {
                    notifySettingsChanged()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    reportFailure("notify saved settings", error)
                    _uiState.update { it.copy(message = Po0Message.SAVED_SERVICE_UNAVAILABLE) }
                }
                _uiState.update { it.copy(saving = false) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                reportFailure("save", error)
                _uiState.update { it.copy(saving = false, message = Po0Message.SAVE_FAILED) }
            }
        }
    }

    private fun checkNow(queryOnly: Boolean): Job? {
        if (!_uiState.value.canCheckNow) return null
        val settings = savedSettings ?: return null
        _uiState.update { it.copy(requestingCheck = true, message = null) }
        return viewModelScope.launch(ioDispatcher) {
            try {
                val result = runManualCheck(settings, queryOnly)
                val status = if (result.checkedAt > 0) result else result.copy(checkedAt = System.currentTimeMillis())
                val machines = withContext(Dispatchers.Default) { status.results.map(::po0MachineUiState) }
                manualCheckedAt = status.checkedAt
                _uiState.update {
                    val newerStatusAlreadyShown = it.status.checkedAt > status.checkedAt
                    it.copy(
                        requestingCheck = false,
                        status = if (newerStatusAlreadyShown) it.status else status,
                        machines = if (newerStatusAlreadyShown) it.machines else machines,
                        statusReadFailed = false,
                        message = Po0Message.MANUAL_COMPLETED
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                reportFailure("request check", error)
                _uiState.update { it.copy(requestingCheck = false, message = Po0Message.CHECK_REQUEST_FAILED) }
            }
        }
    }

    private fun refreshStatus(): Job = viewModelScope.launch(ioDispatcher) {
        try {
            val status = readRunStatus()
            val machines = withContext(Dispatchers.Default) { status.results.map(::po0MachineUiState) }
            _uiState.update {
                // Manual results are local to this screen; a stale service snapshot must not erase them.
                if (manualCheckedAt > 0 && status.checkedAt <= manualCheckedAt) it.copy(statusReadFailed = false)
                else it.copy(status = status, machines = machines, statusReadFailed = false)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            reportFailure("read check status", error)
            _uiState.update { it.copy(statusReadFailed = true) }
        }
    }
}
