package com.mahdi.aethervpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * MAHDI VPN — full-device VPN (rooted).
 *
 *  libmahdi.so  (aether / WARP) -> SOCKS5 on 127.0.0.1:1819
 *  libhevtun.so (hev-socks5-tunnel, runs as root via Magisk su)
 *               -> creates tun0, routes ALL traffic (TCP + UDP such as
 *                  Telegram audio calls) through the SOCKS5 proxy.
 *
 * Requires a rooted device (Magisk) because creating tun0 needs root.
 */
class MahdiVpnService : VpnService() {

    private var aetherProc: Process? = null
    private var hevProc: Process? = null
    private var thread: Thread? = null
    private var running = true

    companion object {
        const val CHANNEL = "mahdi_vpn"
        const val ACTION_STATUS = "com.mahdi.aethervpn.STATUS"
        const val SO_MAHDI = "libmahdi.so"
        const val SO_HEV = "libhevtun.so"
        const val SOCKS_HOST = "127.0.0.1"
        const val SOCKS_PORT = 1819
        const val ROOT_HEV = "/data/local/tmp/hevtun"
        const val ROOT_CONF = "/data/local/tmp/hev.conf"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification("در حال راه‌اندازی VPN..."))
        thread = Thread { runVpn() }
        thread?.start()
        return START_STICKY
    }

    private fun findLib(name: String): File? {
        val libRoot = File(applicationInfo.nativeLibraryDir)
        fun scan(dir: File): File? {
            dir.listFiles()?.forEach { f ->
                if (f.isDirectory) scan(f)?.let { return it }
                else if (f.name == name) return f
            }
            return null
        }
        return scan(libRoot)
    }

    private fun runVpn() {
        try {
            val aether = findLib(SO_MAHDI)
            val hev = findLib(SO_HEV)
            if (aether == null || hev == null) {
                sendStatus("خطا: باینری‌های داخلی یافت نشد")
                return
            }

            // ---- 1. hev config: tun0 -> SOCKS5 127.0.0.1:1819 with UDP relay ----
            val confText =
                "tunnel:\n" +
                "  name: tun0\n" +
                "  mtu: 1500\n" +
                "  ipv4: 10.8.0.2\n" +
                "  ipv6: 'fc00::2'\n" +
                "\n" +
                "socks5:\n" +
                "  address: $SOCKS_HOST\n" +
                "  port: $SOCKS_PORT\n" +
                "  udp: 'udp'\n"

            sendStatus("در حال اجرای Aether (WARP)...")

            // ---- 2. start aether -> SOCKS5 on 1819 ----
            val pbAether = ProcessBuilder(aether.absolutePath,
                "--wg", "--scan", "balanced", "--bind", "$SOCKS_HOST:$SOCKS_PORT")
            pbAether.redirectErrorStream(true)
            aetherProc = pbAether.start()
            Thread.sleep(3500)

            sendStatus("Aether فعال — ساخت تونل...")

            // ---- 3. stage hev binary + config where root can reach them ----
            stageRoot(File(ROOT_HEV), hev)
            stageRootFile(ROOT_CONF, confText)

            // ---- 4. run hev as root: creates tun0 + routes all traffic ----
            val cmd = "chmod 755 $ROOT_HEV; " +
                "killall -9 hevtun 2>/dev/null; " +
                "$ROOT_HEV $ROOT_CONF"
            hevProc = rootRun(cmd)

            sendStatus("متصل ✅ — کال تلگرام باید کار کنه")
            readLogs(hevProc)

            while (running) Thread.sleep(1000)
        } catch (e: Exception) {
            sendStatus("خطا: ${e.message ?: e.javaClass.simpleName}")
            Log.e("MAHDI_VPN", "err", e)
        }
    }

    private fun readLogs(p: Process?) {
        p?.inputStream?.bufferedReader()?.use { r ->
            for (line in r.readLines()) Log.d("MAHDI_VPN", line)
        }
    }

    private fun stageRoot(dst: File, src: File) {
        dst.parentFile?.mkdirs()
        try {
            src.copyTo(dst, overwrite = true)
        } catch (e: Exception) {
            Log.e("MAHDI_VPN", "stage bin: ${e.message}")
        }
        dst.setExecutable(true, false)
    }

    private fun stageRootFile(path: String, content: String) {
        try {
            val f = File(path)
            FileOutputStream(f).use { it.write(content.toByteArray()) }
            f.setReadable(true, false)
        } catch (e: Exception) {
            Log.e("MAHDI_VPN", "stage conf: ${e.message}")
        }
    }

    private fun rootRun(cmd: String): Process =
        ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()

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
        try { rootRun("pkill -f hevtun") } catch (_: Exception) {}
        try { aetherProc?.destroy() } catch (_: Exception) {}
        try { hevProc?.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}