package com.kprint.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kprint.app.data.SettingsStore
import com.kprint.app.printing.BluetoothPrinter

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val config = SettingsStore(context).load()
        if (MonitorPreference.isEnabled(context) && config.autoStart && config.isReady &&
            BluetoothPrinter(context).hasPermission()
        ) {
            runCatching { PrintMonitorService.start(context) }
        }
    }
}
