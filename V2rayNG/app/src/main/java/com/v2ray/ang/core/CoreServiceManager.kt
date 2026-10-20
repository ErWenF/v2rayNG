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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.ProcessFinder
import java.lang.ref.SoftReference
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

object CoreServiceManager {

    // Service construction/foreground entry must not load or initialize the native core on Main.
    private val coreControllerDelegate = lazy { CoreNativeManager.newCoreController(CoreCallback()) }
    private val coreController: CoreController by coreControllerDelegate
    private val mMsgReceive = ReceiveMessageHandler()
    private var currentConfig: ProfileItem? = null
    private var processFinder: XrayProcessFinder? = null
    private var browserDialer: IDialerService? = null
    @Volatile
    private var networkMonitor: NetworkMonitor? = null
    private val observerGate = Any()
    private var observersStopping = false
    private val handoverGate = CoreHandoverGate()
    private class CoreServiceRun(
        val control: ServiceControl,
        val lifecycle: CoreLifecycleOwner = CoreLifecycleOwner(),
        val beforeCoreStop: suspend () -> Unit,
        val afterCoreStop: suspend () -> Unit,
    ) {
        val destroyed = CompletableDeferred<Unit>()
        var resourcesReleased = false
    }
    private class CoreRestartRequest(val ticket: Long) {
        val accepted = CompletableDeferred<Unit>()
    }
    private var currentRun: CoreServiceRun? = null
    private val restartGate = CoreRestartGate()
    private var restartRequest: CoreRestartRequest? = null
    // The transition owns command reception until Android accepts or rejects the replacement.
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

    /**
     * Checks if the V2Ray service is running.
     * @return True if the service is running, false otherwise.
     */
    fun isRunning() = coreControllerDelegate.isInitialized() && coreController.isRunning

    /**
     * Gets the name of the currently running server.
     * @return The name of the running server.
     */
    fun getRunningServerName() = currentConfig?.remarks.orEmpty()

    /**
     * Called on Main only after the service entered foreground. One shared owner covers the
     * bounded Po0 preflight, native startup and each mode's resources, including failed setup.
     */
    fun startCoreService(
        control: ServiceControl,
        startIntent: Intent? = null,
        prepareInterface: suspend () -> ParcelFileDescriptor? = { null },
        afterCoreStart: suspend () -> Unit = {},
        beforeCoreStop: suspend () -> Unit = {},
        afterCoreStop: suspend () -> Unit = {},
    ): Boolean {
        val service = control.getService()
        val run = synchronized(observerGate) {
            currentRun?.let {
                // onCreate temporarily supplies its own foreground-entry target. A rejected
                // different-mode instance must not retain authority over the active service.
                serviceControl = SoftReference(it.control)
                // An equivalent start is a no-op; a stopping attempt cannot be revived.
                return it.control === control && !it.lifecycle.isStopRequested
            }
            val restartTicket = startIntent?.takeIf { it.hasExtra(LauncherManager.RESTART_TICKET) }
                ?.getLongExtra(LauncherManager.RESTART_TICKET, -1L)
            if (!restartGate.accept(restartTicket)) return false
            // A newer explicit/system start also completes the transition while its untagged
            // admission invalidates any previously queued replacement intent.
            restartRequest?.accepted?.complete(Unit)
            // Admission supersedes the old transition even while its blocking IO unwinds.
            // Its cancellation/finally is scoped to the old ticket and receiver owner.
            restartJob?.cancel()
            CoreServiceRun(control, beforeCoreStop = beforeCoreStop, afterCoreStop = afterCoreStop).also {
                currentRun = it
                serviceControl = SoftReference(control)
                observersStopping = true
            }
        }
        try {
            val filter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE).apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            // STOP/RESTART must already work while the preflight is waiting for the network.
            if (receiverOwner == null) {
                ContextCompat.registerReceiver(service.applicationContext, mMsgReceive, filter, Utils.receiverFlags())
            }
            receiverOwner = run.lifecycle
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: ${service.javaClass.simpleName} setup receiver registration failed", e)
            stopCoreService(control)
            return false
        }

