package com.swupdater.capture

import android.content.Context
import com.swupdater.util.AppLog
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileWriter
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date

object CertificateManager {

    private const val TAG = "CertManager"
    private const val CA_ALIAS = "swupdater-ca"
    private const val CA_COMMON_NAME = "SWUpdater CA"
    private const val CA_ORG = "SWUpdater"
    private const val KEY_ALGORITHM = "RSA"
    private const val KEY_SIZE = 2048
    private const val SIGNATURE_ALGORITHM = "SHA256WithRSAEncryption"
    private const val CA_VALIDITY_YEARS = 10
    private const val LEAF_VALIDITY_YEARS = 1
    private const val SYSTEM_CERT_DIR = "/system/etc/security/cacerts"

    private val bcProvider = BouncyCastleProvider()

    private var caKeyPair: KeyPair? = null
    private var _caCertificate: X509Certificate? = null
    val caCertificate: X509Certificate? get() = _caCertificate
    private val leafCertCache = mutableMapOf<String, Pair<KeyPair, X509Certificate>>()

    init {
        Security.addProvider(bcProvider)
    }

    fun initialize(context: Context) {
        val certDir = getCertDir(context)
        val caCertFile = File(certDir, "ca-cert.pem")
        val caKeyFile = File(certDir, "ca-key.pem")

        if (caCertFile.exists() && caKeyFile.exists()) {
            loadExistingCa(caCertFile, caKeyFile)
        } else {
            generateRootCA(certDir)
        }
    }

    private fun loadExistingCa(caCertFile: File, caKeyFile: File) {
        try {
            val certHolder = org.bouncycastle.openssl.PEMParser(caCertFile.reader()).use { it.readObject() }
            val keyHolder = org.bouncycastle.openssl.PEMParser(caKeyFile.reader()).use { it.readObject() }

            _caCertificate = JcaX509CertificateConverter()
                .setProvider(bcProvider)
                .getCertificate(certHolder as X509CertificateHolder)

            val keyPair = org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter()
                .setProvider(bcProvider)
                .getKeyPair(keyHolder as org.bouncycastle.openssl.PEMKeyPair)
            caKeyPair = keyPair

            AppLog.i(TAG, "CA 证书加载成功")
        } catch (e: Exception) {
            AppLog.e(TAG, "CA 证书加载失败，重新生成", e)
            caCertFile.parentFile?.let { generateRootCA(it) }
        }
    }

    private fun generateRootCA(certDir: File) {
        try {
            certDir.mkdirs()

            val keyPairGenerator = KeyPairGenerator.getInstance(KEY_ALGORITHM, bcProvider)
            keyPairGenerator.initialize(KEY_SIZE, SecureRandom())
            val keyPair = keyPairGenerator.generateKeyPair()

            val subject = X500Name("CN=$CA_COMMON_NAME, O=$CA_ORG")
            val builder = JcaX509v3CertificateBuilder(
                subject,
                BigInteger.valueOf(System.currentTimeMillis()),
                Date(System.currentTimeMillis() - 86400000L),
                Date(System.currentTimeMillis() + CA_VALIDITY_YEARS * 365L * 86400000L),
                subject,
                keyPair.public
            )

            val extUtils = JcaX509ExtensionUtils()
            builder.addExtension(Extension.subjectKeyIdentifier, false, extUtils.createSubjectKeyIdentifier(keyPair.public))
            builder.addExtension(Extension.authorityKeyIdentifier, false, extUtils.createAuthorityKeyIdentifier(keyPair.public))
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
            builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))

            val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
                .setProvider(bcProvider)
                .build(keyPair.private)

            val certHolder = builder.build(signer)
            val cert = JcaX509CertificateConverter()
                .setProvider(bcProvider)
                .getCertificate(certHolder)

            caKeyPair = keyPair
            _caCertificate = cert

            savePem(File(certDir, "ca-cert.pem"), cert)
            savePem(File(certDir, "ca-key.pem"), keyPair)

