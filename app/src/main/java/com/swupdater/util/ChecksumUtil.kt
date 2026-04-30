﻿package com.swupdater.util

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object ChecksumUtil {

    private const val BUFFER_SIZE = 8192

    data class VerifyResult(
        val success: Boolean,
        val expected: String,
        val actual: String
    ) {
        val detail: String get() = if (success) "校验通过" else "校验失败: 期望=$expected, 实际=$actual"
    }

    fun md5(file: File): String {
        return calculateHash(file, "MD5")
    }

    fun sha256(file: File): String {
        return calculateHash(file, "SHA-256")
    }

    fun verifyMd5(file: File, expectedMd5: String): Boolean {
        return verifyMd5Detail(file, expectedMd5).success
    }

    fun verifySha256(file: File, expectedSha256: String): Boolean {
        return verifySha256Detail(file, expectedSha256).success
    }

    fun verifyMd5Detail(file: File, expectedMd5: String): VerifyResult {
        val actual = md5(file).lowercase()
        return VerifyResult(
            success = actual == expectedMd5.lowercase(),
            expected = expectedMd5.lowercase(),
            actual = actual
        )
    }

    fun verifySha256Detail(file: File, expectedSha256: String): VerifyResult {
        val actual = sha256(file).lowercase()
        return VerifyResult(
            success = actual == expectedSha256.lowercase(),
            expected = expectedSha256.lowercase(),
            actual = actual
        )
    }

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

    fun verifyFileSize(file: File, expectedSize: Long): Boolean {
        return file.exists() && file.length() == expectedSize
    }
}
