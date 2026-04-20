package com.swupdater.util

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * 文件校验工具
 * 支持 MD5 和 SHA256 校验
 */
object ChecksumUtil {

    private const val BUFFER_SIZE = 8192

    /**
     * 计算文件的 MD5 值
     */
    fun md5(file: File): String {
        return calculateHash(file, "MD5")
    }

    /**
     * 计算文件的 SHA256 值
     */
    fun sha256(file: File): String {
        return calculateHash(file, "SHA-256")
    }

    /**
     * 校验文件 MD5
     */
    fun verifyMd5(file: File, expectedMd5: String): Boolean {
        val actual = md5(file).lowercase()
        return actual == expectedMd5.lowercase()
    }

    /**
     * 校验文件 SHA256
     */
    fun verifySha256(file: File, expectedSha256: String): Boolean {
        val actual = sha256(file).lowercase()
        return actual == expectedSha256.lowercase()
    }

    /**
     * 通用哈希计算
     */
    private fun calculateHash(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 快速校验文件大小是否匹配
     */
    fun verifyFileSize(file: File, expectedSize: Long): Boolean {
        return file.exists() && file.length() == expectedSize
    }
}
