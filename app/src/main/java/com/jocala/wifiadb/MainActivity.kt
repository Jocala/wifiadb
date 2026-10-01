package com.jocala.wifiadb

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        findViewById<Button>(R.id.btn_a11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btn_notif).setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            } else {
                Toast.makeText(this, "not needed below Android 13", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btn_run).setOnClickListener {
            WifiAdbService.requestEnsure(this, cycle = false)
            Toast.makeText(this, "ensure requested", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        status.text = buildStatus()
    }

    private fun buildStatus(): String {
        val enabledSvcs = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val svcOn = enabledSvcs.contains("$packageName/.WifiAdbService")
        val wifi = try {
            Settings.Global.getInt(contentResolver, "adb_wifi_enabled", -1)
        } catch (e: Exception) {
            -1
        }
        val prefs = getSharedPreferences(WifiAdbService.PREFS, MODE_PRIVATE)
        val last = prefs.getString(WifiAdbService.KEY_LAST_RESULT, "never run")
        val t = prefs.getLong(WifiAdbService.KEY_LAST_TIME, 0L)
        val whenStr = if (t == 0L) "" else SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(t))
        return "service enabled: $svcOn\nadb_wifi_enabled: $wifi\nlast: $last $whenStr"
    }
}
