package com.smbshare.utils

import java.security.MessageDigest

/**
 * MD5 校验工具类
 */
object Md5Utils {

    /**
     * 计算字符串的 MD5
     */
    fun md5(input: String): String {
        return hashToHex(MessageDigest.getInstance("MD5").digest(input.toByteArray()))
    }

    /**
     * 计算字节数组的 MD5
     */
    fun md5(input: ByteArray): String {
        return hashToHex(MessageDigest.getInstance("MD5").digest(input))
    }

    private fun hashToHex(digest: ByteArray): String {
        val hexChars = "0123456789abcdef"
        val result = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val i = byte.toInt()
            result.append(hexChars[i shr 4 and 0x0f])
            result.append(hexChars[i and 0x0f])
        }
        return result.toString()
    }
}