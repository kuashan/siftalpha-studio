package com.siftalpha.studio.runtime

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** Fixed-loopback health client for the authenticated Runtime Agent. */
class RuntimeAgentHealthClient {

    data class Health(
        val agent: String,
        val version: String,
        val protocol: Int,
        val pid: Int,
        val uptimeSeconds: Long,
        val capabilities: List<String>,
    )

    data class HttpResponse(
        val statusCode: Int,
        val body: String,
    )

    fun check(token: String): Result<Health> = runCatching {
        require(RuntimeAgentProtocol.isValidToken(token)) { "Runtime Agent token 无效" }
        val response = requestHealth(token)
        when (response.statusCode) {
            200 -> Unit
            401 -> error("Runtime Agent 鉴权失败，请重新启动 Agent 以同步本机 token")
            else -> error("Runtime Agent 返回 HTTP ${response.statusCode}")
        }

        val json = JSONObject(response.body)
        check(json.optBoolean("ok", false)) { "Runtime Agent health 响应无效" }
        val agent = json.optString("agent")
        val version = json.optString("version")
        val protocol = json.optInt("protocol", -1)
        val host = json.optString("host")
        val port = json.optInt("port", -1)
        check(agent == RuntimeAgentProtocol.AGENT_NAME) { "Runtime Agent 身份不匹配" }
        check(version == RuntimeAgentProtocol.AGENT_VERSION) {
            "Runtime Agent 版本不匹配：$version，请启动 / 升级 Agent"
        }
        check(protocol == RuntimeAgentProtocol.PROTOCOL_VERSION) {
            "Runtime Agent 协议不兼容：$protocol，请启动 / 升级 Agent"
        }
        check(host == RuntimeAgentProtocol.HOST && port == RuntimeAgentProtocol.PORT) {
            "Runtime Agent endpoint 不匹配"
        }

        val capabilitiesJson = json.optJSONArray("capabilities")
        val capabilities = buildList {
            if (capabilitiesJson != null) {
                for (index in 0 until capabilitiesJson.length()) {
                    capabilitiesJson.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }

        Health(
            agent = agent,
            version = version,
            protocol = protocol,
            pid = json.optInt("pid", -1),
            uptimeSeconds = json.optLong("uptimeSeconds", 0L),
            capabilities = capabilities,
        )
    }

    private fun requestHealth(token: String): HttpResponse {
        val socket = Socket()
        socket.use {
            socket.connect(
                InetSocketAddress(RuntimeAgentProtocol.HOST, RuntimeAgentProtocol.PORT),
                CONNECT_TIMEOUT_MS,
            )
            socket.soTimeout = READ_TIMEOUT_MS

            val request = buildString {
                append("GET ${RuntimeAgentProtocol.HEALTH_PATH} HTTP/1.1\r\n")
                append("Host: ${RuntimeAgentProtocol.HOST}:${RuntimeAgentProtocol.PORT}\r\n")
                append("Authorization: ${RuntimeAgentProtocol.authorizationValue(token)}\r\n")
                append("Accept: application/json\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()

            val output = ByteArrayOutputStream()
            val buffer = ByteArray(2048)
            while (true) {
                val count = socket.getInputStream().read(buffer)
                if (count < 0) break
                if (count == 0) continue
                check(output.size() + count <= MAX_RESPONSE_BYTES) { "Runtime Agent 响应过大" }
                output.write(buffer, 0, count)
            }
            return parseHttpResponse(output.toByteArray().toString(Charsets.UTF_8))
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 1200
        private const val READ_TIMEOUT_MS = 1500
        private const val MAX_RESPONSE_BYTES = 64 * 1024

        internal fun parseHttpResponse(raw: String): HttpResponse {
            val split = raw.indexOf("\r\n\r\n")
            require(split >= 0) { "Runtime Agent HTTP 响应缺少 header/body 分隔" }
            val header = raw.substring(0, split)
            val body = raw.substring(split + 4)
            val firstLine = header.lineSequence().firstOrNull().orEmpty()
            val statusCode = Regex("^HTTP/1\\.[01]\\s+([0-9]{3})(?:\\s|$)")
                .find(firstLine)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: error("Runtime Agent HTTP 状态行无效")
            return HttpResponse(statusCode = statusCode, body = body)
        }
    }
}
