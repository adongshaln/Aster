package com.adong.adchat.data

import android.annotation.SuppressLint
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/** When true, HTTPS no longer checks the server certificate or hostname. Default is off. */
object NetworkTrust {
    @Volatile
    var allowInsecureCertificates: Boolean = false
}

@SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
internal fun OkHttpClient.Builder.applyNetworkTrust(): OkHttpClient.Builder {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as java.security.KeyStore?)
    val system = factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    val trust = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            if (!NetworkTrust.allowInsecureCertificates) system.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (!NetworkTrust.allowInsecureCertificates) system.checkServerTrusted(chain, authType)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    }
    val context = SSLContext.getInstance("TLS")
    context.init(null, arrayOf<TrustManager>(trust), SecureRandom())
    val platformVerifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
    return sslSocketFactory(context.socketFactory, trust).hostnameVerifier { hostname, session ->
        NetworkTrust.allowInsecureCertificates || platformVerifier.verify(hostname, session)
    }
}
