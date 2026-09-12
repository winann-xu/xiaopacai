// [android-tv] P2P 客户端身份证书（mTLS）
// 小趴菜 TVOS 版
//
// 为什么需要它：Web 服务端 P2P 监听强制要求客户端证书（ClientCertificateRequired=true），
// 并以「证书 SHA-256 指纹」作为设备身份锚点（devices.cert_fingerprint 固定比对）。
// 因此电视必须先有稳定的身份证书，指纹上报给服务端后，P2P 握手才能通过。
//
// 复刻手机版 core/p2p/P2PConnectionService.getOrCreateClientCertificate()：
//   EC P-256 + BouncyCastle 自签名（clientAuth EKU）→ PKCS12 落盘（files/p2p_client.pfx）
//   不使用 AndroidKeyStore（Conscrypt 握手签名路径与 AndroidKeyStore 密钥不兼容）。
//
// 电视端差异：不依赖应用级单例，直接接收 Context（filesDir），便于单元测试与多进程复用。

package com.xiaopacai.tvos.p2p

import android.content.Context
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import javax.net.ssl.KeyManagerFactory

object TvP2pIdentity {

    private const val TAG = "TvP2pIdentity"
    private const val PFX_NAME = "p2p_client.pfx"
    private const val PWD_NAME = "p2p_client.pwd"
    private const val KEY_ALIAS = "p2p_client"

    private var cachedFingerprint: String? = null

    /** 证书指纹（SHA-256 十六进制小写 64 位）；首次调用会生成证书 */
    fun fingerprint(context: Context): String {
        cachedFingerprint?.let { return it }
        val (_, chain) = getOrCreate(context)
        val fp = sha256Hex(chain[0].encoded)
        cachedFingerprint = fp
        return fp
    }

    /** 供 SSLContext 使用的 KeyManagerFactory（内含本机客户端身份证书） */
    fun keyManagerFactory(context: Context): KeyManagerFactory {
        val (key, chain) = getOrCreate(context)
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(KEY_ALIAS, key, charArrayOf(), chain)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, charArrayOf())
        return kmf
    }

    private fun sha256Hex(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }

    /** 加载已持久化证书；缺失或损坏则重新生成并落盘 */
    @Synchronized
    private fun getOrCreate(context: Context): Pair<PrivateKey, Array<X509Certificate>> {
        val pfxFile = File(context.filesDir, PFX_NAME)
        val pwdFile = File(context.filesDir, PWD_NAME)

        if (pfxFile.exists() && pwdFile.exists()) {
            runCatching {
                val pwd = pwdFile.readText().trim()
                val ks = KeyStore.getInstance("PKCS12")
                FileInputStream(pfxFile).use { ks.load(it, pwd.toCharArray()) }
                val entry = ks.getEntry(KEY_ALIAS, KeyStore.PasswordProtection(pwd.toCharArray()))
                    as KeyStore.PrivateKeyEntry
                @Suppress("UNCHECKED_CAST")
                return Pair(entry.privateKey, entry.certificateChain as Array<X509Certificate>)
            }.onFailure { Log.w(TAG, "加载客户端身份证书失败，重新生成: ${it.message}") }
        }

        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val cert = selfSigned(keyPair)

        runCatching {
            val pwd = UUID.randomUUID().toString()
            val ks = KeyStore.getInstance("PKCS12")
            ks.load(null, null)
            ks.setKeyEntry(KEY_ALIAS, keyPair.private, pwd.toCharArray(), arrayOf(cert))
            FileOutputStream(pfxFile).use { ks.store(it, pwd.toCharArray()) }
            pwdFile.writeText(pwd)
        }.onFailure { Log.w(TAG, "持久化客户端身份证书失败: ${it.message}") }

        Log.i(TAG, "已生成客户端身份证书: ${sha256Hex(cert.encoded)}")
        return Pair(keyPair.private, arrayOf(cert))
    }

    private fun selfSigned(keyPair: java.security.KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val subject = X500Name("CN=Xiaopacai TV Client")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(now),
            java.util.Date(now - 86_400_000L),
            java.util.Date(now + 10L * 365 * 24 * 3600 * 1000),
            subject,
            keyPair.public
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
        builder.addExtension(
            Extension.extendedKeyUsage, true,
            ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth)
        )
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }
}
