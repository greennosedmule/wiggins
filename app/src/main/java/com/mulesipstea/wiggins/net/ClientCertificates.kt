package com.mulesipstea.wiggins.net

import android.content.Context
import android.security.KeyChain
import okhttp3.OkHttpClient
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

class ClientCertificateException(message: String) : Exception(message)

/**
 * The client-certificate auth mode: Wiggins presents a certificate from Android
 * KeyChain to the reverse proxy (mTLS). The user picks or installs it through
 * the system's own KeyChain UI, which also grants Wiggins access to that alias.
 */
object ClientCertificates {
    /**
     * An OkHttp client that presents [alias]'s certificate and trusts the system CAs.
     * Blocking (KeyChain IPC): call off the main thread.
     */
    fun clientFor(context: Context, base: OkHttpClient, alias: String): OkHttpClient {
        val key = KeyChain.getPrivateKey(context, alias)
            ?: throw ClientCertificateException("Wiggins can't use certificate \"$alias\"; choose it again in settings")
        val chain = KeyChain.getCertificateChain(context, alias)
            ?: throw ClientCertificateException("Certificate \"$alias\" has no certificate chain")
        val trust = systemTrustManager()
        val tls = SSLContext.getInstance("TLS").apply {
            init(arrayOf(SingleKeyManager(alias, key, chain)), arrayOf(trust), null)
        }
        return base.newBuilder().sslSocketFactory(tls.socketFactory, trust).build()
    }

    private fun systemTrustManager(): X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()

    /** Offers one client key, whatever the server asks for. */
    private class SingleKeyManager(
        private val alias: String,
        private val key: PrivateKey,
        private val chain: Array<X509Certificate>,
    ) : X509ExtendedKeyManager() {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = alias
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
        override fun getCertificateChain(alias: String?) = chain
        override fun getPrivateKey(alias: String?) = key
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    }
}
