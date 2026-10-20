package com.sternpaul.streamguide.core

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ProxySettingsTest {
    private val socks = ProxySettings(true, ProxyType.SOCKS5, "10.0.0.2", 1080, "u", "p")

    @Test fun offByDefaultAndADisabledProxyNeedsNothing() {
        assertEquals(false, ProxySettings().enabled)
        assertEquals("", ProxySettings().host)
        assertNull(ProxyValidation.problem(ProxySettings()))
    }

    @Test fun enabledProxyNeedsHostPortAndAUsernameForAPassword() {
        assertNotNull(ProxyValidation.problem(ProxySettings(enabled = true, port = 8888)))
        assertNotNull(ProxyValidation.problem(ProxySettings(true, ProxyType.HTTP, "http://nas.lan", 8888)))
        assertNotNull(ProxyValidation.problem(ProxySettings(true, ProxyType.HTTP, "nas.lan", 0)))
        assertNotNull(ProxyValidation.problem(ProxySettings(true, ProxyType.HTTP, "nas.lan", 70000)))
        assertNotNull(ProxyValidation.problem(ProxySettings(true, ProxyType.HTTP, "nas.lan", 8888, "", "secret")))
        assertNull(ProxyValidation.problem(ProxySettings(true, ProxyType.HTTP, "nas.lan", 8888)))
        assertNull(ProxyValidation.problem(socks))
        assertNotNull(ProxyValidation.problem(socks, socksSupported = false))
    }

    @Test fun failuresReachingOrLoggingInToTheProxyAreNamed() {
        fun reason(error: IOException) = (ProxyErrors.classify(error, socks) as? ProxyException)?.reason
        assertEquals(ProxyException.Reason.UNREACHABLE, reason(ConnectException("Failed to connect to /10.0.0.2:1080")))
        assertEquals(ProxyException.Reason.UNREACHABLE, reason(UnknownHostException("nas.lan")))
        assertEquals(ProxyException.Reason.UNREACHABLE, reason(SocketException("Connection refused")))
        assertEquals(ProxyException.Reason.LOGIN_FAILED, reason(SocketException("SOCKS : authentication failed")))
        assertEquals(ProxyException.Reason.LOGIN_FAILED, reason(IOException("Failed to authenticate with proxy")))
        // The proxy answered but the stream server refused: not a proxy fault.
        assertNull(reason(SocketException("SOCKS: Connection refused")))
        assertEquals("Proxy unreachable (10.0.0.2:1080)", ProxyErrors.unreachable(socks).message)
    }

    @Test fun proxyFailureIsFoundInsideWrappedErrors() {
        val proxy = ProxyErrors.loginFailed()
        assertSame(proxy, ProxyErrors.find(IllegalStateException("playback", IOException("io", proxy))))
        assertNull(ProxyErrors.find(IOException("other")))
    }
}
