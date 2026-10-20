package com.sternpaul.streamguide.data

import com.sternpaul.streamguide.core.ProxyErrors
import com.sternpaul.streamguide.core.ProxySettings
import com.sternpaul.streamguide.core.ProxyType
import com.sternpaul.streamguide.core.ProxyValidation
import java.io.IOException
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * The one HTTP client of the app. Playlist, EPG, app updates, channel logos and playback all
 * go through it, so the proxy setting covers every request StreamGuide makes.
 *
 * With the proxy enabled, host names are resolved by the proxy (HTTP CONNECT or SOCKS5 with
 * an unresolved address), and a proxy that cannot be reached or rejects the login fails the
 * request; there is no direct fallback.
 */
class HttpClients(
    initial: ProxySettings,
    val socksSupported: Boolean = true,
    private val dns: Dns = Dns.SYSTEM
) {
    @Volatile private var settings: ProxySettings = initial
    @Volatile var client: OkHttpClient = build(initial)
        private set
    private val lock = Any()

    /** Resolves the current client per request, so open players and loaders follow a change. */
    val callFactory: Call.Factory = Call.Factory { request -> client.newCall(request) }

    init { ProxyPasswordAuthenticator.allow(initial) }

    /** Switches every client to [new] without a restart. Running requests are cancelled. */
    fun apply(new: ProxySettings) {
        val old: OkHttpClient
        synchronized(lock) {
            old = client
            ProxyPasswordAuthenticator.allow(new)
            ProxyPasswordAuthenticator.revoke(settings)
            settings = new
            client = build(new)
        }
        // Nothing may keep using a connection opened under the previous setting.
        old.dispatcher.cancelAll()
        old.connectionPool.evictAll()
    }

    /** A client with its own timeouts on top of the current shared one, rebuilt when that changes. */
    fun derived(customize: OkHttpClient.Builder.() -> Unit): () -> OkHttpClient {
        var base: OkHttpClient? = null
        var derived: OkHttpClient? = null
        return {
            synchronized(lock) {
                val current = client
                if (base !== current) { base = current; derived = current.newBuilder().apply(customize).build() }
                derived!!
            }
        }
    }

    /** Runs [block] with a client for [candidate], which need not be saved yet. */
    fun <T> withTestClient(candidate: ProxySettings, block: (OkHttpClient) -> T): T {
        val test = build(candidate).newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
        ProxyPasswordAuthenticator.allow(candidate)
        try {
            return block(test)
        } finally {
            ProxyPasswordAuthenticator.revoke(candidate)
            test.connectionPool.evictAll()
        }
    }

    private fun build(proxy: ProxySettings): OkHttpClient {
        val builder = OkHttpClient.Builder().connectionPool(ConnectionPool()).dns(dns)
        if (!proxy.enabled) return builder.build()
        // Enabled but unusable: refuse every request rather than going direct.
        ProxyValidation.problem(proxy, socksSupported)?.let { problem ->
            return builder.proxy(Proxy.NO_PROXY).addInterceptor { throw ProxyErrors.notConfigured(problem) }.build()
        }
        // Unresolved, so the proxy's own name is looked up only when connecting, off the main thread.
        val address = InetSocketAddress.createUnresolved(proxy.host, proxy.port)
        builder.proxy(Proxy(if (proxy.type == ProxyType.HTTP) Proxy.Type.HTTP else Proxy.Type.SOCKS, address))
        if (proxy.type == ProxyType.HTTP && proxy.hasCredentials) {
            val credentials = Credentials.basic(proxy.username, proxy.password)
            builder.proxyAuthenticator { _, response ->
                // Sent once (also preemptively for CONNECT); a second 407 means the login is wrong.
                if (response.request.header("Proxy-Authorization") != null) null
                else response.request.newBuilder().header("Proxy-Authorization", credentials).build()
            }
        }
        builder.addInterceptor(FailClosed(proxy))
        return builder.build()
    }

    private class FailClosed(private val proxy: ProxySettings) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val response = try {
                chain.proceed(chain.request())
            } catch (error: IOException) {
                throw ProxyErrors.classify(error, proxy)
            }
            if (response.code == 407) {
                response.close()
                throw ProxyErrors.loginFailed()
            }
            return response
        }
    }
}

/**
 * Java's SOCKS client asks the process-wide [java.net.Authenticator] for its login. This one
 * answers only for a configured SOCKS5 proxy's exact host and port, and for nothing else.
 */
object ProxyPasswordAuthenticator : java.net.Authenticator() {
    private val allowed = CopyOnWriteArrayList<ProxySettings>()
    @Volatile private var installed = false

    fun allow(settings: ProxySettings) {
        if (!settings.enabled || settings.type != ProxyType.SOCKS5 || !settings.hasCredentials) return
        allowed += settings
        if (!installed) synchronized(this) { if (!installed) { setDefault(this); installed = true } }
    }

    fun revoke(settings: ProxySettings) { allowed.remove(settings) }

    override fun getPasswordAuthentication(): PasswordAuthentication? {
        // Java's SOCKS code asks with protocol "SOCKS5" and requestor type SERVER; other
        // proxy prompts use type PROXY. Origin servers asking for a login get nothing.
        val forProxy = requestorType == RequestorType.PROXY || requestingProtocol.orEmpty().startsWith("SOCKS", ignoreCase = true)
        if (!forProxy) return null
        val host = requestingHost ?: return null
        val match = allowed.lastOrNull { it.host.equals(host, ignoreCase = true) && it.port == requestingPort } ?: return null
        return PasswordAuthentication(match.username, match.password.toCharArray())
    }
}
