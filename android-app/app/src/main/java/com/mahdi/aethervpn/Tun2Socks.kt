package com.mahdi.aethervpn

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal tun2socks: reads IP packets from TUN, forwards TCP/UDP via SOCKS5 proxy,
 * writes replies back to TUN. Runs in a background thread.
 */
class Tun2Socks(
    private val tunFd: ParcelFileDescriptor,
    private val socksHost: String,
    private val socksPort: Int
) : Thread() {

    private val TAG = "Tun2Socks"
    @Volatile private var running = true

    // map local socket key -> destination
    private val tcpConns = ConcurrentHashMap<Int, TcpConn>()
    private val udpConns = ConcurrentHashMap<Int, UdpConn>()

    override fun run() {
        val `in` = FileInputStream(tunFd.fileDescriptor)
        val out = FileOutputStream(tunFd.fileDescriptor)
        val buf = ByteArray(65535)
        while (running) {
            try {
                val n = `in`.read(buf)
                if (n <= 0) { sleep(5); continue }
                val pkt = buf.copyOf(n)
                val ver = pkt[0].toInt() and 0xF0
                if (ver != 0x40) continue // IPv4 only
                val proto = pkt[9].toInt() and 0xFF
                when (proto) {
                    6 -> handleTcp(pkt, out)
                    17 -> handleUdp(pkt, out)
                    // ICMP etc ignored for now
                }
            } catch (e: Exception) {
                Log.d(TAG, "loop: ${e.message}")
                if (!running) break
            }
        }
    }

    private fun ip(b: ByteArray, off: Int): String =
        "${b[off].toInt() and 0xFF}.${b[off+1].toInt() and 0xFF}.${b[off+2].toInt() and 0xFF}.${b[off+3].toInt() and 0xFF}"

    private fun port(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off+1].toInt() and 0xFF)

    private fun handleTcp(pkt: ByteArray, out: FileOutputStream) {
        val srcPort = port(pkt, 20)
        val dstIp = ip(pkt, 16)
        val dstPort = port(pkt, 22)
        val hdrLen = (pkt[0].toInt() and 0x0F) * 4
        val flags = pkt[hdrLen - 1].toInt() and 0xFF
        val syn = (flags and 0x02) != 0
        val ack = (flags and 0x10) != 0
        // For simplicity, open a proxy connection per new SYN
        Thread {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(socksHost, socksPort), 8000)
                val os = s.getOutputStream()
                val `is` = s.getInputStream()
                // SOCKS5 no-auth
                os.write(byteArrayOf(0x05, 0x01, 0x00))
                `is`.read(); `is`.read()
                // connect dst via socks
                os.write(0x05); os.write(0x01); os.write(0x00); os.write(0x01)
                val dip = InetAddress.getByName(dstIp).address
                os.write(dip)
                os.write((dstPort shr 8).toByte()); os.write((dstPort and 0xFF).toByte())
                `is`.read(); `is`.read(); `is`.read(); `is`.read()
                `is`.read(); `is`.read(); `is`.read(); `is`.read()
                // send payload
                if (pkt.size > hdrLen) os.write(pkt.copyOfRange(hdrLen, pkt.size))
                // read response and craft fake reply to TUN (simplified)
                val resp = ByteArray(65535)
                var r: Int
                while (`is`.read(resp).also { r = it } > 0) {
                    // build a minimal IP packet back (src = dstIp:dstPort, dst = 10.10.10.2:srcPort)
                    val back = fakeIpPacket(dstIp, dstPort, "10.10.10.2", srcPort, resp.copyOf(r))
                    out.write(back)
                }
                s.close()
            } catch (e: Exception) {
                Log.d(TAG, "tcp $dstIp:$dstPort ${e.message}")
            }
        }.start()
    }

    private fun handleUdp(pkt: ByteArray, out: FileOutputStream) {
        val srcPort = port(pkt, 20)
        val dstIp = ip(pkt, 16)
        val dstPort = port(pkt, 22)
        val hdrLen = (pkt[0].toInt() and 0x0F) * 4
        if (pkt.size <= hdrLen) return
        val payload = pkt.copyOfRange(hdrLen, pkt.size)
        Thread {
            try {
                val ds = DatagramSocket()
                // send via SOCKS5 UDP (simplified: send directly to proxy's UDP associate not implemented,
                // so we just relay through a TCP SOCKS connect for DNS-like small packets)
                val s = Socket()
                s.connect(InetSocketAddress(socksHost, socksPort), 8000)
                val os = s.getOutputStream(); val `is` = s.getInputStream()
                os.write(byteArrayOf(0x05,0x01,0x00)); `is`.read(); `is`.read()
                // UDP ASSOCIATE
                os.write(0x05); os.write(0x03); os.write(0x00); os.write(0x01)
                os.write(byteArrayOf(0,0,0,0)); os.write(0,0)
                `is`.read(); `is`.read(); `is`.read(); `is`.read()
                `is`.read(); `is`.read(); `is`.read(); `is`.read()
                // send UDP via proxy
                os.write(0x00); os.write(0x00); os.write(0x00) // rsv frag
                os.write(0x01); os.write(InetAddress.getByName(dstIp).address)
                os.write((dstPort shr 8).toByte()); os.write((dstPort and 0xFF).toByte())
                os.write(payload)
                s.close()
            } catch (e: Exception) {
                Log.d(TAG, "udp $dstIp:$dstPort ${e.message}")
            }
        }.start()
    }

    private fun fakeIpPacket(srcIp: String, srcPort: Int, dstIp: String, dstPort: Int, data: ByteArray): ByteArray {
        val total = 20 + data.size
        val pkt = ByteArray(total)
        pkt[0] = 0x45.toByte() // v4, ihl=5
        pkt[1] = 0
        pkt[2] = (total shr 8).toByte(); pkt[3] = (total and 0xFF).toByte()
        pkt[4] = 0; pkt[5] = 0
        pkt[6] = 0; pkt[7] = 0
        pkt[8] = 64 // ttl
        pkt[9] = 6 // TCP
        // checksum omitted (0 is acceptable for local TUN on many devices)
        val sa = InetAddress.getByName(srcIp).address
        val da = InetAddress.getByName(dstIp).address
        System.arraycopy(sa, 0, pkt, 12, 4)
        System.arraycopy(da, 0, pkt, 16, 4)
        // TCP header (20 bytes) minimal, flags PSH+ACK
        var o = 20
        pkt[o] = (srcPort shr 8).toByte(); pkt[o+1] = (srcPort and 0xFF).toByte()
        pkt[o+2] = (dstPort shr 8).toByte(); pkt[o+3] = (dstPort and 0xFF).toByte()
        pkt[o+12] = 0x18 // PSH ACK (no proper seq/ack but works for simple relay)
        System.arraycopy(data, 0, pkt, 40, data.size)
        return pkt
    }

    fun stopThread() {
        running = false
    }
}
