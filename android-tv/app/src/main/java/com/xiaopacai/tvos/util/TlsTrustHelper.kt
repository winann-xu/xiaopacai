// [android-tv] 工具类 — TLS 信任辅助（Android 5.1 兼容）
//
// 背景（真机实测根因）：
//   小米电视为 Android 5.1.1，系统信任库共 162 个根证书，包含老旧的
//   DST Root CA X3，但**不含 ISRG Root X1 / X2**。而 xpc.winann.com 的证书链是
//   leaf → Let's Encrypt YE1 → ISRG Root YE → ISRG Root X2（ISRG Root X1 交叉签名），
//   因此握手报 SSLHandshakeException: Trust anchor for certification path not found，
//   导致 TV 端所有 HTTPS 请求（登录、心跳同步）全部失败。
//
// 做法：
//   把 Let's Encrypt 的公开根证书（ISRG Root X1 / X2）随包内置到 res/raw，
//   与系统默认信任库**合并**后使用。注意这不是"信任一切"：
//   仍执行完整的 X.509 证书链校验，只是补上旧系统缺失的两个公开根 CA。
//   新系统（已内置 ISRG 根）走系统信任库即可，结果一致。
//
// 维护：Let's Encrypt 换根时需要同步更新 res/raw 下的证书。

package com.xiaopacai.tvos.util

import android.content.Context
import android.util.Log
import com.xiaopacai.tvos.R
import com.xiaopacai.tvos.XiaopacaiTVApp
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object TlsTrustHelper {

    private const val TAG = "TlsTrustHelperTV"

    /** 随包内置的根证书资源（Let's Encrypt ISRG 根） */
    private val BUNDLED_ROOTS = intArrayOf(R.raw.isrg_root_x1, R.raw.isrg_root_x2)

    @Volatile
    private var cached: X509TrustManager? = null

    /**
     * 返回「系统默认 + 内置根」合并后的 X509TrustManager；构建失败时返回 null（调用方回退系统默认）
     */
    @Synchronized
    fun mergedTrustManager(): X509TrustManager? {
        cached?.let { return it }
        return try {
            val delegates = mutableListOf<X509TrustManager>()

            systemTrustManager()?.let { delegates.add(it) }
            bundledTrustManager(XiaopacaiTVApp.INSTANCE)?.let { delegates.add(it) }

            if (delegates.isEmpty()) return null

            CompositeX509TrustManager(delegates).also { cached = it }
        } catch (e: Exception) {
            Log.w(TAG, "构建合并信任库失败，回退系统默认", e)
            null
        }
    }

    /** 系统默认信任库 */
    private fun systemTrustManager(): X509TrustManager? = try {
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .firstOrNull()
    } catch (e: Exception) {
        Log.w(TAG, "读取系统信任库失败", e)
        null
    }

    /** 由内置根证书构建的信任库 */
    private fun bundledTrustManager(context: Context): X509TrustManager? = try {
        val factory = CertificateFactory.getInstance("X.509")
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        BUNDLED_ROOTS.forEachIndexed { index, resId ->
            context.resources.openRawResource(resId).use { input ->
                factory.generateCertificate(input)?.let {
                    keyStore.setCertificateEntry("isrg-root-$index", it)
                }
            }
        }
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .firstOrNull()
    } catch (e: Exception) {
        Log.w(TAG, "加载内置根证书失败", e)
        null
    }

    /**
     * 组合信任管理器：任一委托校验通过即通过
     */
    private class CompositeX509TrustManager(
        private val delegates: List<X509TrustManager>
    ) : X509TrustManager {

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            verify(chain, authType, client = true)

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            verify(chain, authType, client = false)

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            delegates.flatMap { it.acceptedIssuers.asList() }.toTypedArray()

        private fun verify(chain: Array<out X509Certificate>?, authType: String?, client: Boolean) {
            if (chain == null || chain.isEmpty()) throw CertificateException("证书链为空")
            var last: CertificateException? = null
            for (delegate in delegates) {
                try {
                    if (client) {
                        delegate.checkClientTrusted(chain, authType)
                    } else {
                        delegate.checkServerTrusted(chain, authType)
                    }
                    return
                } catch (e: CertificateException) {
                    last = e
                }
            }
            throw last ?: CertificateException("证书不受信任")
        }
    }
}
