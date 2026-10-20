package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.root.RootProxyManager
import com.v2ray.ang.util.LogUtil
import java.lang.ref.SoftReference

/**
 * Foreground service for the root (system-wide) run modes. Unlike [CoreVpnService] it
 * does not use Android VpnService — traffic is routed by iptables instead
 * (see [RootProxyManager]).
 *
 * Foreground entry precedes the shared asynchronous preflight/setup owner. Root rules
 * are installed after the listener starts and removed before the listener stops.
 */
class CoreRootService : Service(), ServiceControl {

    private var routingSetupStarted = false

    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-Root: Service created")
        CoreServiceManager.serviceControl = SoftReference(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationManager.ensureForeground()
        LogUtil.i(AppConfig.TAG, "StartCore-Root: command received")

        if (!CoreServiceManager.startCoreService(
                control = this,
                startIntent = intent,
                afterCoreStart = {
                    routingSetupStarted = true
                    check(RootProxyManager.start(this)) { "Root routing setup failed" }
                },
                beforeCoreStop = {
                    if (routingSetupStarted) {
                        RootProxyManager.stop(this)
                        routingSetupStarted = false
                    }
                },
            )) {
            stopSelf()
        }
        // Root routing is not ready when this asynchronous start callback returns.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Shared teardown joins even a cancelled blocking root setup before removing its
        // routes, then stops native. RootShell bounds each command; Main never waits for su.
        CoreServiceManager.coreServiceDestroyed(this)
    }

    override fun getService(): Service = this

    override fun startService() {
        // do nothing
    }

    override fun stopService() {
        CoreServiceManager.stopCoreService(this)
    }

    override fun vpnProtect(socket: Int): Boolean = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let(AppLocaleManager::localizedContext)
        super.attachBaseContext(context)
    }
}
