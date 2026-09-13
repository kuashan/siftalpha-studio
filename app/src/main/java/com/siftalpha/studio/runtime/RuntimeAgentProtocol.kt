package com.siftalpha.studio.runtime

/**
 * Stable constants shared by the Android client and the Ubuntu Runtime Agent.
 *
 * v0.5.4 upgrades the Agent to a bounded authenticated multi-PTY model. Every Terminal Session owns
 * an independent interactive Bash process, PTY, output ring and window size. The Agent remains fixed
 * to IPv4 loopback and still exposes no generic exec or shell endpoint.
 */
object RuntimeAgentProtocol {
    const val AGENT_NAME = "siftalpha-runtime-agent"
    const val AGENT_VERSION = "0.5.4"
    const val PROTOCOL_VERSION = 4
    const val HOST = "127.0.0.1"
    const val PORT = 17650
    const val HEALTH_PATH = "/health"
    const val TERMINAL_SESSIONS_PATH = "/terminal/sessions"
    const val TERMINAL_SESSION_PATH = "/terminal/session"
    const val TERMINAL_INPUT_PATH = "/terminal/session/input"
    const val TERMINAL_OUTPUT_PATH = "/terminal/session/output"
    const val TERMINAL_RESIZE_PATH = "/terminal/session/resize"
    const val TERMINAL_CLOSE_PATH = "/terminal/session/close"
    const val MAX_TERMINAL_SESSIONS = 4
    const val AGENT_DIR = "/root/siftalpha/agent"
    const val SCRIPT_PATH = "$AGENT_DIR/agent.py"
    const val TOKEN_PATH = "$AGENT_DIR/token"
    const val PID_PATH = "$AGENT_DIR/pid"
    const val LOG_PATH = "$AGENT_DIR/agent.log"

    private val TOKEN_REGEX = Regex("^[0-9a-f]{64}$")

    fun isValidToken(value: String?): Boolean =
        value != null && TOKEN_REGEX.matches(value)

    fun authorizationValue(token: String): String {
        require(isValidToken(token)) { "Invalid Runtime Agent token" }
        return "Bearer $token"
    }

