package com.swupdater.capture

import android.util.Log
import com.swupdater.util.AppLog
import org.msgpack.core.MessagePack
import org.msgpack.value.Value
import java.io.ByteArrayInputStream

class GameDataParser {

    private var buffer = ByteArray(0)
    private var expectedLength = 0

    var onDataParsed: ((String, Map<String, Any?>) -> Unit)? = null

    companion object {
        private const val TAG = "GameDataParser"
    }

    @Synchronized
    fun processResponse(data: ByteArray, hostname: String) {
        buffer += data

        while (buffer.isNotEmpty()) {
            if (expectedLength == 0 && buffer.size >= 4) {
                expectedLength = readUInt32BE(buffer, 0)
            }

            if (expectedLength <= 0) {
                buffer = ByteArray(0)
                break
            }

            if (buffer.size >= expectedLength + 4) {
                val payload = buffer.sliceArray(4 until expectedLength + 4)
                buffer = buffer.sliceArray(expectedLength + 4 until buffer.size)
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
            val unpacker = MessagePack.newDefaultUnpacker(ByteArrayInputStream(payload))
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
            // 非游戏数据包，静默忽略
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
                for ((k, v) in mapValue) {
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
        buffer = ByteArray(0)
        expectedLength = 0
    }
}
