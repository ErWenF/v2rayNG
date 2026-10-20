package com.v2ray.ang.handler

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.Po0RunStatus
import com.v2ray.ang.dto.Po0Settings
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.service.Po0WhitelistService
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

object Po0ServiceControl {
    internal const val ACTION_CONTROL = "${BuildConfig.APPLICATION_ID}.PO0_CONTROL"

    /** Explicit user action; it never starts a proxy or requests VPN permission. */
    fun apply(context: Context, settings: Po0Settings) {
        val intent = Intent().setComponent(ComponentName(context, Po0WhitelistService::class.java))
        if (settings.enabled && settings.standaloneEnabled && settings.activeTokens.isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ContextCompat.startForegroundService(context, intent)
            else context.startService(intent)
        } else context.stopService(intent)
    }

    /** An acknowledgement proves a daemon exists now; persisted status alone cannot do so. */
    suspend fun status(context: Context): Po0RunStatus? = withContext(Dispatchers.IO) {
        val result = CompletableDeferred<Pair<Boolean?, String?>>()
        MessageHelper.sendPo0Command(context, AppConfig.MSG_PO0_STATUS) { handled, json ->
            result.complete(handled to json)
        }
        val response = withTimeoutOrNull(2_000L) { result.await() } ?: throw IOException("Po0 daemon status timed out")
        if (response.first == null) throw IOException("Po0 daemon status dispatch failed")
        if (response.first == false) return@withContext null
        val json = response.second ?: throw IOException("Po0 daemon status unavailable")
        Gson().fromJson(json, Po0RunStatus::class.java) ?: throw IOException("Po0 daemon status invalid")
    }

    fun notifySettingsChanged(context: Context) {
        MessageHelper.sendPo0Command(context, AppConfig.MSG_PO0_SETTINGS_CHANGED)
        MessageHelper.sendMsg2Service(context, AppConfig.MSG_PO0_SETTINGS_CHANGED, "")
    }

    fun notifyCredentialsReset(context: Context) {
        MessageHelper.sendPo0Command(context, AppConfig.MSG_PO0_CREDENTIALS_RESET)
    }

    /**
     * Keep manual and automatic writes in one physical-network session. A missing/disabled daemon
     * returns null; an accepted request times out explicitly instead of starting a duplicate locally.
     */
    internal suspend fun runInDaemon(context: Context, tokenIds: List<String>, queryOnly: Boolean): Po0RunStatus? =
        withContext(Dispatchers.IO) {
            val requestId = UUID.randomUUID().toString()
            val accepted = CompletableDeferred<Boolean?>()
            val response = CompletableDeferred<String>()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.getIntExtra("key", 0) == AppConfig.MSG_PO0_MANUAL_RESULT &&
                        intent.getStringExtra(MessageHelper.EXTRA_REQUEST_ID) == requestId) {
                        intent.getStringExtra("content")?.let { response.complete(it) }
                    }
                }
            }
            var registered = false
            try {
                ContextCompat.registerReceiver(context, receiver, IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY), ContextCompat.RECEIVER_NOT_EXPORTED)
                registered = true
                MessageHelper.sendPo0Command(context, AppConfig.MSG_PO0_MANUAL, requestId, tokenIds, queryOnly) { handled, _ ->
                    accepted.complete(handled)
                }
                // An unacknowledged delivery is uncertain, so do not race it with a local POST.
                val handled = withTimeoutOrNull(3_000L) { accepted.await() }
                    ?: throw IOException("Po0 daemon acknowledgement timed out")
                if (!handled) return@withContext null
                val json = withTimeoutOrNull(115_000L) { response.await() }
                    ?: throw IOException("Po0 daemon manual response timed out")
                Gson().fromJson(json, Po0RunStatus::class.java) ?: throw IOException("Po0 daemon manual response invalid")
            } finally {
                MessageHelper.sendPo0Command(context, AppConfig.MSG_PO0_CANCEL_MANUAL, requestId)
                if (registered) {
                    try { context.unregisterReceiver(receiver) } catch (error: Exception) {
                        LogUtil.e(AppConfig.TAG, "Po0 manual response cleanup failed (${error.javaClass.simpleName})")
                    }
                }
            }
        }
}
