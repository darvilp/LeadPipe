package org.schabi.newpipe.sync.subscriptions

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.java_websocket.SSLSocketChannel2
import org.java_websocket.WebSocket
import org.java_websocket.server.DefaultSSLWebSocketServerFactory
import java.net.Socket
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

internal class SubscriptionTls private constructor(
    private val certificate: X509Certificate,
    private val privateKey: PrivateKey
) {
    private val localPin = pin(certificate)

    private fun context(accept: (String) -> Boolean): SSLContext {
        val keys = object : X509ExtendedKeyManager() {
            override fun getCertificateChain(alias: String?) = if (alias == ALIAS) arrayOf(certificate) else null
            override fun getPrivateKey(alias: String?) = privateKey.takeIf { alias == ALIAS }
            override fun getClientAliases(type: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
            override fun getServerAliases(type: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
            override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
            override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
            override fun chooseEngineClientAlias(
                types: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?
            ) = ALIAS
            override fun chooseEngineServerAlias(
                type: String?, issuers: Array<out Principal>?, engine: SSLEngine?
            ) = ALIAS
        }
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()

            private fun verify(chain: Array<out X509Certificate>) {
                val peer = chain.firstOrNull() ?: throw CertificateException("Missing device certificate")
                peer.checkValidity()
                if (!accept(pin(peer))) throw CertificateException("Unrecognized device key")
            }

            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = verify(chain)
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = verify(chain)
        }
        return SSLContext.getInstance("TLS").apply { init(arrayOf(keys), arrayOf(trust), null) }
    }

    fun client(expectedPin: String?) = context { expectedPin == null || it == expectedPin }.socketFactory

    fun server(accept: (String) -> Boolean) = object : DefaultSSLWebSocketServerFactory(context(accept)) {
        override fun wrapChannel(channel: SocketChannel, key: SelectionKey): SSLSocketChannel2 {
            val engine = sslcontext.createSSLEngine()
            engine.useClientMode = false
            engine.needClientAuth = true
            return SSLSocketChannel2(channel, engine, exec, key)
        }
    }

    fun peerPin(socket: WebSocket): String =
        pin(socket.sslSession.peerCertificates.first() as X509Certificate)

    fun verificationCode(peerPin: String): String {
        val keys = listOf(localPin, peerPin).sorted().joinToString("/")
        return hash("LeadPipe subscriptions pairing v1/$keys".toByteArray(Charsets.UTF_8))
            .take(32).chunked(4).joinToString(" ")
    }

    companion object {
        private const val ALIAS = "leadpipe-subscription-sync-v1"

        @Synchronized
        fun load(): SubscriptionTls {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!store.containsAlias(ALIAS)) {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                    initialize(
                        KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                            .setCertificateSubject(X500Principal("CN=LeadPipe subscription sync"))
                            .build()
                    )
                    generateKeyPair()
                }
            }
            return SubscriptionTls(store.getCertificate(ALIAS) as X509Certificate, store.getKey(ALIAS, null) as PrivateKey)
        }

        private fun pin(certificate: X509Certificate) = hash(certificate.publicKey.encoded)
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