    fun pythonSource(): String = """
        #!/usr/bin/env python3
        import base64
        import errno
        import fcntl
        import hmac
        import json
        import os
        import pty
        import secrets
        import signal
        import struct
        import termios
        import threading
        import time
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
        from urllib.parse import parse_qs, urlparse

        HOST = ${pythonString(HOST)}
        PORT = $PORT
        AGENT_NAME = ${pythonString(AGENT_NAME)}
        AGENT_VERSION = ${pythonString(AGENT_VERSION)}
        PROTOCOL_VERSION = $PROTOCOL_VERSION
        TOKEN_PATH = ${pythonString(TOKEN_PATH)}
        PID_PATH = ${pythonString(PID_PATH)}
        STARTED_AT = time.time()

        MAX_REQUEST_BYTES = 64 * 1024
        MAX_TERMINAL_BUFFER_BYTES = 512 * 1024
        MAX_TERMINAL_OUTPUT_BYTES = 32 * 1024
        MAX_TERMINAL_INPUT_BYTES = 16 * 1024
        MAX_TERMINAL_SESSIONS = $MAX_TERMINAL_SESSIONS
        MIN_COLS = 20
        MAX_COLS = 300
        MIN_ROWS = 5
        MAX_ROWS = 120
        DEFAULT_COLS = 80
        DEFAULT_ROWS = 24

        SESSION_LOCK = threading.RLock()
        SESSIONS = {}
        SESSION_ORDER = []
        NEXT_SESSION_ORDINAL = 1

        def load_token():
            with open(TOKEN_PATH, "r", encoding="utf-8") as handle:
                token = handle.read().strip()
            if len(token) != 64 or any(ch not in "0123456789abcdef" for ch in token):
                raise RuntimeError("invalid Runtime Agent token")
            return token

        TOKEN = load_token()

        with open(PID_PATH, "w", encoding="utf-8") as handle:
            handle.write(str(os.getpid()))

        def session_payload(session):
            return {
                "exists": True,
                "sessionId": session["id"],
                "ordinal": session["ordinal"],
                "pid": session["pid"],
                "running": session["running"],
                "exitCode": session["exit_code"],
                "cols": session["cols"],
                "rows": session["rows"],
                "createdAt": session["created_at"],
                "baseCursor": session["base_cursor"],
                "nextCursor": session["next_cursor"],
            }

        def sessions_payload():
            sessions = []
            for session_id in SESSION_ORDER:
                session = SESSIONS.get(session_id)
                if session is not None:
                    sessions.append(session_payload(session))
            return sessions

        def prune_stopped_for_capacity():
            while len(SESSIONS) >= MAX_TERMINAL_SESSIONS:
                removable_id = None
                for session_id in SESSION_ORDER:
                    session = SESSIONS.get(session_id)
                    if session is not None and not session["running"]:
                        removable_id = session_id
                        break
                if removable_id is None:
                    return False
                SESSIONS.pop(removable_id, None)
                try:
                    SESSION_ORDER.remove(removable_id)
                except ValueError:
                    pass
            return True

        def valid_terminal_size(cols, rows):
            return MIN_COLS <= cols <= MAX_COLS and MIN_ROWS <= rows <= MAX_ROWS

        def parse_terminal_size(payload):
            try:
                cols = int(payload.get("cols", DEFAULT_COLS))
                rows = int(payload.get("rows", DEFAULT_ROWS))
            except (TypeError, ValueError):
                return None, None
            if not valid_terminal_size(cols, rows):
                return None, None
            return cols, rows

        def append_terminal_output(session_id, data):
            with SESSION_LOCK:
                session = SESSIONS.get(session_id)
                if session is None:
                    return
                session["buffer"].extend(data)
                session["next_cursor"] += len(data)
                extra = len(session["buffer"]) - MAX_TERMINAL_BUFFER_BYTES
                if extra > 0:
                    del session["buffer"][:extra]
                    session["base_cursor"] += extra

        def terminal_reader(session_id, child_pid, master_fd):
            try:
                while True:
                    try:
                        data = os.read(master_fd, 4096)
                    except OSError as error:
                        if error.errno == errno.EIO:
                            break
                        raise
                    if not data:
                        break
                    append_terminal_output(session_id, data)
            finally:
                try:
                    _, status = os.waitpid(child_pid, 0)
                    exit_code = os.waitstatus_to_exitcode(status)
                except (ChildProcessError, OSError):
                    exit_code = None
                try:
                    os.close(master_fd)
                except OSError:
                    pass
                with SESSION_LOCK:
                    session = SESSIONS.get(session_id)
                    if session is not None:
                        session["running"] = False
                        session["exit_code"] = exit_code

        def set_terminal_size(master_fd, cols, rows):
            packed = struct.pack("HHHH", rows, cols, 0, 0)
            fcntl.ioctl(master_fd, termios.TIOCSWINSZ, packed)

        def start_terminal(cols, rows):
            global NEXT_SESSION_ORDINAL
            with SESSION_LOCK:
                if not prune_stopped_for_capacity():
                    return None, "max_sessions_running"

                child_pid, master_fd = pty.fork()
                if child_pid == 0:
                    try:
                        os.chdir("/root")
                        env = os.environ.copy()
                        env["HOME"] = "/root"
                        env["SHELL"] = "/bin/bash"
                        env["TERM"] = "xterm-256color"
                        os.execvpe("/bin/bash", ["/bin/bash", "-i"], env)
                    except BaseException:
                        os._exit(127)

                try:
                    set_terminal_size(master_fd, cols, rows)
                except BaseException:
                    try:
                        os.kill(child_pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    try:
                        os.close(master_fd)
                    except OSError:
                        pass
                    raise

                session_id = secrets.token_hex(12)
                ordinal = NEXT_SESSION_ORDINAL
                NEXT_SESSION_ORDINAL += 1
                session = {
                    "id": session_id,
                    "ordinal": ordinal,
                    "pid": child_pid,
                    "master_fd": master_fd,
                    "running": True,
                    "exit_code": None,
                    "cols": cols,
                    "rows": rows,
                    "created_at": int(time.time()),
                    "buffer": bytearray(),
                    "base_cursor": 0,
                    "next_cursor": 0,
                }
                SESSIONS[session_id] = session
                SESSION_ORDER.append(session_id)
                reader = threading.Thread(
                    target=terminal_reader,
                    args=(session_id, child_pid, master_fd),
                    name="siftalpha-terminal-reader-" + session_id[:6],
                    daemon=True,
                )
                reader.start()
                return session_payload(session), None

        def write_terminal(session_id, data):
            with SESSION_LOCK:
                session = SESSIONS.get(session_id)
                if session is None:
                    return "session_not_found"
                if not session["running"]:
                    return "session_not_running"
                master_fd = session["master_fd"]
            try:
                view = memoryview(data)
                while view:
                    count = os.write(master_fd, view)
                    view = view[count:]
                return None
            except OSError:
                return "terminal_write_failed"

        def resize_terminal(session_id, cols, rows):
            with SESSION_LOCK:
                session = SESSIONS.get(session_id)
                if session is None:
                    return None, "session_not_found"
                if not session["running"]:
                    return None, "session_not_running"
                master_fd = session["master_fd"]
                try:
                    set_terminal_size(master_fd, cols, rows)
                except OSError:
                    return None, "terminal_resize_failed"
                session["cols"] = cols
                session["rows"] = rows
                return session_payload(session), None

        def read_terminal(session_id, cursor):
            with SESSION_LOCK:
                session = SESSIONS.get(session_id)
                if session is None:
                    return None, "session_not_found"
                if cursor > session["next_cursor"]:
                    return None, "cursor_ahead"
                start = max(cursor, session["base_cursor"])
                truncated = cursor < session["base_cursor"]
                index = start - session["base_cursor"]
                data = bytes(session["buffer"][index:index + MAX_TERMINAL_OUTPUT_BYTES])
                next_cursor = start + len(data)
                return {
                    "sessionId": session_id,
                    "data": base64.b64encode(data).decode("ascii"),
                    "nextCursor": next_cursor,
                    "baseCursor": session["base_cursor"],
                    "truncated": truncated,
                    "running": session["running"],
                    "exitCode": session["exit_code"],
                }, None

        def close_terminal(session_id):
            with SESSION_LOCK:
                session = SESSIONS.get(session_id)
                if session is None:
                    return None, "session_not_found"
                pid = session["pid"]
                running = session["running"]
            if running:
                try:
                    os.kill(pid, signal.SIGHUP)
                except ProcessLookupError:
                    pass
                deadline = time.time() + 1.0
                while time.time() < deadline:
                    with SESSION_LOCK:
                        current = SESSIONS.get(session_id)
                        if current is None or not current["running"]:
                            break
                    time.sleep(0.05)
                with SESSION_LOCK:
                    current = SESSIONS.get(session_id)
                    still_running = current is not None and current["running"]
                if still_running:
                    try:
                        os.kill(pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    deadline = time.time() + 0.5
                    while time.time() < deadline:
                        with SESSION_LOCK:
                            current = SESSIONS.get(session_id)
                            if current is None or not current["running"]:
                                break
                        time.sleep(0.05)
            with SESSION_LOCK:
                current = SESSIONS.get(session_id)
                if current is None:
                    return None, "session_not_found"
                payload = session_payload(current)
                SESSIONS.pop(session_id, None)
                try:
                    SESSION_ORDER.remove(session_id)
                except ValueError:
                    pass
                return payload, None

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, format, *args):
                return

            def _authorized(self):
                supplied = self.headers.get("Authorization", "")
                expected = "Bearer " + TOKEN
                return hmac.compare_digest(supplied, expected)

            def _json(self, status, payload):
                body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Connection", "close")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def _require_auth(self):
                if self._authorized():
                    return True
                self._json(401, {"ok": False, "error": "unauthorized"})
                return False

            def _read_json(self):
                raw_length = self.headers.get("Content-Length", "0")
                try:
                    length = int(raw_length)
                except ValueError:
                    self._json(400, {"ok": False, "error": "invalid_content_length"})
                    return None
                if length < 0 or length > MAX_REQUEST_BYTES:
                    self._json(413, {"ok": False, "error": "request_too_large"})
                    return None
                try:
                    raw = self.rfile.read(length) if length else b"{}"
                    payload = json.loads(raw.decode("utf-8"))
                except (UnicodeDecodeError, json.JSONDecodeError):
                    self._json(400, {"ok": False, "error": "invalid_json"})
                    return None
                if not isinstance(payload, dict):
                    self._json(400, {"ok": False, "error": "json_object_required"})
                    return None
                return payload

            def do_GET(self):
                parsed = urlparse(self.path)
                if parsed.path == ${pythonString(HEALTH_PATH)}:
                    if not self._require_auth():
                        return
                    with SESSION_LOCK:
                        running_count = sum(1 for session in SESSIONS.values() if session["running"])
                    self._json(200, {
                        "ok": True,
                        "agent": AGENT_NAME,
                        "version": AGENT_VERSION,
                        "protocol": PROTOCOL_VERSION,
                        "pid": os.getpid(),
                        "host": HOST,
                        "port": PORT,
                        "uptimeSeconds": int(time.time() - STARTED_AT),
                        "capabilities": ["health", "pty", "terminal-multi", "terminal-list", "terminal-resize"],
                        "maxTerminalSessions": MAX_TERMINAL_SESSIONS,
                        "runningTerminalSessions": running_count,
                    })
                    return

                if parsed.path == ${pythonString(TERMINAL_SESSIONS_PATH)}:
                    if not self._require_auth():
                        return
                    with SESSION_LOCK:
                        sessions = sessions_payload()
                        running_count = sum(1 for session in SESSIONS.values() if session["running"])
                    self._json(200, {
                        "ok": True,
                        "sessions": sessions,
                        "maxSessions": MAX_TERMINAL_SESSIONS,
                        "runningSessions": running_count,
                    })
                    return

                if parsed.path == ${pythonString(TERMINAL_OUTPUT_PATH)}:
                    if not self._require_auth():
                        return
                    query = parse_qs(parsed.query)
                    session_id = query.get("sessionId", [""])[0]
                    try:
                        cursor = int(query.get("cursor", ["0"])[0])
                    except ValueError:
                        self._json(400, {"ok": False, "error": "invalid_cursor"})
                        return
                    if not session_id:
                        self._json(400, {"ok": False, "error": "session_id_required"})
                        return
                    if cursor < 0:
                        self._json(400, {"ok": False, "error": "invalid_cursor"})
                        return
                    payload, error = read_terminal(session_id, cursor)
                    if error == "session_not_found":
                        self._json(404, {"ok": False, "error": error})
                    elif error == "cursor_ahead":
                        self._json(409, {"ok": False, "error": error})
                    else:
                        self._json(200, {"ok": True, **payload})
                    return

                self._json(404, {"ok": False, "error": "not_found"})

            def do_POST(self):
                parsed = urlparse(self.path)
                if parsed.path not in (
                    ${pythonString(TERMINAL_SESSION_PATH)},
                    ${pythonString(TERMINAL_INPUT_PATH)},
                    ${pythonString(TERMINAL_RESIZE_PATH)},
                    ${pythonString(TERMINAL_CLOSE_PATH)},
                ):
                    self._json(404, {"ok": False, "error": "not_found"})
                    return
                if not self._require_auth():
                    return
                payload = self._read_json()
                if payload is None:
                    return

                if parsed.path == ${pythonString(TERMINAL_SESSION_PATH)}:
                    cols, rows = parse_terminal_size(payload)
                    if cols is None or rows is None:
                        self._json(400, {"ok": False, "error": "invalid_terminal_size"})
                        return
                    session, error = start_terminal(cols, rows)
                    if error:
                        self._json(409, {"ok": False, "error": error})
                    else:
                        self._json(201, {"ok": True, **session})
                    return

                session_id = payload.get("sessionId", "")
                if not isinstance(session_id, str) or not session_id:
                    self._json(400, {"ok": False, "error": "session_id_required"})
                    return

                if parsed.path == ${pythonString(TERMINAL_INPUT_PATH)}:
                    encoded = payload.get("data", "")
                    if not isinstance(encoded, str):
                        self._json(400, {"ok": False, "error": "invalid_input"})
                        return
                    try:
                        data = base64.b64decode(encoded, validate=True)
                    except ValueError:
                        self._json(400, {"ok": False, "error": "invalid_input"})
                        return
                    if len(data) > MAX_TERMINAL_INPUT_BYTES:
                        self._json(413, {"ok": False, "error": "input_too_large"})
                        return
                    error = write_terminal(session_id, data)
                    if error == "session_not_found":
                        self._json(404, {"ok": False, "error": error})
                    elif error:
                        self._json(409, {"ok": False, "error": error})
                    else:
                        self._json(200, {"ok": True, "acceptedBytes": len(data)})
                    return

                if parsed.path == ${pythonString(TERMINAL_RESIZE_PATH)}:
                    cols, rows = parse_terminal_size(payload)
                    if cols is None or rows is None:
                        self._json(400, {"ok": False, "error": "invalid_terminal_size"})
                        return
                    session, error = resize_terminal(session_id, cols, rows)
                    if error == "session_not_found":
                        self._json(404, {"ok": False, "error": error})
                    elif error:
                        self._json(409, {"ok": False, "error": error})
                    else:
                        self._json(200, {"ok": True, **session})
                    return

                session, error = close_terminal(session_id)
                if error:
                    self._json(404, {"ok": False, "error": error})
                else:
                    self._json(200, {"ok": True, "closed": True, **session})

            def do_PUT(self):
                self._json(405, {"ok": False, "error": "method_not_allowed"})

            def do_DELETE(self):
                self._json(405, {"ok": False, "error": "method_not_allowed"})

        server = ThreadingHTTPServer((HOST, PORT), Handler)
        server.daemon_threads = True
        server.serve_forever()
    """.trimIndent() + "\n"

    private fun pythonString(value: String): String =
        "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
}
