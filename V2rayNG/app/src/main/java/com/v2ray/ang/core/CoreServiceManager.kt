package com.v2ray.ang.core

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.IDialerService
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.BrowserDialerMode
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.service.DialerNativeService
import com.v2ray.ang.service.DialerWebviewService
import com.v2ray.ang.service.NetworkMonitor
import com.v2ray.ang.service.Po0WhitelistRuntime
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.ProcessFinder
import java.lang.ref.SoftReference
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

object CoreServiceManager {

    private val coreController: CoreController = CoreNativeManager.newCoreController(CoreCallback())
    private val mMsgReceive = ReceiveMessageHandler()
    private var currentConfig: ProfileItem? = null
    private var processFinder: XrayProcessFinder? = null
    private var browserDialer: IDialerService? = null
    @Volatile
    private var networkMonitor: NetworkMonitor? = null
    private val observerGate = Any()
    private var observersStopping = false
    private val handoverGate = CoreHandoverGate()
    private var coreLifecycle = CoreLifecycleOwner()
    // A pending restart owns the transition between two service lifetimes and ends at dispatch/cancel.
    private val restartScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var restartJob: Job? = null
    // Registration/release runs on Main; ownership prevents an old restart from removing a new receiver.
    private var receiverOwner: CoreLifecycleOwner? = null
    private val connectionTestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val reloadRequests = AtomicInteger()
    private val isReloading: Boolean get() = reloadRequests.get() > 0

    /** Tun descriptor the core was started with, null in the proxy only and root run modes. */
    private var currentVpnInterface: ParcelFileDescriptor? = null

    var serviceControl: SoftReference<ServiceControl>? = null
        set(value) {
            field = value
            val service = value?.get()?.getService()
            CoreNativeManager.initCoreEnv(service)
            if (service != null && processFinder == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                processFinder = XrayProcessFinder(service)
                coreController.registerProcessFinder(processFinder)
            }
        }

    /**
     * Checks if the V2Ray service is running.
     * @return True if the service is running, false otherwise.
     */
    fun isRunning() = coreController.isRunning

