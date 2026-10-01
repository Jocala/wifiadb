package com.jocala.wifiadb

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.lang.ref.WeakReference

/**
 * Re-enables Wireless debugging by tapping its Quick Settings tile.
 * Triggered at boot (see [BootReceiver] + [onServiceConnected]) or on demand
 * via [ACTION_RUN_ENSURE] / [ACTION_RUN_CYCLE] broadcasts. Lab use only.
 */
class WifiAdbService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private var control: BroadcastReceiver? = null

    override fun onServiceConnected() {
        Log.i(TAG, "onServiceConnected enter")
        instance = WeakReference(this)
        val f = IntentFilter().apply {
            addAction(ACTION_RUN_ENSURE)
            addAction(ACTION_RUN_CYCLE)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                ensureWifiAdb(cycle = i.action == ACTION_RUN_CYCLE, reason = "broadcast")
            }
        }
        registerReceiverSafely(r, f)
        control = r
        Log.i(TAG, "onServiceConnected receiver registered")
        // Fresh boot (or first enable): handle it here. The system binds enabled
        // services early at boot, typically before BOOT_COMPLETED is delivered.
        val boot = bootTime()
        val prefs = prefs()
        if (prefs.getLong(KEY_LAST_BOOT, -1L) != boot) {
            ensureWifiAdb(cycle = false, reason = "service-connected")
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        control?.let { unregisterReceiver(it) }
        control = null
        instance.clear()
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) = Unit
    override fun onInterrupt() = Unit

    private fun registerReceiverSafely(r: BroadcastReceiver, f: IntentFilter) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(r, f, RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(r, f)
            }
        } catch (e: Exception) {
            Log.i(TAG, "registerReceiver threw: $e")
        }
    }

    /** Idempotent entry point. Safe to call from receiver, activity, or self. */
    fun ensureWifiAdb(cycle: Boolean, reason: String) {
        if (running) {
            Log.i(TAG, "ensure already running, ignoring ($reason)")
            return
        }
        if (!prefs().getBoolean(KEY_AUTO, true) && reason != "broadcast") {
            record("skipped: auto-enable off")
            return
        }
        running = true
        prefs().edit().putLong(KEY_LAST_BOOT, bootTime()).apply()
        Log.i(TAG, "ensure start cycle=$cycle reason=$reason")
        stepRead(cycle, 0)
    }

    // ---- state machine (main thread, Handler-delayed, never blocking) ----

    private fun stepRead(cycle: Boolean, attempt: Int) {
        val isOn = readWifiGlobal() == 1
        Log.i(TAG, "global adb_wifi_enabled isOn=$isOn cycle=$cycle")
        if (isOn && !cycle) return finish(true, "already on")
        if (!openQs()) return finish(false, "could not open quick settings")
        stepFindTile(cycle, needOffFirst = cycle && isOn, deadline = now() + TILE_TIMEOUT)
    }

    private fun stepFindTile(cycle: Boolean, needOffFirst: Boolean, deadline: Long) {
        val tile = findTile()
        if (tile == null) {
            if (now() > deadline) {
                goHome()
                return finish(false, "tile not found in quick settings")
            }
            main.postDelayed({ stepFindTile(cycle, needOffFirst, deadline) }, POLL)
            return
        }
        val checked = tile.isChecked
        Log.i(TAG, "tile found checked=$checked needOffFirst=$needOffFirst")
        tile.recycle()
        when {
            needOffFirst && checked -> {
                if (!clickTile()) {
                    goHome()
                    return finish(false, "tap (off) failed")
                }
                stepWaitGlobal(expect = 0, then = {
                    main.postDelayed({ stepRead(cycle = true, attempt = 0) }, TAP_SETTLE)
                }, deadline = now() + SETTLE_TIMEOUT, failMsg = "did not turn off")
            }
            !checked -> {
                if (!clickTile()) {
                    goHome()
                    return finish(false, "tap (on) failed")
                }
                stepWaitGlobal(expect = 1, then = {
                    goHome()
                    finish(true, if (cycle) "cycled off/on" else "turned on")
                }, deadline = now() + SETTLE_TIMEOUT, failMsg = "tap did not stick")
            }
            else -> {
                // Tile says on but global says off (or finishing a cycle's on-phase):
                // one tap to force the real transition, then wait.
                if (!clickTile()) {
                    goHome()
                    return finish(false, "tap (sync) failed")
                }
                stepWaitGlobal(expect = 1, then = {
                    goHome()
                    finish(true, "synced on")
                }, deadline = now() + SETTLE_TIMEOUT, failMsg = "sync tap did not stick")
            }
        }
    }

    private fun stepWaitGlobal(expect: Int, then: () -> Unit, deadline: Long, failMsg: String) {
        if (readWifiGlobal() == expect) {
            then()
            return
        }
        if (now() > deadline) {
            goHome()
            finish(false, failMsg)
            return
        }
        main.postDelayed({ stepWaitGlobal(expect, then, deadline, failMsg) }, POLL)
    }

    private fun finish(ok: Boolean, msg: String) {
        running = false
        goHome() // every path ends at the launcher, nothing left open
        record((if (ok) "OK: " else "FAIL: ") + msg)
        Log.i(TAG, "ensure finish ok=$ok $msg")
        if (!ok) notifyFail(msg)
    }

    // ---- primitives ----

    private fun readWifiGlobal(): Int = try {
        Settings.Global.getInt(contentResolver, "adb_wifi_enabled", 0)
    } catch (e: Exception) {
        Log.i(TAG, "read global failed: $e")
        0
    }

    private fun openQs(): Boolean =
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    /** Leave no UI behind: QS/Settings closed, tablet back at the launcher. */
    private fun goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /** The tile dumped on onntab1: Switch, content-desc "Wireless debugging",
     *  text "Wireless debugging, On/Off". Match desc first, text prefix fallback. */
    private fun findTile(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return try {
            findTileIn(root)
        } finally {
            root.recycle()
        }
    }

    private fun findTileIn(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className == "android.widget.Switch") {
            val desc = node.contentDescription?.toString() ?: ""
            val text = node.text?.toString() ?: ""
            if (desc == TILE_DESC || text.startsWith(TILE_DESC)) return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findTileIn(child)
            if (hit != null) return hit
            child.recycle()
        }
        return null
    }

    private fun clickTile(): Boolean {
        val tile = findTile() ?: return false
        return try {
            tile.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally {
            tile.recycle()
        }
    }

    private fun record(msg: String) {
        prefs().edit()
            .putString(KEY_LAST_RESULT, msg)
            .putLong(KEY_LAST_TIME, System.currentTimeMillis())
            .apply()
    }

    private fun notifyFail(msg: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "WifiAdb", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        @Suppress("DEPRECATION")
        val n = if (android.os.Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            Notification.Builder(this)
        }
            .setContentTitle("Wireless debugging did not stick")
            .setContentText(msg)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .build()
        try {
            nm.notify(1, n)
        } catch (e: SecurityException) {
            Log.i(TAG, "notify denied: $e")
        }
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    companion object {
        const val TAG = "WifiAdb"
        const val PREFS = "wifiadb"
        const val KEY_AUTO = "auto_enable"
        const val KEY_LAST_BOOT = "last_boot_handled"
        const val KEY_LAST_RESULT = "last_result"
        const val KEY_LAST_TIME = "last_time"
        const val ACTION_RUN_ENSURE = "com.jocala.wifiadb.RUN_ENSURE"
        const val ACTION_RUN_CYCLE = "com.jocala.wifiadb.RUN_CYCLE"
        const val TILE_DESC = "Wireless debugging"
        private const val CHANNEL = "wifiadb"
        private const val POLL = 500L
        private const val TILE_TIMEOUT = 10_000L
        private const val SETTLE_TIMEOUT = 30_000L
        private const val TAP_SETTLE = 2_000L

        private var instance = WeakReference<WifiAdbService>(null)

        fun bootTime(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        private fun now(): Long = SystemClock.uptimeMillis()

        /** No-op unless the service is currently bound. */
        fun requestEnsure(context: Context, cycle: Boolean = false) {
            instance.get()?.ensureWifiAdb(cycle, "request")
                ?: context.sendBroadcast(
                    Intent(if (cycle) ACTION_RUN_CYCLE else ACTION_RUN_ENSURE)
                        .setPackage(context.packageName)
                )
        }
    }
}
