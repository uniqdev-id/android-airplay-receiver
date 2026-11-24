package com.uniq.airplay.util

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Standby-screen network info (SPEC §5F.1). */
object NetInfo {

    /** First globally reachable IPv4 address, or null. */
    @Suppress("DEPRECATION")
    fun localIpv4(): String? {
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
            if (!iface.isUp || iface.isLoopback || iface.isVirtual) return@forEach
            val name = iface.name.lowercase()
            // Skip virtual / tunnel interfaces Android keeps around.
            if (name.startsWith("lo") || name.startsWith("vnet") || name.startsWith("dummy") ||
                name.startsWith("tun") || name.startsWith("ip") || name.startsWith("wlan0_")
            ) {
                return@forEach
            }
            iface.inetAddresses?.toList()?.forEach { addr ->
                if (addr is Inet4Address && !addr.isLoopbackAddress && !isLinkLocal(addr)) {
                    @Suppress("DEPRECATION")
                    return addr.hostAddress
                }
            }
        }
        return null
    }

    private fun isLinkLocal(addr: InetAddress): Boolean {
        val a = addr.address
        return a[0].toInt() == 169 && a[1].toInt() == 254
    }

    /** "Wi-Fi", "Wired", "Cellular" or "Unknown". */
    fun networkTypeLabel(context: Context): String {
        @Suppress("DEPRECATION")
        val info = (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
            .activeNetworkInfo ?: return "Unknown"
        return when (info.type) {
            ConnectivityManager.TYPE_WIFI -> "Wi-Fi"
            ConnectivityManager.TYPE_ETHERNET -> "Wired"
            ConnectivityManager.TYPE_MOBILE -> "Cellular"
            else -> "Unknown"
        }
    }
}
