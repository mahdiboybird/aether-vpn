package com.mahdi.aethervpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

class MahdiVpnService : VpnService() {

    private var proc: Process? = null
    private var vpnThread: Thread? = null
    private var tun2socks: Tun2Socks? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var running = true

    companion object {
        const val CHANNEL = "mahdi_vpn"
        const val ACTION_STATUS = "com.mahdi.aethervpn.STATUS"
        const val SO_NAME = "libmahdi.so"
        const val SOCKS_HOST = "127.0.0.1"
        const val SOCKS_PORT = 1819
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification("در حال راه‌اندازی VPN..."))
        vpnThread = Thread { runVpn() }
        vpnThread?.start()
        return START_STICKY
    }

    private fun findBinary(): File? {
        val libRoot = File(applicationInfo.nativeLibraryDir)
        fun scan(dir: File): File? {
            dir.listFiles()?.forEach { f ->
                if (f.isDirectory) scan(f)?.let { return it }
                else if (f.name == SO_NAME) return f
            }
            return null
        }
        return scan(libRoot)
    }

    private fun runVpn() {
        try {
            val bin = findBinary()
            if (bin == null) { sendStatus("خطا: باینری یافت نشد"); return }
            bin.setExecutable(true, false)
            sendStatus("در حال اجرای Aether...")
            val pb = ProcessBuilder(listOf(bin.absolutePath, "--wg", "--scan", "balanced", "--bind", "$SOCKS_HOST:$SOCKS_PORT"))
            pb.redirectErrorStream(true)
            proc = pb.start()
            Thread.sleep(4000)
            sendStatus("Aether فعال — در حال ساخت تونل...")

            val builder = Builder()
                .setSession("MAHDI VPN")
                .addAddress("10.10.10.2", 24)
                .addDnsServer("1.1.1.1")
                .addRoute("0.0.0.0", 0)
                .establish()
            if (builder == null) { sendStatus("خطا: ساخت تونل ناموفق"); return }
            vpnInterface = builder

            // start tun2socks to bridge TUN <-> Aether SOCKS5
            tun2socks = Tun2Socks(builder, SOCKS_HOST, SOCKS_PORT)
            tun2socks?.start()
            sendStatus("متصل ✅ — کل ترافیک از طریق MAHDI VPN")

            // keep monitoring aether
            proc?.inputStream?.bufferedReader()?.use { r ->
                while (running) {
                    val line = r.readLine() ?: break
                    Log.d("MAHDI_VPN", line)
                }
            }
            sendStatus("قطع شد")
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
        tun2socks?.stopThread()
        proc?.destroy()
        vpnThread?.interrupt()
        try { vpnInterface?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
