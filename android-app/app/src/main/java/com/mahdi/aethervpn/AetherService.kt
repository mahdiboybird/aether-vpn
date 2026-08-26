package com.mahdi.aethervpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class AetherService : Service() {

    private var proc: Process? = null
    private var thread: Thread? = null

    companion object {
        const val CHANNEL = "aether_vpn"
        const val ACTION_STATUS = "com.mahdi.aethervpn.STATUS"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification("در حال راه‌اندازی..."))
        thread = Thread { runBinary() }
        thread?.start()
        return START_STICKY
    }

    private fun runBinary() {
        try {
            val nativeDir = applicationInfo.nativeLibraryDir
            val bin = File(filesDir, "aether")
            val src = File(nativeDir, "libaether.so")
            src.copyTo(bin, overwrite = true)
            bin.setExecutable(true, false)

            val cmd = listOf(
                bin.absolutePath,
                "--wg",
                "--scan", "balanced",
                "--bind", "127.0.0.1:1819"
            )
            val pb = ProcessBuilder(cmd)
            pb.directory(filesDir)
            pb.redirectErrorStream(true)
            proc = pb.start()

            sendStatus("متصل ✅ — پروکسی روی 127.0.0.1:1819")
            updateNotif("متصل ✅")

            val reader = BufferedReader(InputStreamReader(proc!!.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                Log.d("AetherVPN", line)
            }
            sendStatus("قطع شد")
        } catch (e: Exception) {
            sendStatus("خطا: ${e.message ?: e.javaClass.simpleName}")
            Log.e("AetherVPN", "err", e)
        }
    }

    private fun sendStatus(s: String) {
        val i = Intent(ACTION_STATUS)
        i.putExtra("status", s)
        sendBroadcast(i)
    }

    private fun updateNotif(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Aether VPN — Mahdi")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL, "Aether VPN", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        proc?.destroy()
        thread?.interrupt()
        super.onDestroy()
    }
}
