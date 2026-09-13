package com.siftalpha.studio.runtime

import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * Verifies whether a previously validated loopback Web URL has a real TCP listener.
 *
 * The probe deliberately checks only the host/port. HTTP parsing is unnecessary here:
 * Studio only needs to know whether enabling the Browser entry is truthful. URL safety
 * remains owned by [RuntimeWebUrl], which accepts loopback HTTP(S) addresses only.
 */
object RuntimeWebEndpointProbe {

    internal data class Target(
        val host: String,
        val port: Int,
    )

    internal fun targets(url: String): List<Target> {
        val validated = RuntimeWebUrl.extractLocalHttpUrl("SIFTALPHA_WEB_URL=$url") ?: return emptyList()
        val uri = runCatching { URI(validated) }.getOrNull() ?: return emptyList()
        val scheme = uri.scheme?.lowercase() ?: return emptyList()
        val port = when {
            uri.port >= 1 -> uri.port
            scheme == "https" -> 443
            else -> 80
        }
        val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return emptyList()
        return when (host) {
            "localhost" -> listOf(Target("127.0.0.1", port), Target("::1", port))
            "127.0.0.1" -> listOf(Target("127.0.0.1", port))
            "::1" -> listOf(Target("::1", port))
            else -> emptyList()
        }
    }

    fun isListening(url: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Boolean {
        if (timeoutMs <= 0) return false
        return targets(url).any { target ->
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(target.host, target.port), timeoutMs)
                    socket.isConnected
                }
            }.getOrDefault(false)
        }
    }

    private const val DEFAULT_TIMEOUT_MS = 350
}
