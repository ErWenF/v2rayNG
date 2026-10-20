package com.v2ray.ang.handler

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.v2ray.ang.dto.Po0Settings
import com.v2ray.ang.service.Po0WhitelistService

object Po0ServiceControl {
    /** Called from an explicit user action; it never starts a proxy or requests VPN permission. */
    fun apply(context: Context, settings: Po0Settings) {
        val intent = Intent().setComponent(ComponentName(context, Po0WhitelistService::class.java))
        if (settings.enabled && settings.standaloneEnabled && settings.tokens.isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ContextCompat.startForegroundService(context, intent)
            else context.startService(intent)
        } else {
            context.stopService(intent)
        }
    }
}