            AppLog.i(TAG, "CA 根证书生成成功")
        } catch (e: Exception) {
            AppLog.e(TAG, "CA 根证书生成失败", e)
            AppLog.e(TAG, "CA 根证书生成失败: ${e.message}")
        }
    }

    private fun savePem(file: File, cert: X509Certificate) {
        JcaPEMWriter(FileWriter(file)).use { writer ->
            writer.writeObject(cert)
        }
    }

    private fun savePem(file: File, keyPair: KeyPair) {
        JcaPEMWriter(FileWriter(file)).use { writer ->
            writer.writeObject(keyPair.private)
        }
    }

    fun getLeafCertificate(hostname: String): LeafCertResult? {
        val kp = caKeyPair ?: return null
        val caCert = caCertificate ?: return null

        leafCertCache[hostname]?.let { (cachedKeyPair, cachedCert) ->
            return LeafCertResult(cachedKeyPair, cachedCert)
        }

        try {
            val keyPairGenerator = KeyPairGenerator.getInstance(KEY_ALGORITHM, bcProvider)
            keyPairGenerator.initialize(KEY_SIZE, SecureRandom())
            val leafKeyPair = keyPairGenerator.generateKeyPair()

            val issuer = X500Name(caCert.subjectX500Principal.name)
            val subject = X500Name("CN=$hostname")

            val builder = JcaX509v3CertificateBuilder(
                issuer,
                BigInteger.valueOf(SecureRandom().nextLong()).abs(),
                Date(System.currentTimeMillis() - 86400000L),
                Date(System.currentTimeMillis() + LEAF_VALIDITY_YEARS * 365L * 86400000L),
                subject,
                leafKeyPair.public
            )

            val extUtils = JcaX509ExtensionUtils()
            builder.addExtension(Extension.subjectKeyIdentifier, false, extUtils.createSubjectKeyIdentifier(leafKeyPair.public))
            builder.addExtension(Extension.authorityKeyIdentifier, false, extUtils.createAuthorityKeyIdentifier(kp.public))
            builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.dNSName, hostname)))
            builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
            builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))

            val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
                .setProvider(bcProvider)
                .build(kp.private)

            val certHolder = builder.build(signer)
            val leafCert = JcaX509CertificateConverter()
                .setProvider(bcProvider)
                .getCertificate(certHolder)

            leafCertCache[hostname] = leafKeyPair to leafCert

            AppLog.d(TAG, "叶子证书生成: $hostname")
            return LeafCertResult(leafKeyPair, leafCert)
        } catch (e: Exception) {
            AppLog.e(TAG, "叶子证书生成失败: $hostname", e)
            AppLog.e(TAG, "叶子证书生成失败: ${e.message}")
            return null
        }
    }

    fun getCaCertFile(context: Context): File {
        return File(getCertDir(context), "ca-cert.pem")
    }

    fun getCertSubjectHashOld(context: Context): String? {
        try {
            val caCert = caCertificate ?: return null
            val principal = caCert.subjectX500Principal
            val encoded = principal.encoded
            val digest = java.security.MessageDigest.getInstance("SHA1")
            val hash = digest.digest(encoded)
            val hashInt = ((hash[0].toInt() and 0xFF)
                or ((hash[1].toInt() and 0xFF) shl 8)
                or ((hash[2].toInt() and 0xFF) shl 16)
                or ((hash[3].toInt() and 0xFF) shl 24))
            return String.format("%08x", hashInt)
        } catch (e: Exception) {
            AppLog.e(TAG, "计算证书 hash 失败", e)
            return null
        }
    }

    fun isCaInstalledInSystem(context: Context): Boolean {
        val hash = getCertSubjectHashOld(context) ?: return false
        return executeRootCommand("ls $SYSTEM_CERT_DIR/${hash}.0").first
    }

    fun installCaToSystem(context: Context): Boolean {
        val caCertFile = getCaCertFile(context)
        if (!caCertFile.exists()) {
            AppLog.e(TAG, "CA 证书文件不存在")
            return false
        }

        val hash = getCertSubjectHashOld(context) ?: return false
        val targetPath = "$SYSTEM_CERT_DIR/${hash}.0"

        val commands = listOf(
            "mount -o rw,remount /system",
            "cp '${caCertFile.absolutePath}' $targetPath",
            "chmod 644 $targetPath",
            "mount -o ro,remount /system"
        )

        val (success, output) = executeRootCommands(commands)
        if (success) {
            AppLog.i(TAG, "CA 证书安装到系统目录成功: $targetPath")
        } else {
            AppLog.e(TAG, "CA 证书安装失败: $output")
        }
        return success
    }

    fun uninstallCaFromSystem(context: Context): Boolean {
        val hash = getCertSubjectHashOld(context) ?: return false
        val targetPath = "$SYSTEM_CERT_DIR/${hash}.0"

        val commands = listOf(
            "mount -o rw,remount /system",
            "rm -f $targetPath",
            "mount -o ro,remount /system"
        )

        val (success, output) = executeRootCommands(commands)
        if (success) {
            AppLog.i(TAG, "CA 证书从系统目录卸载成功")
        } else {
            AppLog.e(TAG, "CA 证书卸载失败: $output")
        }
        return success
    }

    private fun executeRootCommand(command: String): Pair<Boolean, String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val output = process.inputStream.bufferedReader().readText().trim()
            val error = process.errorStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            val result = if (exitCode == 0) output else error
            (exitCode == 0) to result
        } catch (e: Exception) {
            false to (e.message ?: "执行失败")
        }
    }

    private fun executeRootCommands(commands: List<String>): Pair<Boolean, String> {
        val combinedCommand = commands.joinToString(" && ")
        return executeRootCommand(combinedCommand)
    }

    private fun getCertDir(context: Context): File {
        return File(context.filesDir, "capture/certs")
    }

    data class LeafCertResult(
        val keyPair: KeyPair,
        val certificate: X509Certificate
    )
}
