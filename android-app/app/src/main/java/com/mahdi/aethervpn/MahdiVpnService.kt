package com.mahdi.aethervpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class MahdiVpnService : VpnService() {

    private var proc: Process? = null
    private var thread: Thread? = null
    private var vpnInterface: ParcelFileDescriptor? = null

    companion object {
        const val CHANNEL = "mahdi_vpn"
        const val ACTION_STATUS = "com.mahdi.aethervpn.STATUS"
        const val SO_NAME = "libmahdi.so"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification("در حال راه‌اندازی VPN..."))
        thread = Thread { runVpn() }
        thread?.start()
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

    private fun copyToExecutable(src: File): File {
        // /data/local/tmp is world-executable on most devices
        val dst = File("/data/local/tmp", "mahdi")
        try {
            src.copyTo(dst, overwrite = true)
        } catch (e: Exception) {
            // fallback to app's exec dir
            val f2 = File(filesDir, "mahdi")
            src.copyTo(f2, overwrite = true)
            return f2
        }
        return dst
    }

    private fun runVpn() {
        try {
            val bin = findBinary()
            if (bin == null) {
                sendStatus("خطا: باینری یافت نشد")
                return
            }
            val exe = copyToExecutable(bin)
            exe.setExecutable(true, false)
            Log.d("MAHDI_VPN", "exec: ${exe.absolutePath}, exists=${exe.exists()}, canExec=${exe.canExecute()}")

            val cmd = listOf(exe.absolutePath, "--wg", "--scan", "balanced", "--bind", "127.0.0.1:1819")
            val pb = ProcessBuilder(cmd)
            pb.directory(filesDir)
            pb.redirectErrorStream(true)
            proc = pb.start()
            sendStatus("Aether در حال اجرا...")
            Thread.sleep(3000)

            // Build VPN interface routing ALL traffic
            val builder = Builder()
                .setSession("MAHDI VPN")
                .addAddress("10.10.10.2", 24)
                .addDnsServer("1.1.1.1")
                .addRoute("0.0.0.0", 0)
                .establish()

            if (builder == null) {
                sendStatus("خطا: ساخت تونل VPN ناموفق")
                return
            }
            vpnInterface = builder
            sendStatus("متصل ✅ — کل ترافیک از طریق MAHDI VPN")

            proc?.inputStream?.bufferedReader()?.use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    Log.d("MAHDI_VPN", line)
                }
            }
            sendStatus("قطع شد")
        } catch (e: Exception) {
            sendStatus("خطا: ${e.message ?: e.javaClass.simpleName}")
            Log.e("MAHDI_VPN", "vpn err", e)
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
        proc?.destroy()
        thread?.interrupt()
        try { vpnInterface?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
