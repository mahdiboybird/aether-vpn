package com.mahdi.aethervpn

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.net.VpnService
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var btn: Button
    private var running = false
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val s = i?.getStringExtra("status") ?: return
            runOnUiThread {
                statusText.text = s
                running = s.contains("متصل")
                btn.text = if (running) "قطع اتصال" else "اتصال"
            }
            if (s.startsWith("خطا")) {
                runOnUiThread { Toast.makeText(this@MainActivity, s, Toast.LENGTH_LONG).show() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status)
        btn = findViewById(R.id.toggle)

        // Register receiver with Android 13+ flag
        val filter = IntentFilter(MahdiVpnService.ACTION_STATUS)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
            receiverRegistered = true
        } catch (e: Exception) {
            // fallback
            try { registerReceiver(receiver, filter); receiverRegistered = true } catch (_: Exception) {}
        }

        btn.setOnClickListener {
            if (!running) {
                // POST_NOTIFICATIONS on Android 13+ — request if needed but don't block VPN
                if (Build.VERSION.SDK_INT >= 33) {
                    val perm = android.Manifest.permission.POST_NOTIFICATIONS
                    if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(this, arrayOf(perm), 101)
                    }
                }
                val i = VpnService.prepare(this)
                if (i != null) {
                    try { startActivityForResult(i, 100) } catch (e: Exception) {
                        Toast.makeText(this, "خطا: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } else {
                    startVpn()
                }
            } else {
                stopService(Intent(this, MahdiVpnService::class.java))
                statusText.text = "قطع شد"
                btn.text = "اتصال"
                running = false
            }
            btn.isEnabled = false
            btn.postDelayed({ btn.isEnabled = true }, 1200)
        }
    }

    private fun startVpn() {
        try {
            val intent = Intent(this, MahdiVpnService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            statusText.text = "در حال اتصال..."
        } catch (e: Exception) {
            Toast.makeText(this, "خطا در شروع سرویس: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Continue regardless — foreground service will still show notification on most ROMs
        if (requestCode == 101) {
            // no-op, user can still connect
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100) {
            if (resultCode == RESULT_OK) startVpn()
            else Toast.makeText(this, "مجوز VPN رد شد", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        if (receiverRegistered) try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
