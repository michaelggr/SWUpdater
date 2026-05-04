package com.swupdater.capture

import com.swupdater.util.AppLog
import org.msgpack.core.MessagePack
import org.msgpack.value.Value
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class GameDataParser {

    private val responseBuffer = ByteArrayOutputStream()
    private var expectedLength = 0
    private var responseBufferedData = ByteArray(0)

    var onDataParsed: ((String, Map<String, Any?>) -> Unit)? = null

    companion object {
        private const val TAG = "GameDataParser"
    }

    private val requestBuffer = ByteArrayOutputStream()
    private var requestExpectedLength = 0
    private var requestBufferedData = ByteArray(0)

    /**
     * 处理请求方向数据（客户端→服务器）
     * 主要用于提取会话密钥等元数据
     */
    @Synchronized
    fun processRequest(data: ByteArray, hostname: String) {
        requestBuffer.write(data)
        requestBufferedData = requestBuffer.toByteArray()

        while (requestBufferedData.isNotEmpty()) {
            if (requestExpectedLength == 0 && requestBufferedData.size >= 4) {
                requestExpectedLength = readUInt32BE(requestBufferedData, 0)
            }

            if (requestExpectedLength <= 0) {
                requestBuffer.reset()
                requestBufferedData = ByteArray(0)
                break
            }

            if (requestBufferedData.size >= requestExpectedLength + 4) {
                val payload = requestBufferedData.sliceArray(4 until requestExpectedLength + 4)
                requestBufferedData = requestBufferedData.sliceArray(requestExpectedLength + 4 until requestBufferedData.size)
                requestBuffer.reset()
                requestBuffer.write(requestBufferedData)
                requestExpectedLength = 0

                parseRequestPacket(payload, hostname)
            } else {
                break
            }
        }
    }

    /**
     * 解析请求包，提取会话密钥等元数据
     */
    private fun parseRequestPacket(payload: ByteArray, hostname: String) {
        try {
            val decryptedPayload = GameDecryptor.decrypt(payload)
            val unpacker = MessagePack.newDefaultUnpacker(ByteArrayInputStream(decryptedPayload))
            val value = unpacker.unpackValue()
            unpacker.close()

            val map = convertValue(value) as? Map<String, Any?> ?: return
            val command = map["command"] as? String ?: return

            AppLog.d(TAG, "收到请求命令: $command (来源: $hostname)")

            // 从登录请求中提取可能的加密密钥
            if (command == "HubUserLogin") {
                extractSessionKey(map)
            }
        } catch (_: Exception) {
            // 非游戏数据包，静默忽略
        }
    }

    /**
     * 从登录请求中提取会话密钥
     */
    private fun extractSessionKey(data: Map<String, Any?>) {
        try {
            val key = data["session_key"] as? String
            if (!key.isNullOrEmpty()) {
                AppLog.d(TAG, "发现会话密钥，长度: ${key.length}")
                // 尝试将密钥转为 AES-128 可用的 16 字节
                val keyBytes = deriveAesKey(key)
                val iv = keyBytes.copyOfRange(0, 16)
                GameDecryptor.setSessionKey(keyBytes, iv)
            }
        } catch (e: Exception) {
            AppLog.d(TAG, "提取会话密钥失败: ${e.message}")
        }
    }

    /**
     * 从游戏会话密钥派生 AES-128 密钥（16字节）
     * 优先尝试十六进制解码，失败则用 SHA-256 截取前16字节
     */
    private fun deriveAesKey(key: String): ByteArray {
        // 尝试十六进制解码
        if (key.length >= 32 && key.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return key.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        // 回退：SHA-256 哈希后取前16字节
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(key.toByteArray()).copyOfRange(0, 16)
    }

    @Synchronized
    fun processResponse(data: ByteArray, hostname: String) {
        responseBuffer.write(data)
        responseBufferedData = responseBuffer.toByteArray()

        while (responseBufferedData.isNotEmpty()) {
            if (expectedLength == 0 && responseBufferedData.size >= 4) {
                expectedLength = readUInt32BE(responseBufferedData, 0)
            }

            if (expectedLength <= 0) {
                responseBuffer.reset()
                responseBufferedData = ByteArray(0)
                break
            }

            if (responseBufferedData.size >= expectedLength + 4) {
                val payload = responseBufferedData.sliceArray(4 until expectedLength + 4)
                responseBufferedData = responseBufferedData.sliceArray(expectedLength + 4 until responseBufferedData.size)
                responseBuffer.reset()
                responseBuffer.write(responseBufferedData)
                expectedLength = 0

                parsePacket(payload, hostname)
            } else {
                break
            }
        }
    }

    private fun readUInt32BE(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
    }

    private fun parsePacket(payload: ByteArray, hostname: String) {
        try {
            // 先尝试解密（部分游戏数据可能被加密）
            val decryptedPayload = GameDecryptor.decrypt(payload)

            val unpacker = MessagePack.newDefaultUnpacker(ByteArrayInputStream(decryptedPayload))
            val value = unpacker.unpackValue()
            unpacker.close()

            val map = convertValue(value) as? Map<String, Any?> ?: return
            val command = map["command"] as? String ?: return

            AppLog.d(TAG, "收到游戏命令: $command (来源: $hostname)")

            val handler = GameCommandMapper.getHandler(command)
            if (handler != null) {
                val result = handler.handle(map)
                onDataParsed?.invoke(command, result)
            }
        } catch (e: Exception) {
            // 非游戏数据包或解密失败，静默忽略
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun convertValue(value: Value): Any? {
        return when {
            value.isNilValue -> null
            value.isBooleanValue -> value.asBooleanValue().boolean
            value.isIntegerValue -> value.asIntegerValue().toLong()
            value.isFloatValue -> value.asFloatValue().toDouble()
            value.isStringValue -> value.asStringValue().asString()
            value.isBinaryValue -> value.asBinaryValue().asByteArray().toList()
            value.isArrayValue -> {
                val array = value.asArrayValue()
                array.map { convertValue(it) }
            }
            value.isMapValue -> {
                val mapValue = value.asMapValue()
                val result = mutableMapOf<String, Any?>()
                val entries = mapValue.entrySet()
                for (entry in entries) {
                    val k = entry.key
                    val v = entry.value
                    val key = if (k.isStringValue) k.asStringValue().asString() else k.toString()
                    result[key] = convertValue(v)
                }
                result
            }
            else -> value.toString()
        }
    }

    @Synchronized
    fun reset() {
        responseBuffer.reset()
        responseBufferedData = ByteArray(0)
        expectedLength = 0
        requestBuffer.reset()
        requestBufferedData = ByteArray(0)
        requestExpectedLength = 0
        GameDecryptor.clearSessionKey()
    }
}
