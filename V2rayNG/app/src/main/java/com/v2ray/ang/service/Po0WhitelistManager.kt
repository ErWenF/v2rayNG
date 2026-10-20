package com.v2ray.ang.service

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.Po0MaintenanceState
import com.v2ray.ang.dto.Po0ResultState
import com.v2ray.ang.dto.Po0RunStatus
import com.v2ray.ang.dto.Po0Settings
import com.v2ray.ang.dto.Po0TokenResult
import com.v2ray.ang.handler.Po0ServiceControl
import com.v2ray.ang.handler.Po0RequestCoordinator
import com.v2ray.ang.handler.Po0SettingsStore
import com.v2ray.ang.handler.isPo0NetworkReady
import com.v2ray.ang.handler.po0NetworkClient
import com.v2ray.ang.handler.po0PhysicalNetworkRequest
import com.v2ray.ang.handler.po0RunStatus
import com.v2ray.ang.handler.po0RunTokens
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.po0ServerId
import com.v2ray.ang.util.po0TokenId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.coroutineContext

private val po0StatusWriter = Po0StatusWriter<Po0RunStatus>(
    write = { Po0SettingsStore.writeStatus(it) },
    reportFailure = { error ->
        LogUtil.e(AppConfig.TAG, "Po0 daemon status write failed (${error.javaClass.simpleName})")
    },
)

