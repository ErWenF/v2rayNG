package com.v2ray.ang.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.Po0SettingsStore
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.ui.po0.Po0SettingsActivity
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Optional user-visible foreground owner for whitelist maintenance with the proxy stopped. */
class Po0WhitelistService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var setupJob: Job? = null
    private val lifecycleGate = Any()
    private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 8+ requires foreground entry before configuration/Keystore/network work.
        NotificationHelper.startForeground(
            this,
            NotificationChannelType.PO0_WHITELIST,
            getString(R.string.po0_notification_title),
            getString(R.string.po0_notification_content),
            NotificationCompat.Action.Builder(
                0,
                getString(R.string.po0_notification_manage),
                PendingIntent.getActivity(
                    this, 90, Intent(this, Po0SettingsActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            ).build()
        )
        setupJob?.cancel()
        setupJob = serviceScope.launch {
            try {
                val settings = Po0SettingsStore.read()
                ensureActive()
                if (!settings.enabled || !settings.standaloneEnabled || settings.tokens.isEmpty()) {
                    stopSelf(startId)
                    return@launch
                }
                synchronized(lifecycleGate) {
                    if (!destroyed) {
                        ensureActive()
                        Po0WhitelistRuntime.attach(this@Po0WhitelistService, this@Po0WhitelistService)
                        Po0WhitelistRuntime.settingsChanged()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Po0 standalone setup failed (${error.javaClass.simpleName})")
                stopSelf(startId)
            }
        }
        // Saving explicitly starts this optional owner after a system kill or force-stop.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        synchronized(lifecycleGate) {
            destroyed = true
            serviceScope.cancel()
            Po0WhitelistRuntime.detach(this)
        }
        NotificationHelper.stopForeground(this)
        NotificationHelper.cancel(NotificationChannelType.PO0_WHITELIST, this)
        super.onDestroy()
    }

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let(AppLocaleManager::localizedContext))
    }
}
