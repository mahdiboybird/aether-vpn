package com.mahdi.aethervpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import tun2socks.Tun2socks
import tun2socks.Vless

/**
 * MAHDI VPN — full-device portable VPN (no root, works on any phone).
 * Go-tun2socks (libgojni.so) via VpnService TUN -> Cloudflare VLESS.
 * Fixes: notification channel, foreground type, protect() instance, proper VLESS field order.
 */
class MahdiVpnService : VpnService() {

    private var thread: Thread? = null
    @Volatile private var running = true
    private var tunFd: ParcelFileDescriptor? = null

    companion object {
        const val CHANNEL = "mahdi_vpn"
        const val ACTION_STATUS = "com.mahdi.aethervpn.STATUS"
        const val VLESS_ADDR = "cdn-status-check.mahdiboybird.workers.dev"
        const val VLESS_PORT = 443L
        const val VLESS_ID = "f7259050-c186-4b30-8f75-34f248ef3cf7"
        const val VLESS_PATH = "/?ed=2048"
        const val TUN_ADDR = "10.66.66.66"
        const val TUN_NET = 24
        const val DNS = "1.1.1.1"
        const val TAG = "MAHDI_VPN"
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "MAHDI VPN", NotificationManager.IMPORTANCE_LOW)
            ch.description = "MAHDI VPN tunnel status"
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        // Prevent double-start
        if (thread?.isAlive == true) return START_STICKY
        running = true
        try {
            startForeground(1, buildNotification("در حال راه‌اندازی MAHDI VPN..."))
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            sendStatus("خطا: مجوز اعلان داده نشده")
            stopSelf()
            return START_NOT_STICKY
        }
        thread = Thread { runVpn() }
        thread?.start()
        return START_STICKY
    }

    private fun runVpn() {
        var pfd: ParcelFileDescriptor? = null
        try {
            val builder = Builder()
                .setSession("MAHDI VPN")
                .setMtu(1500)
                .addAddress(TUN_ADDR, TUN_NET)
                .addDnsServer(DNS)
                .addRoute("0.0.0.0", 0)
            // IPv6 route only if system supports it — prevents crash on some ROMs
            try { builder.addRoute("::", 0) } catch (_: Exception) {}

            pfd = builder.establish()
            if (pfd == null) {
                sendStatus("خطا: اجازهٔ VPN داده نشد")
                stopSelf()
                return
            }
            tunFd = pfd
            val fd = pfd.fd.toLong()
            sendStatus("اتصال به سرور MAHDI...")

            val vpnWrapper = object : tun2socks.VpnService {
                override fun protect(socket: Long): Boolean = try {
                    this@MahdiVpnService.protect(socket.toInt())
                } catch (e: Exception) {
                    Log.e(TAG, "protect failed", e); false
                }
            }
            val logWrapper = object : tun2socks.LogService {
                override fun writeLog(s: String?) { Log.i(TAG, s ?: "") }
            }
            val speedWrapper = object : tun2socks.QuerySpeed {
                override fun updateTraffic(tx: Long, rx: Long) {}
            }

            // Correct field order matching tun2socks.Vless (see javap):
            // Vless(add, port, id, net, type, security, encryption, flow, protocol, path, host, sni, header)
            // But Tun2socks.newVless() JNI signature is (String,long,String,String,String,String,String,String,String,String,String,String,byte[])
            // Verified via javap: 13 args. We pass in documented order used by upstream App.
            val vless: Vless
            try {
                vless = Tun2socks.newVless(
                    VLESS_ADDR,  // add
                    VLESS_PORT,  // port
                    VLESS_ID,    // id
                    "ws",        // net
                    "none",      // type (original: cdn type handled elsewhere)
                    "tls",       // security
                    "none",      // encryption
                    "",          // flow
                    "",          // protocol (empty = default)
                    VLESS_PATH,  // path
                    VLESS_ADDR,  // host
                    VLESS_ADDR,  // sni
                    byteArrayOf()
                )
            } catch (e: Exception) {
                Log.e(TAG, "newVless failed", e)
                sendStatus("خطا در ساخت کانفیگ: ${e.message}")
                stopSelf(); return
            }

            val config = "{\"dns\":{\"enable\":true,\"listen\":\"${DNS}:53\"},\"tun2socks\":{\"stack\":\"system\"}}"
            sendStatus("راه‌اندازی تونل...")
            try {
                Tun2socks.startXVlessTunFd(fd, vpnWrapper, logWrapper, speedWrapper, vless, config)
            } catch (e: Exception) {
                Log.e(TAG, "startXVlessTunFd failed", e)
                sendStatus("خطا در اتصال: ${e.message ?: e.javaClass.simpleName}")
                stopSelf(); return
            }

            sendStatus("متصل ✅ — کل ترافیک از طریق MAHDI VPN")
            updateNotification("متصل ✅ — MAHDI VPN فعال است")
            while (running) Thread.sleep(1000)
        } catch (e: Exception) {
            Log.e(TAG, "runVpn error", e)
            sendStatus("خطا: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            try { pfd?.close() } catch (_: Exception) {}
            try { tunFd?.close() } catch (_: Exception) {}
        }
    }

    private fun sendStatus(s: String) {
        try {
            val i = Intent(ACTION_STATUS).apply { putExtra("status", s) }
            sendBroadcast(i)
        } catch (_: Exception) {}
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1, buildNotification(text))
        } catch (_: Exception) {}
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopIntent = Intent(this, MahdiVpnService::class.java).apply { action = "STOP" }
        val stopPi = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder
            .setContentTitle("MAHDI VPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .addAction(Notification.Action.Builder(null, "قطع", stopPi).build())
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        thread?.interrupt()
        try { tunFd?.close() } catch (_: Exception) {}
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = super.onBind(intent)
}
