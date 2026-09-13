package com.siftalpha.studio.runtime

import java.net.URI

/**
 * Safe local Web URL resolver.
 *
 * `SIFTALPHA_WEB_URL=` remains the explicit protocol and has priority, but ordinary projects are not
 * required to implement it. Studio also accepts loopback/wildcard HTTP(S) URLs printed by common
 * runtimes such as Fastify, Vite and Next, plus strongly-scoped local endpoint log forms such as
 * `listening on 127.0.0.1:8000` and `listening on port 3000`. Wildcard bind addresses are normalized
 * to 127.0.0.1 for the Android browser. External hosts, credentials and custom URI schemes are always
 * rejected. All inferred candidates still require the Android-side endpoint probe before Browser is enabled.
 */
object RuntimeWebUrl {
    private const val PREFIX = "SIFTALPHA_WEB_URL="

    private val localUrlRegex = Regex(
        "(?i)https?://(?:127\\.0\\.0\\.1|localhost|0\\.0\\.0\\.0|\\[::1]|\\[::])(?::\\d{1,5})?(?:/[^\\s\\u001B]*)?",
    )

    private val bareLocalEndpointRegex = Regex(
        "(?i)(?<![A-Za-z0-9_.-])(?:127\\.0\\.0\\.1|localhost|0\\.0\\.0\\.0|\\[::1]|\\[::])\\s*:\\s*\\d{1,5}(?!\\d)",
    )

    private val listeningPortRegex = Regex(
        "(?i)\\b(?:listening|listen(?:ing)?|server\\s+(?:running|listening))\\b[^\\r\\n]{0,48}?\\bport\\s*[:=]?\\s*(\\d{1,5})(?!\\d)",
    )

    fun extractLocalHttpUrl(output: String): String? {
        val explicit = output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith(PREFIX) }
            .map { it.removePrefix(PREFIX).trim() }
            .filter { it.isNotBlank() }
            .mapNotNull(::validateAndNormalize)
            .lastOrNull()
        if (explicit != null) return explicit

        // Invalid explicit protocol lines must never be reinterpreted by the ordinary-log fallback.
        // For example, a credential-bearing URL is intentionally rejected as explicit input and the
        // embedded `127.0.0.1:8765` must not then be rescued as a bare endpoint. Other independent
        // runtime log lines are still eligible for best-effort discovery.
        val fallbackOutput = output.lineSequence()
            .filterNot { it.trim().startsWith(PREFIX) }
            .joinToString("\n")

        val ordinaryUrl = localUrlRegex.findAll(fallbackOutput)
            .map { it.value.trimEnd('.', ',', ';', ')') }
            .mapNotNull(::validateAndNormalize)
            .lastOrNull()
        if (ordinaryUrl != null) return ordinaryUrl

        val bareEndpoint = bareLocalEndpointRegex.findAll(fallbackOutput)
            .mapNotNull { match ->
                val endpoint = match.value.replace(" ", "")
                validateAndNormalize("http://$endpoint")
            }
            .lastOrNull()
        if (bareEndpoint != null) return bareEndpoint

        return listeningPortRegex.findAll(fallbackOutput)
            .mapNotNull { match ->
                val port = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                if (port !in 1..65535) return@mapNotNull null
                validateAndNormalize("http://127.0.0.1:$port")
            }
            .lastOrNull()
    }

    private fun validateAndNormalize(candidate: String): String? {
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.userInfo != null) return null
        if (uri.port == 0 || uri.port < -1 || uri.port > 65535) return null

        val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return null
        val browserHost = when (host) {
            "127.0.0.1", "localhost", "::1" -> host
            "0.0.0.0", "::" -> "127.0.0.1"
            else -> return null
        }
        if (browserHost == host && host != "::1") return candidate

        return runCatching {
            URI(
                scheme,
                null,
                browserHost,
                uri.port,
                uri.rawPath,
                uri.rawQuery,
                uri.rawFragment,
            ).toASCIIString()
        }.getOrNull()
    }
}