/** New physical-network/config sessions cancel obsolete HTTP. Events reuse an existing batch. */
internal class Po0WhitelistManager(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val appContext = context.applicationContext
    private val power = context.getSystemService(PowerManager::class.java)
    private val coordinator = Po0RequestCoordinator(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Any()
    private var closed = false
    private var registered = false
    private var powerReceiverRegistered = false
    private var controlRegistered = false
    private var selectedNetwork: Network? = null
    private var selectedReady = false
    private var selectedBlocked = false
    private var linkFingerprint: String? = null
    private var everConnected = false
    private var registrationRetryAt = 0L
    private var registrationFailures = 0
    private var configurationJob: Job? = null
    private var configurationVersion = 0L
    private var eventVersion = 0L
    private var consumedEvent = 0L
    private val wake = MutableStateFlow(0L)
    private val session = MutableStateFlow(Session())
    private val eventWaiters = mutableListOf<CompletableDeferred<Unit>>()
    private val manualQueue = ArrayDeque<ManualWork>()
    private val manualJobs = mutableMapOf<String, Job>()
    private val manualIds = mutableMapOf<String, Set<String>>()
    // Server cooldown survives network/config changes within this manager's lifetime.
    private val serverNotBefore = mutableMapOf<String, Long>()
    private val configurationPlan = Po0CheckSchedule()
    private var liveStatus = Po0RunStatus(maintenance = Po0MaintenanceState.STARTING, revision = UUID.randomUUID().toString())
    private var liveJson = Gson().toJson(liveStatus)

    private data class Session(
        val version: Long = 0,
        val settings: Po0Settings? = null,
        val network: Network? = null,
        val immediate: Boolean = true,
        val firstRun: CompletableDeferred<Unit> = CompletableDeferred(),
        val statusWriteToken: Po0StatusWriteToken = Po0StatusWriteToken(),
    )

    private data class ManualWork(
        val version: Long,
        val ids: Set<String>,
        val queryOnly: Boolean,
        val reply: CompletableDeferred<Po0RunStatus>,
    )

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = synchronized(gate) {
            // Recalculate deadlines; screen/power changes do not initiate another HTTP batch.
            if (!closed) wake.value++
        }
    }

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || !isOrderedBroadcast) return
            synchronized(gate) {
                if (closed) return
                when (intent.getIntExtra("key", 0)) {
                    AppConfig.MSG_PO0_STATUS -> {
                        resultCode = Activity.RESULT_OK
                        resultData = liveJson
                    }
                    AppConfig.MSG_PO0_SETTINGS_CHANGED -> {
                        resultCode = Activity.RESULT_OK
                        settingsChanged()
                    }
                    AppConfig.MSG_PO0_CREDENTIALS_RESET -> {
                        resultCode = Activity.RESULT_OK
                        configurationJob?.cancel()
                        configurationVersion++
                        replaceSession(session.value.copy(settings = Po0Settings(), network = null))
                        updateNetworkRegistration(null)
                    }
                    AppConfig.MSG_PO0_MANUAL -> {
                        if (!po0MonitoringEnabled(session.value.settings)) return
                        val id = intent.getStringExtra(MessageHelper.EXTRA_REQUEST_ID) ?: return
                        val ids = intent.getStringArrayListExtra("tokenIds")?.toSet().orEmpty()
                        if (ids.isEmpty()) return
                        resultCode = Activity.RESULT_OK
                        if (manualJobs.containsKey(id)) return
                        if (manualJobs.size >= 8) {
                            val current = session.value
                            scope.launch {
                                MessageHelper.sendMsg2UI(appContext, AppConfig.MSG_PO0_MANUAL_RESULT, Gson().toJson(failedManual(current, ids)), id)
                            }
                            return
                        }
                        val current = session.value
                        val reply = CompletableDeferred<Po0RunStatus>()
                        val work = ManualWork(current.version, ids, intent.getBooleanExtra("queryOnly", false), reply)
                        val job = scope.launch(start = CoroutineStart.LAZY) {
                            try {
                                val status = if (current.network == null) failedManual(current, ids) else {
                                    synchronized(gate) { manualQueue.addLast(work); wake.value++ }
                                    withTimeoutOrNull(110_000L) { reply.await() } ?: failedManual(current, ids)
                                }
                                MessageHelper.sendMsg2UI(appContext, AppConfig.MSG_PO0_MANUAL_RESULT, Gson().toJson(status), id)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                LogUtil.e(AppConfig.TAG, "Po0 daemon manual request failed (${error.javaClass.simpleName})")
                                MessageHelper.sendMsg2UI(appContext, AppConfig.MSG_PO0_MANUAL_RESULT, Gson().toJson(failedManual(current, ids)), id)
                            } finally {
                                reply.cancel()
                                synchronized(gate) { manualQueue.remove(work); manualJobs.remove(id); manualIds.remove(id) }
                            }
                        }
                        manualJobs[id] = job
                        manualIds[id] = ids
                        job.start()
                    }
                    AppConfig.MSG_PO0_CANCEL_MANUAL -> {
                        resultCode = Activity.RESULT_OK
                        manualJobs[intent.getStringExtra(MessageHelper.EXTRA_REQUEST_ID)]?.cancel()
                    }
                }
            }
        }
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = synchronized(gate) {
            if (!closed && registered && selectedNetwork != network) {
                selectedNetwork = network
                selectedReady = false
                selectedBlocked = false
                linkFingerprint = null
                replaceNetwork(null)
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = synchronized(gate) {
            if (!closed && registered && selectedNetwork == network) {
                selectedReady = isPo0NetworkReady(capabilities)
                replaceNetwork(network.takeIf { selectedReady && !selectedBlocked })
            }
        }

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = synchronized(gate) {
            if (!closed && registered && selectedNetwork == network) {
                val fingerprint = po0LinkFingerprint(
                    properties.interfaceName, properties.linkAddresses.map { it.toString() },
                    properties.routes.filter { it.isDefaultRoute }.map { it.toString() },
                )
                val previous = linkFingerprint
                linkFingerprint = fingerprint
                if (previous != null && previous != fingerprint && session.value.network == network) {
                    replaceSession(session.value.copy(immediate = session.value.settings?.networkChangeEnabled != false))
                }
            }
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) = synchronized(gate) {
            // Android 10+ reports policy blocks separately from VALIDATED; remove guard at minSdk 29.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !closed && registered && selectedNetwork == network) {
                selectedBlocked = blocked
                replaceNetwork(network.takeIf { selectedReady && !blocked })
            }
        }

        override fun onLost(network: Network) = synchronized(gate) {
            if (!closed && registered && selectedNetwork == network) {
                selectedNetwork = null
                selectedReady = false
                linkFingerprint = null
                replaceNetwork(null)
            }
        }
    }

    fun start() {
        scope.launch {
            session.collectLatest { current ->
                var failures = 0
                while (coroutineContext.isActive) {
                    try {
                        runSession(current)
                        break
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        LogUtil.e(AppConfig.TAG, "Po0 daemon refresh failed (${error.javaClass.simpleName})")
                        publish(current, snapshot(current, Po0MaintenanceState.RETRYING).copy(error = true))
                        current.firstRun.complete(Unit)
                        failures++
                        delay((30_000L * (1L shl failures.coerceAtMost(5))).coerceAtMost(900_000L))
                    }
                }
            }
        }
        scope.launch {
            var failures = 0
            while (coroutineContext.isActive) {
                val version = wake.value
                val ready = synchronized(gate) {
                    if (closed) return@launch
                    registerControl()
                    updateNetworkRegistration(session.value.settings)
                    controlRegistered && (!po0MonitoringEnabled(session.value.settings) || registered)
                }
                failures = if (ready) 0 else (failures + 1).coerceAtMost(5)
                if (ready) wake.first { it != version }
                else awaitWake(version, (30_000L * (1L shl (failures - 1))).coerceAtMost(900_000L))
            }
        }
        settingsChanged()
    }

    fun settingsChanged(): Unit = synchronized(gate) {
        if (closed) return@synchronized
        configurationJob?.cancel()
        val version = ++configurationVersion
        configurationJob = scope.launch {
            try {
                val settings = Po0SettingsStore.read()
                coroutineContext.ensureActive()
                synchronized(gate) {
                    if (!closed && version == configurationVersion) {
                        if (session.value.settings != settings) replaceSession(session.value.copy(settings = settings, immediate = true))
                        updateNetworkRegistration(settings)
                        wake.value++
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Po0 daemon configuration unavailable (${error.javaClass.simpleName})")
                synchronized(gate) {
                    if (!closed && version == configurationVersion) {
                        replaceSession(session.value.copy(settings = null))
                        updateNetworkRegistration(null)
                        publish(session.value, Po0RunStatus(error = true, maintenance = Po0MaintenanceState.NEEDS_ATTENTION))
                        wake.value++
                    }
                }
                delay(30_000L)
                synchronized(gate) { if (!closed && version == configurationVersion) settingsChanged() }
            }
        }
    }

    fun requestCheck(): CompletableDeferred<Unit> = synchronized(gate) {
        val done = CompletableDeferred<Unit>()
        if (closed) done.complete(Unit) else {
            eventVersion++
            eventWaiters.removeAll { it.isCompleted }
            eventWaiters.add(done)
            wake.value++
        }
        done
    }

    suspend fun beforeProxyStart() {
        val done = requestCheck()
        try {
            // Total window includes configuration, validation and HTTP. The service may cancel it.
            withTimeoutOrNull(5_000L) {
                val current = session.first { it.settings != null }
                if (po0MonitoringEnabled(current.settings)) done.await()
            }
        } finally {
            done.cancel()
        }
    }

    suspend fun beforeHandover(network: Network) {
        val settings = session.value.settings ?: return
        if (!po0MonitoringEnabled(settings) || !settings.networkChangeEnabled) return
        withTimeoutOrNull(12_000L) {
            while (coroutineContext.isActive) {
                val current = session.first { it.network == network || !po0MonitoringEnabled(it.settings) }
                if (!po0MonitoringEnabled(current.settings)) return@withTimeoutOrNull
                current.firstRun.await()
                if (session.value.version == current.version) return@withTimeoutOrNull
            }
        }
    }

    fun close() = synchronized(gate) {
        if (closed) return@synchronized
        closed = true
        session.value.statusWriteToken.invalidate()
        session.value.firstRun.complete(Unit)
        eventWaiters.forEach { it.complete(Unit) }
        eventWaiters.clear()
        // Accepted manual work gets a terminal response before its last lifecycle owner cancels it.
        manualIds.forEach { (id, ids) ->
            MessageHelper.sendMsg2UI(appContext, AppConfig.MSG_PO0_MANUAL_RESULT, Gson().toJson(failedManual(session.value, ids)), id)
        }
        scope.cancel()
        unregisterNetwork()
        if (controlRegistered) {
            controlRegistered = false
            try {
                appContext.unregisterReceiver(controlReceiver)
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Po0 daemon control cleanup failed (${error.javaClass.simpleName})")
            }
        }
    }

    private fun registerControl() {
        if (controlRegistered || closed) return
        try {
            ContextCompat.registerReceiver(appContext, controlReceiver, IntentFilter(Po0ServiceControl.ACTION_CONTROL), ContextCompat.RECEIVER_NOT_EXPORTED)
            controlRegistered = true
        } catch (error: Exception) {
            LogUtil.e(AppConfig.TAG, "Po0 daemon control registration failed (${error.javaClass.simpleName})")
        }
    }

    private fun updateNetworkRegistration(settings: Po0Settings?) {
        if (!po0MonitoringEnabled(settings)) {
            unregisterNetwork()
            selectedNetwork = null
            selectedReady = false
            linkFingerprint = null
            everConnected = false
            registrationFailures = 0
            registrationRetryAt = 0
            if (session.value.network != null) replaceSession(session.value.copy(network = null))
            return
        }
        if (registered || connectivity == null) return
        if (SystemClock.elapsedRealtime() < registrationRetryAt) return
        try {
            if (!powerReceiverRegistered) {
                ContextCompat.registerReceiver(
                    appContext, powerReceiver, IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_ON)
                        addAction(Intent.ACTION_SCREEN_OFF)
                        addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                    }, ContextCompat.RECEIVER_NOT_EXPORTED
                )
                powerReceiverRegistered = true
            }
            registered = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                connectivity.registerBestMatchingNetworkCallback(po0PhysicalNetworkRequest(), callback, Handler(Looper.getMainLooper()))
            } else {
                // API 24-30 lack passive best-network selection. Remove when minSdk reaches 31.
                connectivity.requestNetwork(po0PhysicalNetworkRequest(), callback)
            }
            registrationFailures = 0
            registrationRetryAt = 0
        } catch (error: Exception) {
            LogUtil.e(AppConfig.TAG, "Po0 daemon network registration failed (${error.javaClass.simpleName})")
            unregisterNetwork()
            registrationFailures = (registrationFailures + 1).coerceAtMost(5)
            registrationRetryAt = po0Deadline(SystemClock.elapsedRealtime(),
                (30_000L * (1L shl (registrationFailures - 1))).coerceAtMost(900_000L))
            publish(session.value, snapshot(session.value, Po0MaintenanceState.RETRYING).copy(error = true))
        }
    }

    private fun unregisterNetwork() {
        if (powerReceiverRegistered) {
            powerReceiverRegistered = false
            try {
                appContext.unregisterReceiver(powerReceiver)
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Po0 daemon power receiver cleanup failed (${error.javaClass.simpleName})")
            }
        }
        if (registered) {
            registered = false
            try {
                connectivity?.unregisterNetworkCallback(callback)
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Po0 daemon network cleanup failed (${error.javaClass.simpleName})")
            }
        }
    }

    private fun replaceNetwork(network: Network?) {
        val previous = session.value
        if (previous.network == network) return
        val immediate = !everConnected || previous.settings?.networkChangeEnabled != false
        if (network != null) everConnected = true
        replaceSession(previous.copy(network = network, immediate = immediate))
    }

    private fun replaceSession(next: Session) {
        val previous = session.value
        previous.statusWriteToken.invalidate()
        previous.firstRun.complete(Unit)
        session.value = next.copy(version = previous.version + 1, firstRun = CompletableDeferred(), statusWriteToken = Po0StatusWriteToken())
        manualQueue.forEach { it.reply.complete(failedManual(previous, it.ids)) }
        manualQueue.clear()
        wake.value++
    }

    private suspend fun runSession(current: Session) {
        val settings = current.settings ?: return
        if (!po0MonitoringEnabled(settings)) {
            configurationPlan.retain(emptySet())
            publish(current, snapshot(current, Po0MaintenanceState.STOPPED))
            current.firstRun.complete(Unit)
            finishEvent(current)
            return
        }
        val network = current.network
        if (network == null) {
            publish(current, snapshot(current, Po0MaintenanceState.WAITING_NETWORK))
            return
        }
        val ids = settings.tokens.map(::po0TokenId).toSet()
        configurationPlan.retain(ids)
        val plan = Po0CheckSchedule().apply { inheritConfigurationFailures(configurationPlan, ids) }
        val results = settings.tokenEntries.associate {
            val id = po0TokenId(it.token)
            id to if (it.paused) Po0TokenResult(id, Po0ResultState.PAUSED)
                else plan.configurationFailure(id) ?: Po0TokenResult(id, Po0ResultState.UNCHECKED)
        }.toMutableMap()
        val conflicting = settings.tokenEntries.groupBy { po0ServerId(it.token) }.values
            .filter { it.size > 1 }.flatten().map { po0TokenId(it.token) }.toSet()
        val initialDeadline = po0Deadline(SystemClock.elapsedRealtime(), if (current.immediate) 1_000L else nextDelay(settings.intervalSeconds, 0))
        val client = po0NetworkClient(network)
        fun publishNextSchedule(): Long {
            val next = settings.activeTokens.minOf { token ->
                maxOf(initialDeadline, plan.dueAt(po0TokenId(token)) { failures -> nextDelay(settings.intervalSeconds, failures) },
                    serverNotBefore[po0ServerId(token)] ?: 0)
            }
            val state = if (results.values.any { it.state in setOf(Po0ResultState.REJECTED, Po0ResultState.DISABLED, Po0ResultState.CONFLICT) })
                Po0MaintenanceState.NEEDS_ATTENTION else if (results.values.any { it.state == Po0ResultState.ERROR })
                Po0MaintenanceState.RETRYING else Po0MaintenanceState.SCHEDULED
            publish(current, status(current, results.values.toList(), state).copy(nextCheckAtElapsed = next))
            return next
        }
        var inFlight: ManualWork? = null
        try {
            publishNextSchedule()
            if (current.immediate) delay(1_000L)
            while (coroutineContext.isActive) {
                val wakeVersion = wake.value
                val manual = synchronized(gate) {
                    while (manualQueue.firstOrNull()?.reply?.isCancelled == true) manualQueue.removeFirst()
                    manualQueue.removeFirstOrNull()
                }
                inFlight = manual
                if (manual != null && manual.version != current.version) {
                    manual.reply.complete(failedManual(current, manual.ids))
                    inFlight = null
                    continue
                }
                val now = SystemClock.elapsedRealtime()
                val event = synchronized(gate) { eventVersion != consumedEvent }
                if (manual == null && !event && now < initialDeadline) {
                    awaitWake(wakeVersion, initialDeadline - now)
                    continue
                }
                val tokens = if (manual != null) settings.tokens.filter { po0TokenId(it) in manual.ids }
                    else settings.activeTokens.filter { plan.shouldCheck(po0TokenId(it), now, event) { failures -> nextDelay(settings.intervalSeconds, failures) } }
                if (tokens.isNotEmpty()) {
                    // IPC is the prompt cancellation path; storage identity is the missed-delivery fallback.
                    if (!Po0SettingsStore.isCurrentConfiguration(settings)) {
                        settingsChanged()
                        return
                    }
                    publish(current, status(current, results.values.toList(), Po0MaintenanceState.CHECKING).copy(running = true))
                    val allowed = tokens.filter { token ->
                        po0TokenId(token) !in conflicting && now >= (serverNotBefore[po0ServerId(token)] ?: 0)
                    }
                    val checked = if (manual == null) po0RunTokens(client, allowed, coordinator = coordinator) else try { coroutineScope {
                        val request = async { po0RunTokens(client, allowed, manual.queryOnly, coordinator = coordinator) }
                        val cancellation = manual.reply.invokeOnCompletion { if (manual.reply.isCancelled) request.cancel() }
                        try { request.await() } finally { cancellation.dispose() }
                    } } catch (error: CancellationException) {
                        coroutineContext.ensureActive()
                        if (!manual.reply.isCancelled) throw error
                        emptyList()
                    }
                    coroutineContext.ensureActive()
                    if (manual?.reply?.isCancelled == true) {
                        inFlight = null
                        // Cancellation also ends CHECKING during a delayed first automatic cycle.
                        publishNextSchedule()
                        continue
                    }
                    val completedAt = SystemClock.elapsedRealtime()
                    val batch = tokens.map { token ->
                        val id = po0TokenId(token)
                        checked.find { it.tokenId == id } ?: if (id in conflicting) {
                            Po0TokenResult(id, Po0ResultState.CONFLICT, localConflict = true)
                        } else Po0TokenResult(id, Po0ResultState.ERROR, httpStatus = 429,
                            retryAfterMillis = ((serverNotBefore[po0ServerId(token)] ?: completedAt) - completedAt).coerceAtLeast(0))
                    }
                    val checkedIds = checked.map { it.tokenId }.toSet()
                    batch.forEach { result ->
                        results[result.tokenId] = result
                        if (manual?.queryOnly != true && (result.tokenId in checkedIds || result.tokenId in conflicting)) {
                            plan.record(result.tokenId, result, completedAt)
                            configurationPlan.record(result.tokenId, result, completedAt)
                        }
                        if (manual?.queryOnly == true && result.state in setOf(Po0ResultState.APPLIED, Po0ResultState.NOT_APPLIED) &&
                            (result.state == Po0ResultState.NOT_APPLIED || configurationPlan.configurationFailure(result.tokenId) != null)) {
                            plan.invalidate(result.tokenId)
                            configurationPlan.invalidate(result.tokenId)
                        }
                        if (result.tokenId in checkedIds && result.retryAfterMillis > 0) {
                            val token = tokens.first { po0TokenId(it) == result.tokenId }
                            serverNotBefore[po0ServerId(token)] = po0Deadline(completedAt, result.retryAfterMillis)
                        }
                    }
                    publish(current, status(current, results.values.toList(), Po0MaintenanceState.SCHEDULED).copy(checkedAt = System.currentTimeMillis()))
                    manual?.reply?.complete(po0RunStatus(batch))
                    inFlight = null
                    if (manual?.queryOnly != true) {
                        current.firstRun.complete(Unit)
                        finishEvent(current)
                    }
                } else if (manual != null) {
                    manual.reply.complete(failedManual(current, manual.ids))
                    inFlight = null
                } else if (event) finishEvent(current)
                val next = publishNextSchedule()
                if (synchronized(gate) { manualQueue.isNotEmpty() || eventVersion != consumedEvent }) continue
                awaitWake(wakeVersion, (next - SystemClock.elapsedRealtime()).coerceAtLeast(1L))
            }
        } finally {
            inFlight?.let { it.reply.complete(failedManual(current, it.ids)) }
            client.close()
        }
    }

    private fun finishEvent(current: Session) = synchronized(gate) {
        if (closed || session.value.version != current.version) return@synchronized
        consumedEvent = eventVersion
        eventWaiters.forEach { it.complete(Unit) }
        eventWaiters.clear()
    }

    private fun failedManual(current: Session, ids: Set<String>): Po0RunStatus = po0RunStatus(
        (current.settings?.tokens.orEmpty().map(::po0TokenId).filter { it in ids }.ifEmpty { ids.toList() })
            .map { Po0TokenResult(it, Po0ResultState.ERROR) }
    )

    private fun snapshot(current: Session, maintenance: Po0MaintenanceState): Po0RunStatus {
        val previous = synchronized(gate) { liveStatus.results.associateBy { it.tokenId } }
        return status(current, current.settings?.tokenEntries.orEmpty().map {
            val id = po0TokenId(it.token)
            previous[id] ?: Po0TokenResult(id, if (it.paused) Po0ResultState.PAUSED else Po0ResultState.UNCHECKED)
        }, maintenance)
    }

    private fun status(current: Session, results: List<Po0TokenResult>, maintenance: Po0MaintenanceState): Po0RunStatus =
        po0RunStatus(results, synchronized(gate) { liveStatus.checkedAt }).copy(
            totalCount = current.settings?.tokens?.size ?: 0, maintenance = maintenance,
            configurationId = current.settings?.storageIdentity.orEmpty(),
        )

    private fun publish(current: Session, status: Po0RunStatus) = synchronized(gate) {
        if (!closed && session.value.version == current.version) {
            liveStatus = status.copy(revision = UUID.randomUUID().toString())
            liveJson = Gson().toJson(liveStatus)
            po0StatusWriter.submit(scope, current.statusWriteToken, liveStatus)
        }
    }

    private fun nextDelay(interval: Int, failures: Int): Long = po0PollDelayMillis(
        interval, failures, power?.isInteractive != false, power?.isPowerSaveMode == true
    )

    private suspend fun awaitWake(version: Long, timeout: Long) {
        withTimeoutOrNull(timeout.coerceAtLeast(1L)) { wake.first { it != version } }
    }
}

