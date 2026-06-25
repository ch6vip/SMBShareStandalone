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
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                // 跳过 loopback 和 down 的接口
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    // 只返回 IPv4 非 loopback 地址
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val hostAddress = address.hostAddress
                        // 常见 WiFi 接口名
                        if (networkInterface.name.startsWith("wlan") ||
                            networkInterface.name.startsWith("eth") ||
                            networkInterface.displayName.contains("wlan", ignoreCase = true)
                        ) {
                            return hostAddress
                        }
                        // 返回第一个非 link-local 的 IP
                        if (!hostAddress.startsWith("169.254")) {
                            return hostAddress
                        }
                    }
                }
            }
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