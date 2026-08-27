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
        const val CHANNEL = "mahdi_vpn"
        const val ACTION_STATUS = "com.mahdi.aethervpn.STATUS"
        const val SO_NAME = "libmahdi.so"
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

    private fun findBinary(): File? {
        // 1. check nativeLibraryDir and all its subdirs
        val libRoot = File(applicationInfo.nativeLibraryDir)
        Log.d("MAHDI_VPN", "nativeLibDir: ${libRoot.absolutePath} exists=${libRoot.exists()}")
        libRoot.listFiles()?.forEach { f ->
            Log.d("MAHDI_VPN", "  entry: ${f.name} dir=${f.isDirectory}")
        }
        // recursive search for libmahdi.so
        fun scan(dir: File): File? {
            dir.listFiles()?.forEach { f ->
                if (f.isDirectory) {
                    scan(f)?.let { return it }
                } else if (f.name == SO_NAME) {
                    return f
                }
            }
            return null
        }
        val found = scan(libRoot)
        if (found != null) return found

        // 2. fallback: try common paths
        val candidates = listOf(
            File(libRoot, SO_NAME),
            File("/data/app/${packageName}/lib/arm64/$SO_NAME"),
            File(filesDir, "mahdi")
        )
        return candidates.firstOrNull { it.exists() }
    }

    private fun runBinary() {
        try {
            val bin = findBinary()
            if (bin == null) {
                sendStatus("خطا: باینری یافت نشد")
                Log.e("MAHDI_VPN", "binary not found")
                return
            }
            Log.d("MAHDI_VPN", "executing: ${bin.absolutePath}")
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
                Log.d("MAHDI_VPN", line ?: "")
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

    private fun updateNotif(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, buildNotification(text))
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

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL, "MAHDI VPN", NotificationManager.IMPORTANCE_LOW
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
