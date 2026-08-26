package com.mahdi.aethervpn

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

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
            val i = Intent(this, AetherService::class.java)
            if (!running) startForegroundService(i) else stopService(i)
            btn.isEnabled = false
            btn.postDelayed({ btn.isEnabled = true }, 1200)
        }

        registerReceiver(receiver, IntentFilter("com.mahdi.aethervpn.STATUS"))
    }

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Exception) { }
        super.onDestroy()
    }
}
