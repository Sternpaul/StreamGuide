package com.sternpaul.streamguide.data

import com.sternpaul.streamguide.core.ProxyErrors
import com.sternpaul.streamguide.core.ProxyException
import com.sternpaul.streamguide.core.ProxySettings
import com.sternpaul.streamguide.core.ProxyType
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Authenticator
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Runs the real shared client against local fake proxies. Target host names end in .invalid,
 * so any attempt to resolve or reach them directly would fail and show up here.
 */
class HttpClientsProxyTest {
    private val closeables = ArrayList<AutoCloseable>()
    private val dns = RecordingDns()

    @After fun tearDown() { closeables.forEach { runCatching { it.close() } } }

    private fun <T : AutoCloseable> track(item: T): T = item.also { closeables += it }

    private fun http(settings: ProxySettings) = HttpClients(settings, dns = dns)

    private fun OkHttpClient.get(url: String): String =
        newCall(Request.Builder().url(url).build()).execute().use { "${it.code} ${it.body?.string()}" }

    private fun expectProxyFailure(reason: ProxyException.Reason, block: () -> Unit) {
        try {
            block()
            fail("request succeeded, expected $reason")
        } catch (error: IOException) {
            val proxy = ProxyErrors.find(error)
            assertNotNull("expected a proxy error, got $error", proxy)
            assertEquals(reason, proxy!!.reason)
        }
    }

    // --- HTTP proxy ---

    @Test fun httpProxyGetsAbsoluteUrlWithBasicLoginAndTargetIsNeverResolvedLocally() {
        val proxy = track(FakeHttpProxy("alice", "s3cret"))
        val client = http(ProxySettings(true, ProxyType.HTTP, "127.0.0.1", proxy.port, "alice", "s3cret")).client

        assertEquals("200 via-proxy", client.get("http://stream.example.invalid/live/1.ts"))

        val last = proxy.requests.last()
        assertEquals("GET http://stream.example.invalid/live/1.ts HTTP/1.1", last.line)
        assertEquals("Basic " + Base64.getEncoder().encodeToString("alice:s3cret".toByteArray()), last.proxyAuthorization)
        assertFalse("target looked up locally: ${dns.lookups}", dns.lookups.any { it.endsWith(".invalid") })
    }

    @Test fun httpsGoesThroughConnectByHostNameWithPreemptiveLogin() {
        val proxy = track(FakeHttpProxy("alice", "s3cret", connectStatus = 403))
        val client = http(ProxySettings(true, ProxyType.HTTP, "127.0.0.1", proxy.port, "alice", "s3cret")).client

        try { client.get("https://secure.example.invalid/playlist.m3u"); fail("tunnel was refused by the fake proxy") } catch (_: IOException) { }

        val connect = proxy.requests.first()
        assertEquals("CONNECT secure.example.invalid:443 HTTP/1.1", connect.line)
        assertNotNull("CONNECT carried no login", connect.proxyAuthorization)
        assertFalse(dns.lookups.any { it.endsWith(".invalid") })
    }

    @Test fun wrongHttpProxyLoginFailsAsLoginFailedForHttpAndHttps() {
        val proxy = track(FakeHttpProxy("alice", "s3cret"))
        val client = http(ProxySettings(true, ProxyType.HTTP, "127.0.0.1", proxy.port, "alice", "wrong")).client

        expectProxyFailure(ProxyException.Reason.LOGIN_FAILED) { client.get("http://stream.example.invalid/") }
        expectProxyFailure(ProxyException.Reason.LOGIN_FAILED) { client.get("https://secure.example.invalid/") }
    }

    // --- SOCKS5 ---

    @Test fun socks5ReceivesTheHostNameUnresolvedAndTheLoginOnlyGoesToTheProxy() {
        val proxy = track(FakeSocks5("bob", "pa55"))
        val client = http(ProxySettings(true, ProxyType.SOCKS5, "127.0.0.1", proxy.port, "bob", "pa55")).client

        assertEquals("200 via-socks", client.get("http://stream.example.invalid:8080/live/2.ts"))

        assertEquals(listOf("domain stream.example.invalid:8080"), proxy.connects)
        assertEquals(listOf("bob:pa55"), proxy.logins)
        assertTrue("OkHttp resolved names itself: ${dns.lookups}", dns.lookups.isEmpty())
    }

    @Test fun wrongSocks5LoginFailsAsLoginFailed() {
        val proxy = track(FakeSocks5("bob", "pa55"))
        val client = http(ProxySettings(true, ProxyType.SOCKS5, "127.0.0.1", proxy.port, "bob", "nope")).client

        expectProxyFailure(ProxyException.Reason.LOGIN_FAILED) { client.get("http://stream.example.invalid/") }
        assertTrue(proxy.connects.isEmpty())
    }

