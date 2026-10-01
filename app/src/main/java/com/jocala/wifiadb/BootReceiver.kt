package com.jocala.wifiadb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Belt and braces beside the service's own boot check: funnel into the same
 *  idempotent entry point (dedup by boot time inside the service). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(WifiAdbService.TAG, "BOOT_COMPLETED")
        WifiAdbService.requestEnsure(context, cycle = false)
    }
}