    /**
     * Gets the name of the currently running server.
     * @return The name of the running server.
     */
    fun getRunningServerName() = currentConfig?.remarks.orEmpty()

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     * Starts the V2Ray core service.
     */
    fun startCoreLoop(vpnInterface: ParcelFileDescriptor?): Boolean {
        if (synchronized(observerGate) { coreLifecycle.isStopping } || isReloading) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: Previous core mutation is still finishing")
            return false
        }
        if (isRunning()) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: Core already running")
            return false
        }

        val service = getService()
        if (service == null) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Service is null")
            return false
        }

        try {
            doStartCoreLoop(service, vpnInterface)
            return true
        } catch (e: Exception) {
            val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: $message", e)
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
            NotificationManager.cancelNotification()
            return false
        }
    }

    @Throws(Exception::class)
    private fun doStartCoreLoop(service: Service, vpnInterface: ParcelFileDescriptor?) {
        synchronized(observerGate) {
            // Reset before acquiring resources so a failed second start cannot reuse an old teardown.
            coreLifecycle = CoreLifecycleOwner()
        }
        val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE)
        mFilter.addAction(Intent.ACTION_SCREEN_ON)
        mFilter.addAction(Intent.ACTION_SCREEN_OFF)
        mFilter.addAction(Intent.ACTION_USER_PRESENT)
        // Keep accepting stop commands through teardown and pending restart dispatch.
        if (receiverOwner == null) {
            ContextCompat.registerReceiver(service.applicationContext, mMsgReceive, mFilter, Utils.receiverFlags())
        }
        receiverOwner = coreLifecycle

        currentVpnInterface = vpnInterface
        launchCore(service, vpnInterface)
        synchronized(observerGate) {
            observersStopping = false
            handoverGate.activate()
        }
        Po0WhitelistRuntime.attach(this, service)
        startNetworkMonitor(service)
    }

    @Throws(Exception::class)
    private fun launchCore(
        service: Service,
        vpnInterface: ParcelFileDescriptor?,
        isReload: Boolean = false,
        checkCurrent: () -> Unit = {},
    ) {
        checkCurrent()
        val guid = MmkvManager.getSelectServer() ?: error("No server selected")
        val config = MmkvManager.decodeServerConfig(guid) ?: error("Failed to decode server config")

        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting core loop for ${config.remarks}")
        val result = CoreConfigManager.getV2rayConfig(service, guid)
        LogUtil.d(AppConfig.TAG, result.content)
        if (!result.status) {
            error(result.errorMessage.ifBlank { "Failed to get V2Ray config" })
        }

        currentConfig = config
        var tunFd = vpnInterface?.fd ?: 0
        val dialerMode = BrowserDialerMode.from(config.browserDialerMode)
        val dialerAddr = if (dialerMode != null) {
            "127.0.0.1:${Utils.findRandomFreePort()}"
        } else {
            ""
        }
        if (SettingsManager.isUsingHevTun()) {
            tunFd = 0
        }

        checkCurrent()
        NotificationManager.showNotification(currentConfig)
        if (dialerAddr.isNotNullEmpty()) {
            CoreNativeManager.reconcileBrowserDialer(dialerAddr)
        }
        checkCurrent()
        coreController.startLoop(result.content, tunFd)
        checkCurrent()

        if (!isRunning()) {
            error("Core failed to start")
        }

        if (browserDialer != null) {
            browserDialer!!.stop()
            browserDialer = null
        }
        when (dialerMode) {
            BrowserDialerMode.OKHTTP -> {
                browserDialer = DialerNativeService()
                browserDialer!!.start(service, dialerAddr)
            }

            BrowserDialerMode.WEBVIEW -> {
                browserDialer = DialerWebviewService()
                browserDialer!!.start(service, dialerAddr)
            }

            else -> {}
        }

        if (!isReload) {
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
        }
        NotificationManager.startSpeedNotification()
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core started successfully")
    }

    /**
     * Stops the V2Ray core service.
     * Unregisters broadcast receivers, stops notifications, and shuts down plugins.
     * @return True if the core was stopped successfully, false otherwise.
     */
    fun stopCoreLoop(): Boolean {
        releaseNetworkObservers()
        connectionTestScope.coroutineContext.cancelChildren()
        val service = getService() ?: return false

        currentVpnInterface = null
        val owner = synchronized(observerGate) { coreLifecycle }
        owner.stop {
            try {
                handoverGate.teardown {
                    try {
                        if (isRunning()) coreController.stopLoop()
                    } catch (e: Exception) {
                        LogUtil.e(AppConfig.TAG, "StartCore-Manager: ${service.javaClass.simpleName} teardown native stop failed", e)
                    } finally {
                        try {
                            CoreNativeManager.reconcileBrowserDialer("")
                            browserDialer?.stop()
                        } catch (e: Exception) {
                            LogUtil.e(AppConfig.TAG, "StartCore-Manager: ${service.javaClass.simpleName} teardown dialer stop failed", e)
                        } finally {
                            browserDialer = null
                            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
                            NotificationManager.cancelNotification()
                        }
                    }
                }
            } finally {
                withContext(Dispatchers.Main.immediate) {
                    val pendingRestart = synchronized(observerGate) { restartJob?.isActive == true }
                    if (!pendingRestart) releaseReceiver(owner, service.applicationContext)
                }
            }
        }

        return true
    }

    private fun releaseReceiver(owner: CoreLifecycleOwner, context: Context) {
        if (receiverOwner !== owner) return
        try {
            context.unregisterReceiver(mMsgReceive)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: teardown receiver cleanup failed", e)
        } finally {
            receiverOwner = null
        }
    }

    /**
     * Subscribes to upstream network changes for whichever run mode is active.
     * All three services share this manager, so the tunnel recovers from a handover in proxy only
     * and root mode as well, not just behind the VPN interface.
     */
    private fun startNetworkMonitor(service: Service) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (networkMonitor != null) return

        val connectivity = service.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        lateinit var monitor: NetworkMonitor
        monitor = NetworkMonitor(
            connectivity = connectivity,
            onUnderlyingNetworksChanged = { networks -> serviceControl?.get()?.setUnderlyingNetworks(networks) },
            onHandover = { network ->
                // NetworkMonitor serializes callback entry with unregister. Suspend before doing any work.
                yield()
                Po0WhitelistRuntime.beforeHandover(network)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val reload = synchronized(observerGate) {
                    val ticket = handoverGate.ticket()
                    if (!observersStopping && networkMonitor === monitor && ticket != null) {
                        // A newer network may cancel the monitor job while native stop is in flight.
                        // Finish that native mutation in the lifecycle owner; teardown invalidates its ticket.
                        coreLifecycle.scope.async { reloadCore(ticket) }
                    } else null
                }
                reload?.await()
            },
        )
        networkMonitor = monitor
        monitor.register()
    }

    /** Cancel observers before either the native core or the Android service releases dependencies. */
    fun releaseNetworkObservers() {
        val monitor = synchronized(observerGate) {
            observersStopping = true
            handoverGate.invalidate()
            networkMonitor
        }
        monitor?.unregister()
        synchronized(observerGate) {
            if (networkMonitor === monitor) networkMonitor = null
        }
        Po0WhitelistRuntime.detach(this)
    }

    /**
     * Restarts the core in place after the upstream network changed: the service, the notification
     * and the VPN interface all stay up, so nothing of this is visible.
     *
     * The config is rebuilt on purpose, outbound server domains are resolved while building it and
     * an address resolved on a network that is gone can be unusable on the new one.
     *
     * @return True if the core is running again.
     */
    private suspend fun reloadCore(ticket: Long): Boolean {
        val service = getService() ?: return false
        reloadRequests.incrementAndGet()

        return try {
            val tunFd = currentVpnInterface

            handoverGate.reload(
                ticket = ticket,
                canReload = { isRunning() },
                stop = {
                    connectionTestScope.coroutineContext.cancelChildren()
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core reload start...")
                    coreController.stopLoop()
                },
                start = { checkCurrent -> launchCore(service, tunFd, isReload = true, checkCurrent = checkCurrent) },
            ).also { restarted ->
                if (restarted) LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core reload finished")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to reload core: $message", e)
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
            false
        } finally {
            reloadRequests.decrementAndGet()
        }
    }

    /**
     * Queries and resets all outbound traffic counters in one core call.
     * Go side format: tag,direction,value;tag,direction,value;
     */
    fun queryAllOutboundTrafficStats(): List<OutboundTrafficStat> {
        // The stats manager is gone once the core stops, querying it then reaches into freed state.
        if (!isRunning()) return emptyList()

        val payload = coreController.queryAllOutboundTrafficStats()

        val result = ArrayList<OutboundTrafficStat>()

        payload.split(';').forEach { entry ->
            if (entry.isBlank()) return@forEach

            val parts = entry.split(',', limit = 3)
            if (parts.size != 3) return@forEach

            val value = parts[2].toLongOrNull() ?: return@forEach

            result.add(
                OutboundTrafficStat(
                    tag = parts[0],
                    direction = parts[1],
                    value = value,
                )
            )
        }
//        LogUtil.d(AppConfig.TAG, "Queried outbound traffic stats: $result")
        return result
    }

    /**
     * Measures the connection delay for the current V2Ray configuration.
     * Tests with primary URL first, then falls back to alternative URL if needed.
     * Also fetches remote IP information if the delay test was successful.
     */
    private fun measureV2rayDelay(requestId: String) {
        val service = getService() ?: return
        if (!isRunning() || isReloading) {
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_CANCEL, "", requestId)
            return
        }

        connectionTestScope.coroutineContext.cancelChildren()
        connectionTestScope.launch {
            var time = -1L
            var errorStr = ""

            try {
                time = coreController.measureDelay(SettingsManager.getDelayTestUrl())
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                errorStr = e.message?.substringAfter("\":").orEmpty()
            }
            if (time == -1L) {
                ensureActive()
                try {
                    time = coreController.measureDelay(SettingsManager.getDelayTestUrl(true))
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                    errorStr = e.message?.substringAfter("\":").orEmpty()
                }
            }

            ensureActive()
            val endpoint = if (time >= 0) SpeedtestManager.getRemoteIPInfo() else null
            val result = ConnectionTestResult(
                delayMillis = time,
                errorMessage = errorStr,
                country = endpoint?.country,
                ipAddress = endpoint?.ipAddress,
            )
            withContext(Dispatchers.Main.immediate) {
                if (isRunning()) {
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_RESULT, result, requestId)
                } else {
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_CANCEL, "", requestId)
                }
            }
        }.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_CANCEL, "", requestId)
            }
        }
    }

    /**
     * Gets the current service instance.
     * @return The current service instance, or null if not available.
     */
    private fun getService(): Service? {
        return serviceControl?.get()?.getService()
    }

    /**
     * Core callback handler implementation for handling V2Ray core events.
     * Handles startup, shutdown, socket protection, and status emission.
     */
    private class CoreCallback : CoreCallbackHandler {
        /**
         * Called when V2Ray core starts up.
         * @return 0 for success, any other value for failure.
         */
        override fun startup(): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback startup")
            return 0
        }

        /**
         * Called when V2Ray core shuts down.
         * @return 0 for success, any other value for failure.
         */
        override fun shutdown(): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback shutdown")
            return 0
        }

        /**
         * Called when V2Ray core emits status information.
         * @param l Status code.
         * @param s Status message.
         * @return Always returns 0.
         */
        override fun onEmitStatus(l: Long, s: String?): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback onEmitStatus $s")
            return 0
        }
    }

    /**
     * Process finder implementation for Xray core.
     * Uses ConnectivityManager to find the owning UID of a connection based on network parameters.
     */
    private class XrayProcessFinder(context: Context) : ProcessFinder {
        private val cm: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

        override fun findProcessByConnection(network: String, srcIP: String, srcPort: Long, destIP: String, destPort: Long): Long {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1L
            if (cm == null) return -1L
            val proto = when (network) {
                "tcp" -> OsConstants.IPPROTO_TCP
                "udp" -> OsConstants.IPPROTO_UDP
                else -> return -1L
            }

            if (destIP.isBlank() || destPort == 0L) {
                LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to :$destPort, (no dest)")
                return -1L
            }

            return try {
                val uid = cm.getConnectionOwnerUid(
                    proto,
                    InetSocketAddress(srcIP, srcPort.toInt()),
                    InetSocketAddress(destIP, destPort.toInt())
                ).toLong()
                LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to $destIP:$destPort, uid=$uid")
                //LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to $destIP:$destPort, uid=$uid,${PackageUidResolver.uidToPackageName(uid.toString())}")

                uid
            } catch (_: Exception) {
                -1L
            }
        }
    }

    /**
     * Broadcast receiver for handling messages sent to the service.
     * Handles registration, service control, and screen events.
     */
    private class ReceiveMessageHandler : BroadcastReceiver() {
        /**
         * Handles received broadcast messages.
         * Processes service control messages and screen state changes.
         * @param ctx The context in which the receiver is running.
         * @param intent The intent being received.
         */
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val serviceControl = serviceControl?.get() ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    if (isRunning()) {
                        MessageHelper.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_RUNNING, "")
                    } else {
                        MessageHelper.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_NOT_RUNNING, "")
                    }
                }

                AppConfig.MSG_UNREGISTER_CLIENT -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_START -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_STOP -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Stop service")
                    synchronized(observerGate) { restartJob?.cancel() }
                    serviceControl.stopService()
                }

                AppConfig.MSG_STATE_RESTART -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Restart service")
                    // The UI and daemon run in separate processes, so acknowledge the active
                    // daemon before stopping it instead of relying on possibly stale UI state.
                    if (isOrderedBroadcast) resultCode = Activity.RESULT_OK

                    val context = serviceControl.getService().applicationContext
                    val pending = synchronized(observerGate) {
                        if (restartJob?.isActive == true) return
                        val owner = coreLifecycle
                        restartScope.launch(start = CoroutineStart.LAZY) {
                            try {
                                withContext(Dispatchers.Main.immediate) { serviceControl.stopService() }
                                // Root removes its routing before onDestroy asks the manager to stop.
                                // Wait for that teardown, rather than guessing a native stop duration.
                                owner.awaitStopped()
                                withContext(Dispatchers.Main.immediate) { LauncherManager.startService(context) }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                LogUtil.e(AppConfig.TAG, "StartCore-Manager: ${serviceControl.getService().javaClass.simpleName} restart dispatch failed", e)
                            } finally {
                                withContext(NonCancellable + Dispatchers.Main.immediate) {
                                    releaseReceiver(owner, context)
                                }
                            }
                        }.also { restartJob = it }
                    }
                    pending.start()
                }

                AppConfig.MSG_MEASURE_DELAY -> {
                    if (isOrderedBroadcast) resultCode = Activity.RESULT_OK
                    measureV2rayDelay(intent.getStringExtra("content").orEmpty())
                }

                AppConfig.MSG_PO0_SETTINGS_CHANGED -> Po0WhitelistRuntime.settingsChanged()

                AppConfig.MSG_PO0_REFRESH -> {
                    if (isOrderedBroadcast) resultCode = Activity.RESULT_OK
                    Po0WhitelistRuntime.refreshNow()
                }
            }

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen off")
                    NotificationManager.stopSpeedNotification()
                }

                Intent.ACTION_SCREEN_ON -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen on")
                    NotificationManager.startSpeedNotification()
                }
            }
        }
    }
}
