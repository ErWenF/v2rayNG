package com.v2ray.ang.service

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.Po0RunStatus
import com.v2ray.ang.dto.Po0Settings
import com.v2ray.ang.extension.delay
import com.v2ray.ang.handler.Po0SettingsStore
import com.v2ray.ang.handler.isPo0NetworkReady
import com.v2ray.ang.handler.po0NetworkClient
import com.v2ray.ang.handler.po0PhysicalNetworkRequest
import com.v2ray.ang.handler.po0RunStatus
import com.v2ray.ang.handler.po0RunTokens
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

// The shared commit order fences old managers; submitted jobs retain their own manager's lifetime.
private val po0StatusWriter = Po0StatusWriter<Po0RunStatus>(
    write = { Po0SettingsStore.writeStatus(it) },
    reportFailure = { error ->
        LogUtil.e(AppConfig.TAG, "Po0 daemon status write failed (${error.javaClass.simpleName})")
    },
)

/** Daemon-owned network/session state; a newer network or configuration cancels every old HTTP call. */
internal class Po0WhitelistManager(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val appContext = context.applicationContext
    private val power = context.getSystemService(PowerManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Any()
    private var closed = false
    private var registered = false
    private var powerReceiverRegistered = false
    private var selectedNetwork: Network? = null
    private var everConnected = false
    private var configurationJob: Job? = null
    private var configurationVersion = 0L
    private val session = MutableStateFlow(Session())
    private val powerPolicyVersion = MutableStateFlow(0L)

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = synchronized(gate) {
            // Recalculate the next deadline without cancelling a network-change request in flight.
            if (!closed && registered) powerPolicyVersion.value++
        }
    }

    private data class Session(
        val version: Long = 0,
        val settings: Po0Settings? = null,
        val network: Network? = null,
        val immediate: Boolean = true,
        val firstRun: CompletableDeferred<Unit> = CompletableDeferred(),
        val statusWriteToken: Po0StatusWriteToken = Po0StatusWriteToken(),
    )

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = synchronized(gate) {
            if (!closed && registered && selectedNetwork != network) {
                selectedNetwork = network
                replaceNetwork(null)
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = synchronized(gate) {
            if (!closed && registered && selectedNetwork == network) {
                replaceNetwork(network.takeIf { isPo0NetworkReady(capabilities) })
            }
        }

        override fun onLost(network: Network) = synchronized(gate) {
            if (!closed && registered && selectedNetwork == network) {
                selectedNetwork = null
                replaceNetwork(null)
            }
        }
    }

    fun start() {
        scope.launch {
            session.collectLatest { current ->
                try {
                    runSession(current)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // Exception messages/causes can contain credential URLs; record only the failure category.
                    LogUtil.e(AppConfig.TAG, "Po0 daemon refresh failed (${error.javaClass.simpleName})")
                    publish(current, Po0RunStatus(totalCount = current.settings?.tokens?.size ?: 0, error = true))
                } finally {
                    current.firstRun.complete(Unit)
                }
            }
        }
        settingsChanged()
    }

    fun settingsChanged() = synchronized(gate) {
        if (closed) return@synchronized
        configurationJob?.cancel()
        val version = ++configurationVersion
        configurationJob = scope.launch {
            try {
                val settings = Po0SettingsStore.read()
                coroutineContext.ensureActive()
                synchronized(gate) {
                    if (!closed && version == configurationVersion) {
                        replaceSession(session.value.copy(settings = settings, immediate = true))
                        updateNetworkRegistration(settings)
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
                        publish(session.value, Po0RunStatus(error = true))
                    }
                }
            }
        }
    }

    fun refreshNow() = synchronized(gate) {
        if (!closed) replaceSession(session.value.copy(immediate = true))
    }

    suspend fun beforeHandover(network: Network) {
        val settings = session.value.settings ?: return
        if (!po0MonitoringEnabled(settings) || !settings.networkChangeEnabled) return
        // A broken API must never hold the proxy's handover indefinitely.
        withTimeoutOrNull(12_000L) {
            while (coroutineContext.isActive) {
                val current = session.first { it.network == network || !po0MonitoringEnabled(it.settings) }
                if (!po0MonitoringEnabled(current.settings)) return@withTimeoutOrNull
                current.firstRun.await()
                // A superseded session releases its waiters but does not prove the new one checked.
                if (session.value.version == current.version) return@withTimeoutOrNull
            }
        }
    }

    fun close() = synchronized(gate) {
        if (closed) return@synchronized
        closed = true
        session.value.statusWriteToken.invalidate()
        session.value.firstRun.complete(Unit)
        scope.cancel()
        unregisterNetwork()
    }

    /** No radio/network request or power receiver is held when the feature is disabled. */
    private fun updateNetworkRegistration(settings: Po0Settings?) {
        if (!po0MonitoringEnabled(settings)) {
            unregisterNetwork()
            selectedNetwork = null
            everConnected = false
            if (session.value.network != null) replaceSession(session.value.copy(network = null))
            return
        }
        if (registered || connectivity == null) return
        try {
            if (!powerReceiverRegistered) {
                ContextCompat.registerReceiver(
                    appContext, powerReceiver,
                    IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_ON)
                        addAction(Intent.ACTION_SCREEN_OFF)
                        addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                    }, ContextCompat.RECEIVER_NOT_EXPORTED
                )
                powerReceiverRegistered = true
            }
            registered = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ can observe the best physical network without keeping a radio alive.
                connectivity.registerBestMatchingNetworkCallback(
                    po0PhysicalNetworkRequest(), callback, Handler(Looper.getMainLooper())
                )
            } else {
                // API 24-30 lack passive best-network selection. Remove when minSdk reaches 31.
                connectivity.requestNetwork(po0PhysicalNetworkRequest(), callback)
            }
        } catch (error: Exception) {
            LogUtil.e(AppConfig.TAG, "Po0 daemon network registration failed (${error.javaClass.simpleName})")
            unregisterNetwork()
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
        session.value = next.copy(
            version = previous.version + 1,
            firstRun = CompletableDeferred(),
            statusWriteToken = Po0StatusWriteToken(),
        )
    }

    private suspend fun runSession(current: Session) {
        val settings = current.settings ?: return
        if (!po0MonitoringEnabled(settings)) {
            publish(current, Po0RunStatus())
            return
        }
        val network = current.network
        if (network == null) {
            publish(current, Po0RunStatus(totalCount = settings.tokens.size, error = true))
            return
        }
        // Android can announce Wi-Fi before DHCP/captive portal validation has settled.
        if (current.immediate) delay(1_000L) else awaitNextPoll(settings.intervalSeconds, 0)
        val client = po0NetworkClient(network)
        try {
            var failures = 0
            while (coroutineContext.isActive) {
                val results = po0RunTokens(client, settings.tokens)
                coroutineContext.ensureActive()
                val status = po0RunStatus(results)
                publish(current, status)
                current.firstRun.complete(Unit)
                failures = if (status.error) failures + 1 else 0
                awaitNextPoll(settings.intervalSeconds, failures)
            }
        } finally {
            client.close()
        }
    }

    private fun publish(current: Session, status: Po0RunStatus) = synchronized(gate) {
        if (!closed && session.value.version == current.version) {
            // Only queue under the state lock; JSON/MMKV never make callbacks or close wait here.
            po0StatusWriter.submit(scope, current.statusWriteToken, status)
        }
    }

    private fun nextDelay(interval: Int, failures: Int): Long = po0PollDelayMillis(
        interval, failures, power?.isInteractive != false, power?.isPowerSaveMode == true
    )

    private suspend fun awaitNextPoll(interval: Int, failures: Int) {
        val completedAt = SystemClock.elapsedRealtime()
        while (coroutineContext.isActive) {
            val version = powerPolicyVersion.value
            val remaining = (nextDelay(interval, failures) - (SystemClock.elapsedRealtime() - completedAt)).coerceAtLeast(0)
            if (remaining == 0L) return
            // Ordinary coroutine timers do not wake a sleeping device. Power changes only adjust this deadline.
            if (withTimeoutOrNull(remaining) { powerPolicyVersion.first { it != version } } == null) return
        }
    }
}