internal fun po0MonitoringEnabled(settings: Po0Settings?): Boolean =
    settings?.enabled == true && settings.activeTokens.isNotEmpty()

internal fun po0PollDelayMillis(
    intervalSeconds: Int, failures: Int, interactive: Boolean = true, batterySaver: Boolean = false
): Long {
    val configured = intervalSeconds.coerceIn(1, 3600).toLong() * 1_000
    val interval = if (!interactive || batterySaver) maxOf(configured, 900_000L) else configured
    if (failures <= 0) return interval
    return (interval * (1L shl failures.coerceAtMost(10))).coerceAtMost(maxOf(interval, 1_800_000L))
}

object Po0WhitelistRuntime {
    private val owners = mutableSetOf<Any>()
    private var manager: Po0WhitelistManager? = null

    @Synchronized
    fun attach(owner: Any, context: Context) {
        if (!owners.add(owner)) return
        if (manager == null) manager = Po0WhitelistManager(context.applicationContext).also { it.start() }
    }

    @Synchronized
    fun detach(owner: Any, proxyStopped: Boolean = false) {
        if (!owners.remove(owner)) return
        if (owners.isEmpty()) {
            manager?.close()
            manager = null
        } else if (proxyStopped) manager?.requestCheck()
    }

    @Synchronized
    fun settingsChanged() { manager?.settingsChanged() }

    @Synchronized
    fun refreshNow() { manager?.requestCheck() }

    suspend fun beforeProxyStart(owner: Any, context: Context) {
        attach(owner, context)
        synchronized(this) { manager }?.beforeProxyStart()
    }

    suspend fun beforeHandover(network: Network) {
        synchronized(this) { manager }?.beforeHandover(network)
    }
}
