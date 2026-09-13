package com.siftalpha.studio.runtime

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64

/** Authenticated localhost client for bounded multi-PTY Terminal Sessions. */
class RuntimeAgentTerminalClient {

    data class Session(
        val exists: Boolean,
        val sessionId: String?,
        val ordinal: Int,
        val pid: Int?,
        val running: Boolean,
        val exitCode: Int?,
        val cols: Int?,
        val rows: Int?,
        val createdAt: Long,
        val baseCursor: Long,
        val nextCursor: Long,
    )

    data class SessionList(
        val sessions: List<Session>,
        val maxSessions: Int,
        val runningSessions: Int,
    )

    data class Output(
        val sessionId: String,
        val bytes: ByteArray,
        val nextCursor: Long,
        val baseCursor: Long,
        val truncated: Boolean,
        val running: Boolean,
        val exitCode: Int?,
    )

    fun startSession(token: String, cols: Int = 80, rows: Int = 24): Result<Session> = runCatching {
        requireValidSize(cols, rows)
        val body = JSONObject()
            .put("cols", cols)
            .put("rows", rows)
            .toString()
        val response = request("POST", RuntimeAgentProtocol.TERMINAL_SESSION_PATH, token, body)
        requireStatus(response, setOf(201))
        parseSession(JSONObject(response.body))
    }

