package com.localfirst.assistant.phone

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wakes the PC with a Wake-on-LAN magic packet. Only works on the same Wi-Fi
 * network as the PC: magic packets are LAN broadcasts and cannot cross the
 * internet or Tailscale, so this refuses to run on mobile data or VPN.
 */
object WakeOnLan {
    /** 12 hex digits in any common separator style, or null. */
    fun parseMac(mac: String): ByteArray? {
        val digits = mac.trim().replace(Regex("[:-]"), "").replace(" ", "")
        if (!digits.matches(Regex("[0-9A-Fa-f]{12}"))) return null
        return ByteArray(6) { i -> digits.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** 6 x 0xFF followed by 16 repetitions of the MAC. */
    fun magicPacket(mac: ByteArray): ByteArray {
        require(mac.size == 6) { "A MAC address is 6 bytes" }
        return ByteArray(6 + 16 * 6).apply {
            for (i in 0 until 6) this[i] = 0xFF.toByte()
            for (repeat in 0 until 16) mac.copyInto(this, 6 + repeat * 6)
        }
    }

    /** Broadcasts the packet on port 9; returns what happened in plain words. */
    suspend fun wake(context: Context, mac: String): String = withContext(Dispatchers.IO) {
        val address = parseMac(mac) ?: error("Enter the PC's MAC address as six hex pairs, like AA:BB:CC:DD:EE:FF.")
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val wifi = manager.getNetworkCapabilities(manager.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        if (!wifi) error("Join the same Wi-Fi as the PC first: wake packets can't cross mobile data or VPN.")
        val packet = magicPacket(address)
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName("255.255.255.255"), 9))
        }
        "Wake packet sent. Give the PC a minute to boot, then refresh."
    }
}