        run.lifecycle.start {
            val setupContext = currentCoroutineContext()
            val checkCurrent = {
                setupContext.ensureActive()
                if (synchronized(observerGate) { currentRun !== run || run.lifecycle.isStopRequested }) {
                    throw CancellationException("Core service setup superseded")
                }
            }
            try {
                checkCurrent()
                Po0WhitelistRuntime.beforeProxyStart(this@CoreServiceManager, service)
                checkCurrent()
                // Only an accepted attempt chooses a listener port; duplicate start commands
                // must not change the runtime port underneath an already running core.
                SettingsManager.refreshRuntimeSocksPort()
                CoreNativeManager.initCoreEnv(service)
                if (processFinder == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    processFinder = XrayProcessFinder(service)
                    coreController.registerProcessFinder(processFinder)
                }
                checkCurrent()
                val vpnInterface = prepareInterface()
                checkCurrent()
                currentVpnInterface = vpnInterface
                launchCore(service, vpnInterface, checkCurrent = checkCurrent)
                checkCurrent()
                afterCoreStart()
                checkCurrent()
                synchronized(observerGate) {
                    checkCurrent()
                    observersStopping = false
                    handoverGate.activate()
                }
                Po0WhitelistRuntime.attach(this@CoreServiceManager, service)
                checkCurrent()
                startNetworkMonitor(service, run)
                checkCurrent()
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
                NotificationManager.startSpeedNotification()
            } catch (e: CancellationException) {
                stopCoreService(control)
                throw e
            } catch (e: Exception) {
                val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: ${service.javaClass.simpleName} setup failed", e)
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, message)
                stopCoreService(control)
            }
        }
        return true
    }

    @Throws(Exception::class)
    private fun launchCore(
        service: Service,
        vpnInterface: ParcelFileDescriptor?,
        checkCurrent: () -> Unit = {},
    ) {
        checkCurrent()
        val guid = MmkvManager.getSelectServer() ?: error("No server selected")
        val config = MmkvManager.decodeServerConfig(guid) ?: error("Failed to decode server config")

        LogUtil.i(AppConfig.TAG, "StartCore-Manager: ${service.javaClass.simpleName} starting core profile=$guid")
        val result = CoreConfigManager.getV2rayConfig(service, guid)
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

        checkCurrent()
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core started successfully")
    }

    /** Mark stopping synchronously; all waiting and mode/native cleanup stays off Main. */
    fun stopCoreService(control: ServiceControl): Boolean {
        val run = synchronized(observerGate) {
            val active = currentRun?.takeIf { it.control === control } ?: return false
            observersStopping = true
            handoverGate.invalidate()
            active.lifecycle.stop { teardownService(active) }
            active
        }
        // Cancel handovers promptly. Po0 is detached only after setup has joined and the
        // proxy is down, so an in-flight attach cannot outlive its final service owner.
        stopNetworkMonitor()
        return run.lifecycle.isStopRequested
    }

    fun stopCoreLoop(): Boolean {
        val control = synchronized(observerGate) { currentRun?.control } ?: return false
        return stopCoreService(control)
    }

    /** Service callbacks acknowledge actual destruction; restart must not reuse a dying instance. */
    fun coreServiceDestroyed(control: ServiceControl) {
        val run = synchronized(observerGate) { currentRun?.takeIf { it.control === control } } ?: return
        stopCoreService(control)
        run.destroyed.complete(Unit)
        synchronized(observerGate) {
            // A timed-out teardown retains the dying instance as a fence until this callback.
            if (currentRun === run && run.resourcesReleased) currentRun = null
        }
    }

    private suspend fun teardownService(run: CoreServiceRun) {
        val service = run.control.getService()
        stopNetworkMonitor()
        connectionTestScope.coroutineContext.cancelChildren()
        try {
            // Root/LAN routing must disappear while its native listener is still available.
            try {
                run.beforeCoreStop()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: ${service.javaClass.simpleName} teardown mode resources failed", e)
            }
            handoverGate.teardown {
                try {
                    if (isRunning()) coreController.stopLoop()
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: ${service.javaClass.simpleName} teardown native stop failed", e)
                } finally {
                    try {
                        if (coreControllerDelegate.isInitialized()) CoreNativeManager.reconcileBrowserDialer("")
                        browserDialer?.stop()
                    } catch (e: Exception) {
                        LogUtil.e(AppConfig.TAG, "StartCore-Manager: ${service.javaClass.simpleName} teardown dialer stop failed", e)
                    } finally {
                        browserDialer = null
                    }
                }
            }
        } finally {
            try {
                // In VPN mode the descriptor belongs to the core until its stop has completed.
                run.afterCoreStop()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: ${service.javaClass.simpleName} teardown final resources failed", e)
            } finally {
                currentVpnInterface = null
                releaseNetworkObservers()
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
                    NotificationManager.cancelNotification()
                    val pendingRestart = synchronized(observerGate) { restartJob?.isActive == true }
                    if (!pendingRestart) releaseReceiver(run.lifecycle, service.applicationContext)
                    service.stopSelf()
                }
                // stopSelf only enqueues destruction. Waiting asynchronously keeps a restart
                // from being delivered to the old instance before its onDestroy callback runs.
                val destroyed = withTimeoutOrNull(5_000L) { run.destroyed.await() }
                if (destroyed == null) {
                    LogUtil.w(AppConfig.TAG, "StartCore-Manager: ${service.javaClass.simpleName} destroy acknowledgement timed out")
                }
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    synchronized(observerGate) {
                        run.resourcesReleased = true
                        // A missing acknowledgement must never allow a new attempt to reuse
                        // the dying Service. A late onDestroy releases this retained fence.
                        if (currentRun === run && run.destroyed.isCompleted) currentRun = null
                    }
                }
            }
        }
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
    private fun startNetworkMonitor(service: Service, run: CoreServiceRun): Unit = synchronized(observerGate) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@synchronized
        if (currentRun !== run || run.lifecycle.isStopRequested || observersStopping) return@synchronized
        if (networkMonitor != null) return@synchronized

        val connectivity = service.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return@synchronized
        lateinit var monitor: NetworkMonitor
        monitor = NetworkMonitor(
            connectivity = connectivity,
            onUnderlyingNetworksChanged = { networks -> run.control.setUnderlyingNetworks(networks) },
            onHandover = { network ->
                // NetworkMonitor serializes callback entry with unregister. Suspend before doing any work.
                yield()
                Po0WhitelistRuntime.beforeHandover(network)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val reload = synchronized(observerGate) {
                    val ticket = handoverGate.ticket()
                    if (!observersStopping && currentRun === run && !run.lifecycle.isStopRequested && networkMonitor === monitor && ticket != null) {
                        // A newer network may cancel the monitor job while native stop is in flight.
                        // Finish that native mutation in the lifecycle owner; teardown invalidates its ticket.
                        run.lifecycle.scope.async { reloadCore(ticket) }
                    } else null
                }
                reload?.await()
            },
        )
        networkMonitor = monitor
        monitor.register()
    }

    /** Final observer release after setup joined and the mode/core resources stopped. */
    fun releaseNetworkObservers() {
        stopNetworkMonitor()
        Po0WhitelistRuntime.detach(this, proxyStopped = true)
    }

    private fun stopNetworkMonitor() {
        val monitor = synchronized(observerGate) {
            observersStopping = true
            handoverGate.invalidate()
            networkMonitor
        }
        monitor?.unregister()
        synchronized(observerGate) {
            if (networkMonitor === monitor) networkMonitor = null
        }
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
                start = { checkCurrent -> launchCore(service, tunFd, checkCurrent = checkCurrent) },
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
        return (synchronized(observerGate) { currentRun?.control } ?: serviceControl?.get())?.getService()
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
            val serviceControl = synchronized(observerGate) { currentRun?.control } ?: serviceControl?.get() ?: return
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
                    synchronized(observerGate) {
                        restartGate.invalidate()
                        restartJob?.cancel()
                    }
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
                        val run = currentRun ?: return
                        val owner = run.lifecycle
                        val ticket = restartGate.request() ?: return
                        val request = CoreRestartRequest(ticket).also { restartRequest = it }
                        restartScope.launch(start = CoroutineStart.LAZY) {
                            try {
                                withContext(Dispatchers.Main.immediate) { serviceControl.stopService() }
                                // The shared owner joins setup, removes mode routing and stops the
                                // actual native core and receives onDestroy before dispatching replacement.
                                owner.awaitStopped()
                                currentCoroutineContext().ensureActive()
                                check(restartGate.canDispatch(ticket, run.destroyed.isCompleted)) {
                                    "Previous service destruction was not acknowledged"
                                }
                                val restartContext = currentCoroutineContext()
                                LauncherManager.startServiceForRestart(context, ticket) {
                                    restartContext.ensureActive()
                                    check(restartGate.canDispatch(ticket, run.destroyed.isCompleted)) {
                                        "Restart command superseded"
                                    }
                                }
                                // Keep STOP reception until the replacement actually adopts the
                                // receiver. If delivery stalls, invalidate its intent before release.
                                check(withTimeoutOrNull(5_000L) { request.accepted.await() } != null) {
                                    "Replacement service start acknowledgement timed out"
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                LogUtil.e(AppConfig.TAG, "StartCore-Manager: ${serviceControl.getService().javaClass.simpleName} restart dispatch failed", e)
                            } finally {
                                withContext(NonCancellable + Dispatchers.Main.immediate) {
                                    restartGate.cancel(ticket)
                                    synchronized(observerGate) {
                                        if (restartRequest === request) restartRequest = null
                                    }
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
