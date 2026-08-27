package com.mahdi.aethervpn

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.net.VpnService
import androidx.core.app.ActivityCompat
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var btn: Button
    private var running = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val s = i?.getStringExtra("status") ?: return
            statusText.text = s
            running = s.contains("متصل") || s.contains("در حال")
            btn.text = if (running) "قطع اتصال" else "اتصال"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status)
        btn = findViewById(R.id.toggle)

        btn.setOnClickListener {
            if (!running) {
                // request VPN permission if needed
                val i = VpnService.prepare(this)
                if (i != null) {
                    ActivityCompat.startActivityForResult(this, i, 100, null)
                } else {
                    startVpn()
                }
            } else {
                stopService(Intent(this, MahdiVpnService::class.java))
            }
            btn.isEnabled = false
            btn.postDelayed({ btn.isEnabled = true }, 1200)
        }

        registerReceiver(receiver, IntentFilter(MahdiVpnService.ACTION_STATUS))
    }

    private fun startVpn() {
        startForegroundService(Intent(this, MahdiVpnService::class.java))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) {
            startVpn()
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Exception) { }
        super.onDestroy()
    }
}
