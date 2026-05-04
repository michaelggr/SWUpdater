package com.swupdater.capture

import com.swupdater.util.AppLog
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 游戏数据解密器
 * 参考 sw-exporter 的 smon_decryptor 实现
 *
 * 魔灵召唤使用自定义加密保护传输数据：
 * - 请求方向：客户端→服务器，使用请求密钥加密
 * - 响应方向：服务器→客户端，使用响应密钥加密
 *
 * 加密方式：AES-128-CBC
 * 密钥来源：从登录响应中提取，或使用默认密钥
 */
object GameDecryptor {

    private const val TAG = "GameDecryptor"

    // AES-128-CBC 密钥和IV，均为16字节（32个十六进制字符）
    private const val DEFAULT_KEY = "b8e0a8d0e0a8b0e0a8d0e0a8b0e0a8d0"
    private const val DEFAULT_IV = "a8b0e0a8d0e0a8b0e0a8d0e0a8b0e0a8"

    private var sessionKey: ByteArray? = null
    private var sessionIv: ByteArray? = null

    /**
     * 设置从登录响应获取的会话密钥
     */
    fun setSessionKey(key: ByteArray, iv: ByteArray) {
        sessionKey = key
        sessionIv = iv
        AppLog.i(TAG, "会话密钥已更新")
    }

    /**
     * 清除会话密钥
     */
    fun clearSessionKey() {
        sessionKey = null
        sessionIv = null
    }

    /**
     * 尝试解密数据
     * 如果数据未加密（明文 Msgpack），则原样返回
     */
    fun decrypt(data: ByteArray): ByteArray {
        // 先尝试不解密直接使用（大部分场景数据是明文 Msgpack）
        if (isLikelyMsgpack(data)) {
            return data
        }

        // 尝试用会话密钥解密
        sessionKey?.let { key ->
            sessionIv?.let { iv ->
                val decrypted = tryAesDecrypt(data, key, iv)
                if (decrypted != null && isLikelyMsgpack(decrypted)) {
                    AppLog.d(TAG, "使用会话密钥解密成功")
                    return decrypted
                }
            }
        }

        // 尝试用默认密钥解密
        val defaultKey = hexToBytes(DEFAULT_KEY)
        val defaultIv = hexToBytes(DEFAULT_IV)
        val decrypted = tryAesDecrypt(data, defaultKey, defaultIv)
        if (decrypted != null && isLikelyMsgpack(decrypted)) {
            AppLog.d(TAG, "使用默认密钥解密成功")
            return decrypted
        }

        // 解密失败，返回原始数据让上层处理
        AppLog.w(TAG, "数据解密失败，尝试原始解析")
        return data
    }

    /**
     * AES-128-CBC 解密
     */
    private fun tryAesDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        return try {
            val secretKey = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(iv)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            cipher.doFinal(data)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 检测数据是否像 Msgpack 格式
     * Msgpack Map 以 0x80-0x8F 或 0xDE/0xDF 开头
     * Msgpack Array 以 0x90-0x9F 或 0xDC/0xDD 开头
     * Msgpack String 以 0xA0-0xBF 或 0xD9/0xDA/0xDB 开头
     */
    private fun isLikelyMsgpack(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        val firstByte = data[0].toInt() and 0xFF
        return firstByte in 0x80..0xBF ||
                firstByte == 0xDE || firstByte == 0xDF ||
                firstByte == 0xDC || firstByte == 0xDD ||
                firstByte == 0xD9 || firstByte == 0xDA || firstByte == 0xDB
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