    @Test fun socksLoginIsOnlyHandedToTheConfiguredProxy() {
        val settings = ProxySettings(true, ProxyType.SOCKS5, "proxy.lan", 1080, "bob", "pa55")
        http(settings)
        // Asks the process-wide authenticator, exactly as Java's SOCKS client does.
        fun ask(host: String, port: Int, protocol: String, type: Authenticator.RequestorType) =
            Authenticator.requestPasswordAuthentication(host, null, port, protocol, "prompt", null, null, type)

        // What Java's SOCKS client asks.
        assertEquals("bob", ask("proxy.lan", 1080, "SOCKS5", Authenticator.RequestorType.SERVER)?.userName)
        assertEquals("pa55", String(ask("PROXY.LAN", 1080, "SOCKS5", Authenticator.RequestorType.SERVER)!!.password))
        // Anything else gets nothing.
        assertNull(ask("proxy.lan", 1081, "SOCKS5", Authenticator.RequestorType.SERVER))
        assertNull(ask("other.lan", 1080, "SOCKS5", Authenticator.RequestorType.SERVER))
        assertNull(ask("proxy.lan", 1080, "http", Authenticator.RequestorType.SERVER))
        assertNull(ask("stream.example.invalid", 80, "http", Authenticator.RequestorType.SERVER))
    }

    // --- fail closed ---

    @Test fun unreachableProxyFailsEveryRequestAndNeverGoesDirect() {
        val target = track(DirectTarget())
        val deadPort = ServerSocket(0).use { it.localPort }
        for (type in ProxyType.entries) {
            val clients = http(ProxySettings(true, type, "127.0.0.1", deadPort))
            val update = clients.derived { followSslRedirects(false) }
            for (client in listOf(clients.client, update())) {
                expectProxyFailure(ProxyException.Reason.UNREACHABLE) { client.get("http://127.0.0.1:${target.port}/") }
                expectProxyFailure(ProxyException.Reason.UNREACHABLE) { client.get("https://stream.example.invalid/") }
            }
            expectProxyFailure(ProxyException.Reason.UNREACHABLE) {
                clients.callFactory.newCall(Request.Builder().url("http://127.0.0.1:${target.port}/").build()).execute().close()
            }
        }
        assertEquals("a request reached the target directly", 0, target.accepted.get())
    }

    @Test fun unknownProxyHostIsReportedAsUnreachable() {
        val target = track(DirectTarget())
        val client = http(ProxySettings(true, ProxyType.HTTP, "no-such-proxy.invalid", 8888)).client
        expectProxyFailure(ProxyException.Reason.UNREACHABLE) { client.get("http://127.0.0.1:${target.port}/") }
        assertEquals(0, target.accepted.get())
    }

    @Test fun enabledButIncompleteProxyBlocksInsteadOfGoingDirect() {
        val target = track(DirectTarget())
        for (settings in listOf(ProxySettings(enabled = true), ProxySettings(true, ProxyType.HTTP, "127.0.0.1", 0))) {
            expectProxyFailure(ProxyException.Reason.NOT_CONFIGURED) { http(settings).client.get("http://127.0.0.1:${target.port}/") }
        }
        // SOCKS5 on a device whose Java SOCKS client cannot do it.
        val old = HttpClients(ProxySettings(true, ProxyType.SOCKS5, "127.0.0.1", 1080), socksSupported = false, dns = dns)
        expectProxyFailure(ProxyException.Reason.NOT_CONFIGURED) { old.client.get("http://127.0.0.1:${target.port}/") }
        assertEquals(0, target.accepted.get())
    }

    @Test fun changingTheSettingAppliesWithoutRestart() {
        val target = track(DirectTarget())
        val proxy = track(FakeHttpProxy(null, null))
        val clients = http(ProxySettings())
        val playlist = clients.derived { followRedirects(true) }
        val url = "http://127.0.0.1:${target.port}/"
        fun viaFactory() = clients.callFactory.newCall(Request.Builder().url(url).build()).execute().use { "${it.code} ${it.body?.string()}" }

        assertEquals("200 direct", viaFactory())
        assertEquals("200 direct", playlist().get(url))

        clients.apply(ProxySettings(true, ProxyType.HTTP, "127.0.0.1", proxy.port))
        assertEquals("200 via-proxy", viaFactory())
        assertEquals("200 via-proxy", playlist().get(url))

        val deadPort = ServerSocket(0).use { it.localPort }
        clients.apply(ProxySettings(true, ProxyType.SOCKS5, "127.0.0.1", deadPort))
        expectProxyFailure(ProxyException.Reason.UNREACHABLE) { viaFactory() }
        expectProxyFailure(ProxyException.Reason.UNREACHABLE) { playlist().get(url) }
        assertEquals(2, target.accepted.get())

        clients.apply(ProxySettings())
        assertEquals("200 direct", viaFactory())
    }

