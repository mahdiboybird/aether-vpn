package com.mahdi.aethervpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import tun2socks.Tun2socks
import tun2socks.Vless

/**
 * MAHDI VPN — full-device, portable VPN (works on rooted AND non-rooted phones).
 *
 * Uses go-tun2socks (libgojni.so): builds a TUN interface from Android VpnService
 * (no root needed), then tunnels ALL device traffic (incl. UDP so Telegram
 * audio/video calls work) directly to the Cloudflare VLESS worker we configured.
 */
class MahdiVpnService : VpnService() {

    private var thread: Thread? = null
    private var running = true
    private var tunFd: ParcelFileDescriptor? = null

    companion object {
        const val CHANNEL = "mahdi_vpn"
        const val ACTION_STATUS = "com.mahdi.aethervpn.STATUS"

        // Cloudflare VLESS server (already deployed & verified)
        const val VLESS_ADDR = "cdn-status-check.mahdiboybird.workers.dev"
        const val VLESS_PORT = 443L
        const val VLESS_ID = "f7259050-c186-4b30-8f75-34f248ef3cf7"
        const val VLESS_PATH = "/?ed=2048"

        const val TUN_ADDR = "10.66.66.66"
        const val TUN_NET = 24
        const val DNS = "1.1.1.1"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification("در حال راه‌اندازی MAHDI VPN..."))
        thread = Thread { runVpn() }
        thread?.start()
        return START_STICKY
    }

    private fun runVpn() {
        try {
            // establish TUN via VpnService — works on any device, no root needed
            val builder = Builder()
                .setSession("MAHDI VPN")
                .setMtu(1500)
                .addAddress(TUN_ADDR, TUN_NET)
                .addDnsServer(DNS)
                .addRoute("0.0.0.0", 0)   // whole ipv4 internet
                .addRoute("::", 0)        // ipv6 too

            val pfd = builder.establish()
            if (pfd == null) {
                sendStatus("خطا: اجازهٔ VPN داده نشد")
                return
            }
            tunFd = pfd
            val fd = pfd.fd.toLong()

            sendStatus("اتصال به سرور MAHDI...")

            // wrappers expected by go-tun2socks
            val vpnWrapper = object : tun2socks.VpnService {
                override fun protect(socket: Long): Boolean =
                    try { this@MahdiVpnService.protect(socket.toInt()); true }
                    catch (e: Exception) { false }
            }
            val logWrapper = object : tun2socks.LogService {
                override fun writeLog(s: String?) { Log.i("MAHDI_VPN", s ?: "") }
            }
            val speedWrapper = object : tun2socks.QuerySpeed {
                override fun updateTraffic(tx: Long, rx: Long) {}
            }

            // build VLESS config (Cloudflare worker)
            val vless = Tun2socks.newVless(
                VLESS_ADDR,     // address
                VLESS_PORT,     // port
                VLESS_ID,       // uuid
                "ws",           // network
                "tls",          // security
                VLESS_PATH,     // path
                VLESS_ADDR,     // host
                VLESS_ADDR,     // SNI
                "cdn",          // type
                "none",         // encryption
                "",             // flow
                "http",         // protocol
                byteArrayOf()   // header
            )

            val config = "{\"dns\":{\"enable\":true,\"listen\":\"" +
                "${DNS}:53\"},\"tun2socks\":{\"stack\":\"system\"}}"

            sendStatus("راه‌اندازی تونل...")
            Tun2socks.startXVlessTunFd(
                fd, vpnWrapper, logWrapper, speedWrapper, vless, config
            )

            sendStatus("متصل ✅ — کل ترافیک از طریق MAHDI VPN")
            while (running) Thread.sleep(1000)
        } catch (e: Exception) {
            sendStatus("خطا: ${e.message ?: e.javaClass.simpleName}")
            Log.e("MAHDI_VPN", "err", e)
        }
    }

    private fun sendStatus(s: String) {
        val i = Intent(ACTION_STATUS)
        i.putExtra("status", s)
        sendBroadcast(i)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("MAHDI VPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "MAHDI VPN", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        running = false
        thread?.interrupt()
        try { tunFd?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}