    fun listSessions(token: String): Result<SessionList> = runCatching {
        val response = request("GET", RuntimeAgentProtocol.TERMINAL_SESSIONS_PATH, token)
        requireStatus(response, setOf(200))
        val json = JSONObject(response.body)
        check(json.optBoolean("ok", false)) { "Runtime Agent Terminal Sessions 响应无效" }
        val array = json.optJSONArray("sessions") ?: error("Runtime Agent 缺少 Terminal Sessions")
        val sessions = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: error("Runtime Agent Terminal Session 条目无效")
                add(parseSession(item))
            }
        }
        SessionList(
            sessions = sessions,
            maxSessions = json.optInt("maxSessions", RuntimeAgentProtocol.MAX_TERMINAL_SESSIONS),
            runningSessions = json.optInt("runningSessions", sessions.count { it.running }),
        ).also { list ->
            check(list.maxSessions == RuntimeAgentProtocol.MAX_TERMINAL_SESSIONS) {
                "Runtime Agent Terminal Session 上限不匹配"
            }
            check(list.sessions.size <= list.maxSessions) { "Runtime Agent 返回过多 Terminal Sessions" }
            check(list.sessions.mapNotNull { it.sessionId }.distinct().size == list.sessions.size) {
                "Runtime Agent 返回重复 Terminal Session"
            }
        }
    }

    fun sendInput(token: String, sessionId: String, bytes: ByteArray): Result<Int> = runCatching {
        requireSessionId(sessionId)
        require(bytes.size <= MAX_INPUT_BYTES) { "Terminal 输入过大" }
        val body = JSONObject()
            .put("sessionId", sessionId)
            .put("data", Base64.getEncoder().encodeToString(bytes))
            .toString()
        val response = request("POST", RuntimeAgentProtocol.TERMINAL_INPUT_PATH, token, body)
        requireStatus(response, setOf(200))
        JSONObject(response.body).optInt("acceptedBytes", -1).also {
            check(it == bytes.size) { "Runtime Agent 未完整接收 Terminal 输入" }
        }
    }

    fun resizeSession(token: String, sessionId: String, cols: Int, rows: Int): Result<Session> = runCatching {
        requireSessionId(sessionId)
        requireValidSize(cols, rows)
        val body = JSONObject()
            .put("sessionId", sessionId)
            .put("cols", cols)
            .put("rows", rows)
            .toString()
        val response = request("POST", RuntimeAgentProtocol.TERMINAL_RESIZE_PATH, token, body)
        requireStatus(response, setOf(200))
        parseSession(JSONObject(response.body)).also { session ->
            check(session.sessionId == sessionId) { "Terminal Session 身份不匹配" }
            check(session.cols == cols && session.rows == rows) { "Terminal 尺寸同步结果不匹配" }
        }
    }

    fun readOutput(token: String, sessionId: String, cursor: Long): Result<Output> = runCatching {
        requireSessionId(sessionId)
        require(cursor >= 0L) { "Terminal cursor 无效" }
        val path = "${RuntimeAgentProtocol.TERMINAL_OUTPUT_PATH}?sessionId=$sessionId&cursor=$cursor"
        val response = request("GET", path, token)
        requireStatus(response, setOf(200))
        val json = JSONObject(response.body)
        val returnedId = json.optString("sessionId")
        check(returnedId == sessionId) { "Terminal Session 身份不匹配" }
        val encoded = json.optString("data")
        Output(
            sessionId = returnedId,
            bytes = Base64.getDecoder().decode(encoded),
            nextCursor = json.optLong("nextCursor", cursor),
            baseCursor = json.optLong("baseCursor", 0L),
            truncated = json.optBoolean("truncated", false),
            running = json.optBoolean("running", false),
            exitCode = jsonNullableInt(json, "exitCode"),
        )
    }

    fun closeSession(token: String, sessionId: String): Result<Session> = runCatching {
        requireSessionId(sessionId)
        val body = JSONObject().put("sessionId", sessionId).toString()
        val response = request("POST", RuntimeAgentProtocol.TERMINAL_CLOSE_PATH, token, body)
        requireStatus(response, setOf(200))
        parseSession(JSONObject(response.body)).also { session ->
            check(session.sessionId == sessionId) { "Terminal Session 身份不匹配" }
        }
    }

    private fun requireSessionId(sessionId: String) {
        require(SESSION_ID_REGEX.matches(sessionId)) { "Terminal Session ID 无效" }
    }

    private fun requireValidSize(cols: Int, rows: Int) {
        require(cols in MIN_COLS..MAX_COLS && rows in MIN_ROWS..MAX_ROWS) { "Terminal 尺寸无效" }
    }

    private fun parseSession(json: JSONObject): Session {
        check(json.optBoolean("ok", true)) { "Runtime Agent Terminal 响应无效" }
        val exists = json.optBoolean("exists", true)
        val sessionId = json.optString("sessionId").takeIf { exists && it.isNotBlank() }
        if (exists) check(sessionId != null && SESSION_ID_REGEX.matches(sessionId)) {
            "Runtime Agent Terminal Session ID 无效"
        }
        return Session(
            exists = exists,
            sessionId = sessionId,
            ordinal = json.optInt("ordinal", 0),
            pid = jsonNullableInt(json, "pid"),
            running = exists && json.optBoolean("running", false),
            exitCode = jsonNullableInt(json, "exitCode"),
            cols = jsonNullableInt(json, "cols"),
            rows = jsonNullableInt(json, "rows"),
            createdAt = json.optLong("createdAt", 0L),
            baseCursor = json.optLong("baseCursor", 0L),
            nextCursor = json.optLong("nextCursor", 0L),
        ).also { session ->
            if (session.exists) {
                check(session.ordinal > 0) { "Runtime Agent Terminal Session ordinal 无效" }
                check(session.cols != null && session.rows != null) { "Runtime Agent Terminal Session 尺寸缺失" }
            }
        }
    }

    private fun request(
        method: String,
        path: String,
        token: String,
        body: String? = null,
    ): HttpResponse {
        require(RuntimeAgentProtocol.isValidToken(token)) { "Runtime Agent token 无效" }
        require(path.startsWith('/')) { "Runtime Agent path 无效" }
        val bodyBytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        require(bodyBytes.size <= MAX_REQUEST_BYTES) { "Runtime Agent 请求过大" }

        val socket = Socket()
        socket.use {
            socket.connect(
                InetSocketAddress(RuntimeAgentProtocol.HOST, RuntimeAgentProtocol.PORT),
                CONNECT_TIMEOUT_MS,
            )
            socket.soTimeout = READ_TIMEOUT_MS

            val header = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: ${RuntimeAgentProtocol.HOST}:${RuntimeAgentProtocol.PORT}\r\n")
                append("Authorization: ${RuntimeAgentProtocol.authorizationValue(token)}\r\n")
                append("Accept: application/json\r\n")
                if (body != null) {
                    append("Content-Type: application/json; charset=utf-8\r\n")
                    append("Content-Length: ${bodyBytes.size}\r\n")
                }
                append("Connection: close\r\n\r\n")
            }.toByteArray(Charsets.US_ASCII)

            socket.getOutputStream().write(header)
            if (bodyBytes.isNotEmpty()) socket.getOutputStream().write(bodyBytes)
            socket.getOutputStream().flush()

            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
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

    private fun requireStatus(response: HttpResponse, expected: Set<Int>) {
        if (response.statusCode in expected) return
        val detail = runCatching { JSONObject(response.body).optString("error") }.getOrNull()
        val suffix = detail?.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
        error("Runtime Agent 返回 HTTP ${response.statusCode}$suffix")
    }

    private fun jsonNullableInt(json: JSONObject, key: String): Int? =
        if (!json.has(key) || json.isNull(key)) null else json.optInt(key)

    internal data class HttpResponse(val statusCode: Int, val body: String)

    companion object {
        private const val CONNECT_TIMEOUT_MS = 1200
        private const val READ_TIMEOUT_MS = 2000
        private const val MAX_REQUEST_BYTES = 64 * 1024
        private const val MAX_RESPONSE_BYTES = 256 * 1024
        private const val MAX_INPUT_BYTES = 16 * 1024
        private val SESSION_ID_REGEX = Regex("^[0-9a-f]{24}$")
        const val MIN_COLS = 20
        const val MAX_COLS = 300
        const val MIN_ROWS = 5
        const val MAX_ROWS = 120

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
            return HttpResponse(statusCode, body)
        }
    }
}
