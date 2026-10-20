package com.sternpaul.streamguide.core

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class ProxyType(val label: String) { HTTP("HTTP"), SOCKS5("SOCKS5") }

/** The user's proxy. Off by default; nothing is prefilled. */
data class ProxySettings(
    val enabled: Boolean = false,
    val type: ProxyType = ProxyType.HTTP,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = ""
) {
    val hasCredentials: Boolean get() = username.isNotEmpty()
    val address: String get() = if (host.contains(':')) "[$host]:$port" else "$host:$port"

    // Credentials must never reach a log line, an error message or a crash report.
    override fun toString(): String =
        "ProxySettings(enabled=$enabled, type=$type, address=$address, credentials=${if (hasCredentials) "set" else "none"})"
}

object ProxyValidation {
    /** Returns why [settings] cannot be used, or null. A disabled proxy is always valid. */
    fun problem(settings: ProxySettings, socksSupported: Boolean = true): String? = when {
        !settings.enabled -> null
        settings.host.isBlank() -> "Enter the proxy host"
        settings.host.any { it.isWhitespace() || it == '/' || it == '@' } -> "Enter only the proxy host name or IP address, without http:// or a path"
        settings.port !in 1..65535 -> "Enter a proxy port between 1 and 65535"
        settings.password.isNotEmpty() && settings.username.isEmpty() -> "Enter the proxy username for this password"
        settings.type == ProxyType.SOCKS5 && !socksSupported -> "SOCKS5 needs Android 7 or newer on this device. Use an HTTP proxy instead."
        settings.type == ProxyType.SOCKS5 && (settings.username.length > 255 || settings.password.length > 255) -> "SOCKS5 usernames and passwords can be at most 255 characters"
        else -> null
    }
}

/**
 * A request failed because of the proxy. Thrown instead of connecting directly: with the
 * proxy enabled, StreamGuide never falls back to the user's own connection.
 */
class ProxyException(val reason: Reason, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Reason { UNREACHABLE, LOGIN_FAILED, NOT_CONFIGURED }
}

object ProxyErrors {
    private val unreachableMessages = listOf("Connection refused", "Network is unreachable", "No route to host", "connect timed out", "Host is unreachable")
    fun unreachable(settings: ProxySettings, cause: Throwable? = null) =
        ProxyException(ProxyException.Reason.UNREACHABLE, "Proxy unreachable (${settings.address})", cause)
    fun loginFailed(cause: Throwable? = null) =
        ProxyException(ProxyException.Reason.LOGIN_FAILED, "Proxy login failed. Check the proxy username and password.", cause)
    fun notConfigured(problem: String) =
        ProxyException(ProxyException.Reason.NOT_CONFIGURED, "Proxy settings incomplete: $problem")

    /**
     * Names the proxy as the culprit when [error] came from reaching or logging in to it.
     * With a proxy, target host names are never resolved here and every connection goes to
     * the proxy, so a refused connection or an unknown host can only be the proxy itself.
     */
    fun classify(error: IOException, settings: ProxySettings): IOException {
        if (error is ProxyException) return error
        val message = error.message.orEmpty()
        return when {
            message.contains("authentication failed", ignoreCase = true) ||
                message.contains("No acceptable methods", ignoreCase = true) ||
                message.contains("Failed to authenticate with proxy", ignoreCase = true) -> loginFailed(error)
            error is ConnectException || error is NoRouteToHostException || error is UnknownHostException -> unreachable(settings, error)
            // Java's SOCKS client reports a failed connection to the proxy as a plain SocketException;
            // the proxy's own replies about the target start with "SOCKS".
            error is SocketException && !message.contains("SOCKS") && unreachableMessages.any { message.contains(it, ignoreCase = true) } -> unreachable(settings, error)
            error is SocketTimeoutException && message.contains("connect", ignoreCase = true) -> unreachable(settings, error)
            message.contains("Malformed reply from SOCKS server", ignoreCase = true) ||
                message.contains("Reply from SOCKS server has bad version", ignoreCase = true) -> unreachable(settings, error)
            else -> error
        }
    }

    /** The proxy failure somewhere in [error]'s causes, e.g. inside a playback error. */
    fun find(error: Throwable?): ProxyException? {
        var current = error
        repeat(10) {
            if (current is ProxyException) return current as ProxyException
            current = current?.cause ?: return null
        }
        return null
    }
}
