package com.smbshare.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 网络工具类
 */
object NetworkUtils {

    /**
     * 检查是否连接 WiFi
     */
    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * 获取设备 WiFi IP 地址
     */
    fun getWifiIpAddress(): String? {
        try {
            // 收集所有 (接口名, 私有局域网 IPv4) 候选
            // 必须是私有网段, 排除蜂窝(rmnet)/VPN(tun) 等可路由公网地址
            val candidates = mutableListOf<Pair<String, String>>()
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                // 跳过 loopback / down / 虚拟点对点(VPN) 接口
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                val ifName = networkInterface.name ?: ""
                if (ifName.startsWith("tun") || ifName.startsWith("ppp") ||
                    ifName.startsWith("rmnet") || ifName.startsWith("ccmni")
                ) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address.isLoopbackAddress || address !is Inet4Address) continue
                    val host = address.hostAddress ?: continue
                    // 只接受 RFC1918 私有网段, 同时排除 link-local(169.254)
                    if (address.isSiteLocalAddress && !host.startsWith("169.254")) {
                        candidates.add(ifName to host)
                    }
                }
            }
            // 优先 wlan/eth 接口, 其次任意私有网段
            return candidates.firstOrNull {
                it.first.startsWith("wlan") || it.first.startsWith("eth") || it.first.startsWith("ap")
            }?.second ?: candidates.firstOrNull()?.second
        } catch (_: Exception) {
        }
        return null
    }

    /**
     * 从 WifiManager 获取 IP 地址 (备选)
     */
    fun getWifiIpFromManager(context: Context): String? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wifiManager.connectionInfo.ipAddress
            if (ip != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ip and 0xff,
                    ip shr 8 and 0xff,
                    ip shr 16 and 0xff,
                    ip shr 24 and 0xff
                )
            }
        } catch (_: Exception) {
        }
        return null
    }
}