internal fun po0MonitoringEnabled(settings: Po0Settings?): Boolean =
    settings?.enabled == true && settings.tokens.isNotEmpty()

internal fun po0PollDelayMillis(
    intervalSeconds: Int, failures: Int, interactive: Boolean = true, batterySaver: Boolean = false
): Long {
    val configured = intervalSeconds.coerceIn(1, 3600).toLong() * 1_000
    val interval = if (!interactive || batterySaver) maxOf(configured, 900_000L) else configured
    if (failures <= 0) return interval
    return (interval * (1L shl failures.coerceAtMost(10))).coerceAtMost(maxOf(interval, 1_800_000L))
}

/** Only the daemon calls this owner registry; UI state never determines whether a proxy is alive. */
object Po0WhitelistRuntime {
    private val owners = mutableSetOf<Any>()
    private var manager: Po0WhitelistManager? = null

    @Synchronized
    fun attach(owner: Any, context: Context) {
        if (!owners.add(owner)) return
        if (manager == null) manager = Po0WhitelistManager(context.applicationContext).also { it.start() }
    }

    @Synchronized
    fun detach(owner: Any) {
        owners.remove(owner)
        if (owners.isEmpty()) {
            manager?.close()
            manager = null
        }
    }

    @Synchronized
    fun settingsChanged() { manager?.settingsChanged() }

    @Synchronized
    fun refreshNow() { manager?.refreshNow() }

    suspend fun beforeHandover(network: Network) {
        val current = synchronized(this) { manager }
        current?.beforeHandover(network)
    }
}