    @Test fun testClientUsesTheEnteredProxyWithoutChangingTheSavedOne() {
        val proxy = track(FakeSocks5("bob", "pa55"))
        val target = track(DirectTarget())
        val clients = http(ProxySettings())
        val entered = ProxySettings(true, ProxyType.SOCKS5, "127.0.0.1", proxy.port, "bob", "pa55")
        assertEquals("200 via-socks", clients.withTestClient(entered) { it.get("http://ipinfo.example.invalid/json") })
        assertEquals("200 direct", clients.client.get("http://127.0.0.1:${target.port}/"))
    }

    @Test fun settingsNeverPrintCredentials() {
        val text = ProxySettings(true, ProxyType.SOCKS5, "vpn.example", 1080, "user-name-x", "password-y").toString()
        assertFalse(text.contains("user-name-x"))
        assertFalse(text.contains("password-y"))
    }

    // --- fakes ---

    private class RecordingDns : Dns {
        val lookups = CopyOnWriteArrayList<String>()
        override fun lookup(hostname: String): List<InetAddress> { lookups += hostname; return Dns.SYSTEM.lookup(hostname) }
    }

    private abstract class FakeServer : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort
        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { socket.use { runCatching { serve(it, it.getInputStream(), it.getOutputStream()) } } }
                }
            }
        }
        abstract fun serve(socket: Socket, input: InputStream, output: OutputStream)
        override fun close() = server.close()

        protected fun readHead(input: InputStream): List<String>? {
            val lines = ArrayList<String>()
            val line = StringBuilder()
            while (true) {
                val byte = input.read()
                if (byte < 0) return null
                if (byte == '\n'.code) {
                    val text = line.toString().trimEnd('\r'); line.clear()
                    if (text.isEmpty()) return lines
                    lines += text
                } else line.append(byte.toChar())
            }
        }
        protected fun respond(output: OutputStream, status: String, body: String, extra: String = "") {
            output.write("HTTP/1.1 $status\r\n${extra}Content-Length: ${body.length}\r\n\r\n$body".toByteArray()); output.flush()
        }
    }

    private class DirectTarget : FakeServer() {
        /** Requests that reached the target without a proxy. */
        val accepted = AtomicInteger()
        override fun serve(socket: Socket, input: InputStream, output: OutputStream) {
            while (readHead(input) != null) { accepted.incrementAndGet(); respond(output, "200 OK", "direct") }
        }
    }

    private class FakeHttpProxy(user: String?, password: String?, private val connectStatus: Int = 200) : FakeServer() {
        data class Seen(val line: String, val proxyAuthorization: String?)
        val requests = CopyOnWriteArrayList<Seen>()
        private val expected = user?.let { "Basic " + Base64.getEncoder().encodeToString("$it:$password".toByteArray()) }
        override fun serve(socket: Socket, input: InputStream, output: OutputStream) {
            while (true) {
                val head = readHead(input) ?: return
                val auth = head.drop(1).firstOrNull { it.startsWith("Proxy-Authorization:", ignoreCase = true) }?.substringAfter(':')?.trim()
                requests += Seen(head.first(), auth)
                when {
                    expected != null && auth != expected -> respond(output, "407 Proxy Authentication Required", "", "Proxy-Authenticate: Basic realm=\"test\"\r\n")
                    head.first().startsWith("CONNECT") -> { respond(output, "$connectStatus Refused", ""); return }
                    else -> respond(output, "200 OK", "via-proxy")
                }
            }
        }
    }

    private class FakeSocks5(private val user: String, private val password: String) : FakeServer() {
        val logins = CopyOnWriteArrayList<String>()
        val connects = CopyOnWriteArrayList<String>()
        override fun serve(socket: Socket, input: InputStream, output: OutputStream) {
            val data = DataInputStream(input)
            check(data.readUnsignedByte() == 5)
            val methods = ByteArray(data.readUnsignedByte()).also { data.readFully(it) }
            if (2.toByte() !in methods) { output.write(byteArrayOf(5, 0xFF.toByte())); return }
            output.write(byteArrayOf(5, 2)); output.flush()
            check(data.readUnsignedByte() == 1)
            val name = String(ByteArray(data.readUnsignedByte()).also { data.readFully(it) })
            val pass = String(ByteArray(data.readUnsignedByte()).also { data.readFully(it) })
            logins += "$name:$pass"
            if (name != user || pass != password) { output.write(byteArrayOf(1, 1)); output.flush(); return }
            output.write(byteArrayOf(1, 0)); output.flush()
            check(data.readUnsignedByte() == 5); check(data.readUnsignedByte() == 1); data.readUnsignedByte()
            val target = when (data.readUnsignedByte()) {
                3 -> "domain " + String(ByteArray(data.readUnsignedByte()).also { data.readFully(it) })
                1 -> "ipv4 " + ByteArray(4).also { data.readFully(it) }.joinToString(".") { (it.toInt() and 0xFF).toString() }
                else -> "ipv6"
            }
            connects += "$target:${data.readUnsignedShort()}"
            output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); output.flush()
            readHead(input) ?: return
            respond(output, "200 OK", "via-socks", "Connection: close\r\n")
        }
    }
}
