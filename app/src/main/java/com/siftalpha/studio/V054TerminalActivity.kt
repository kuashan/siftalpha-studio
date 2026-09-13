package com.siftalpha.studio

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.siftalpha.studio.runtime.RuntimeAgentController
import com.siftalpha.studio.runtime.RuntimeAgentHealthClient
import com.siftalpha.studio.runtime.RuntimeAgentProtocol
import com.siftalpha.studio.runtime.RuntimeAgentTerminalClient
import com.siftalpha.studio.runtime.RuntimeAgentTokenStore
import com.siftalpha.studio.runtime.RuntimeCommand
import com.siftalpha.studio.runtime.RuntimeResult
import com.siftalpha.studio.runtime.TerminalEmulator
import com.siftalpha.studio.runtime.TerminalInputComposer
import com.siftalpha.studio.runtime.TerminalScrollFollowPolicy
import com.siftalpha.studio.runtime.TerminalSessionSelectionPolicy
import com.siftalpha.studio.runtime.TermuxBackend
import com.siftalpha.studio.runtime.TermuxResultBus
import java.util.ArrayDeque
import java.util.concurrent.Executors
import kotlin.math.floor
import kotlin.math.max

/** v0.5.4 bounded multi-PTY Terminal implementation behind the stable v0.5 Activity route. */
open class V054TerminalActivity : StudioActivity() {

    private enum class Action { START, STATUS, STOP }
    private enum class Tone { NORMAL, CONNECTED, WARNING }

    private data class LocalTerminalSession(
        var remote: RuntimeAgentTerminalClient.Session,
        var cursor: Long,
        var cols: Int,
        var rows: Int,
        var emulator: TerminalEmulator,
        val diagnostics: ArrayDeque<String> = ArrayDeque(),
        val scrollPolicy: TerminalScrollFollowPolicy = TerminalScrollFollowPolicy(),
        var viewportScrollY: Int = 0,
        var pendingInput: String = "",
    )

    private lateinit var backend: TermuxBackend
    private lateinit var controller: RuntimeAgentController
    private lateinit var tokenStore: RuntimeAgentTokenStore
    private lateinit var healthClient: RuntimeAgentHealthClient
    private lateinit var terminalClient: RuntimeAgentTerminalClient
    private lateinit var stateText: TextView
    private lateinit var outputText: TextView
    private lateinit var terminalStateText: TextView
    private lateinit var terminalSessionsText: TextView
    private lateinit var terminalTabsHost: LinearLayout
    private lateinit var terminalOutputScroll: ScrollView
    private lateinit var terminalOutputText: TextView
    private lateinit var terminalInput: EditText

    private val mainHandler = Handler(Looper.getMainLooper())
    private val terminalInputExecutor = Executors.newSingleThreadExecutor()
    private val terminalSessions = linkedMapOf<String, LocalTerminalSession>()
    private var activeSessionId: String? = null
    private var restoredPreferredSessionId: String? = null
    private var terminalPollGeneration: Int = 0
    private var terminalResizeGeneration: Int = 0
    private var agentLifecycleTransition: Boolean = false

    private val pending: MutableMap<Int, Action>
        get() = PENDING_ACTIONS

    private val resultListener: (RuntimeResult) -> Unit = { result ->
        runOnUiThread {
            val action = pending.remove(result.executionId) ?: return@runOnUiThread
            TermuxResultBus.consume(result.executionId)
            renderRuntimeResult(result)
            handleRuntimeResult(action, result)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoredPreferredSessionId = savedInstanceState?.getString(STATE_ACTIVE_SESSION_ID)
        backend = TermuxBackend(this)
        controller = RuntimeAgentController()
        tokenStore = RuntimeAgentTokenStore(this)
        healthClient = RuntimeAgentHealthClient()
        terminalClient = RuntimeAgentTerminalClient()
        setContentView(buildUi())
    }

    override fun onStart() {
        super.onStart()
        TermuxResultBus.addListener(resultListener)
        pending.keys.toList().forEach { executionId ->
            TermuxResultBus.consume(executionId)?.let(resultListener)
        }
        checkHealth(
            silentFailure = true,
            onConnected = { restoreTerminalSessions(forceFollowLatest = true) },
        )
    }

    override fun onStop() {
        saveActiveUiState()
        stopTerminalPolling()
        terminalResizeGeneration++
        mainHandler.removeCallbacksAndMessages(null)
        TermuxResultBus.removeListener(resultListener)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        saveActiveUiState()
        outState.putString(STATE_ACTIVE_SESSION_ID, activeSessionId)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        terminalInputExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val pageScroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(16, 19, 24)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(30))
        }
        pageScroll.addView(root)

        root.addView(text(getString(R.string.app_name), 25f, true).apply { setTextColor(Color.WHITE) })
        root.addView(text(getString(R.string.terminal_subtitle, appVersionName()), 13f, false).apply {
            setTextColor(Color.rgb(165, 170, 180))
            setPadding(0, dp(2), 0, dp(10))
        })
        root.addView(button(getString(R.string.terminal_back)) { finish() })

        root.addView(section(getString(R.string.terminal_section_agent)))
        stateText = text(getString(R.string.terminal_agent_checking), 17f, true).apply {
            setTextColor(Color.rgb(165, 170, 180))
            setPadding(0, 0, 0, dp(5))
        }
        root.addView(stateText)
        root.addView(text(
            getString(
                R.string.terminal_endpoint,
                RuntimeAgentProtocol.HOST,
                RuntimeAgentProtocol.PORT,
                RuntimeAgentProtocol.PROTOCOL_VERSION,
            ),
            12.5f,
            false,
        ).apply {
            setTextColor(Color.rgb(150, 157, 169))
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(10))
        })

        root.addView(button(getString(R.string.terminal_agent_start_upgrade)) { requestAgentStartOrUpgrade() })
        root.addView(button(getString(R.string.terminal_connection_test)) { checkHealth(silentFailure = false) })
        root.addView(button(getString(R.string.terminal_runtime_status)) { send(Action.STATUS, controller.status()) })
        root.addView(button(getString(R.string.terminal_agent_stop)) {
            prepareForAgentLifecycleTransition(getString(R.string.terminal_agent_stopping_with_sessions))
            send(Action.STOP, controller.stop())
        })

        root.addView(section(getString(R.string.terminal_section_sessions)))
        terminalSessionsText = text(getString(R.string.terminal_sessions_waiting), 13f, false).apply {
            setTextColor(Color.rgb(165, 170, 180))
            setPadding(0, 0, 0, dp(6))
        }
        root.addView(terminalSessionsText)

        val tabsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            terminalTabsHost = LinearLayout(this@V054TerminalActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            addView(terminalTabsHost)
        }
        root.addView(tabsScroll)

        terminalStateText = text(getString(R.string.terminal_waiting_agent), 17f, true).apply {
            setTextColor(Color.rgb(190, 194, 204))
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(terminalStateText)

        root.addView(button(getString(R.string.terminal_new_session)) { createTerminalSession() })
        root.addView(button(getString(R.string.terminal_refresh_sessions)) {
            restoreTerminalSessions(forceFollowLatest = false)
        })

        terminalOutputText = TextView(this).apply {
            text = getString(R.string.terminal_no_session_created)
            textSize = 12.5f
            setTextColor(Color.rgb(224, 228, 236))
            setBackgroundColor(Color.rgb(8, 10, 13))
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextIsSelectable(false)
            minHeight = dp(340)
        }
        terminalOutputScroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(8, 10, 13))
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            isSmoothScrollingEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(340),
            )
            addView(
                terminalOutputText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                activeTerminalSessionOrNull()?.viewportScrollY = scrollY
            }
            setOnTouchListener { view, event ->
                val session = activeTerminalSessionOrNull()
                val sessionId = session?.remote?.sessionId
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        session?.scrollPolicy?.onUserTouchStarted()
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        session?.viewportScrollY = terminalOutputScroll.scrollY
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                        if (session != null && sessionId != null) {
                            mainHandler.postDelayed({
                                val current = terminalSessions[sessionId]
                                if (activeSessionId == sessionId && current != null) {
                                    current.viewportScrollY = terminalOutputScroll.scrollY
                                    current.scrollPolicy.onUserTouchFinished(isTerminalOutputNearBottom())
                                }
                            }, TERMINAL_SCROLL_SETTLE_MS)
                        }
                    }
                }
                false
            }
            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                    scheduleTerminalResize()
                }
            }
        }
        root.addView(terminalOutputScroll)

        terminalInput = EditText(this).apply {
            hint = getString(R.string.terminal_input_hint)
            setHintTextColor(Color.rgb(125, 132, 145))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(24, 28, 35))
            typeface = Typeface.MONOSPACE
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendTerminalLine()
                    true
                } else false
            }
        }
        root.addView(terminalInput)
        root.addView(button(getString(R.string.terminal_send_enter)) { sendTerminalLine() })

        val shortcuts = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(this@V054TerminalActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(shortcutButton(getString(R.string.terminal_shortcut_tab)) { sendTerminalShortcut(byteArrayOf(9)) })
                addView(shortcutButton(getString(R.string.terminal_shortcut_esc)) { sendTerminalShortcut(byteArrayOf(27)) })
                addView(shortcutButton("←") { sendTerminalShortcut("\u001B[D".toByteArray(Charsets.US_ASCII)) })
                addView(shortcutButton("↑") { sendTerminalShortcut("\u001B[A".toByteArray(Charsets.US_ASCII)) })
                addView(shortcutButton("↓") { sendTerminalShortcut("\u001B[B".toByteArray(Charsets.US_ASCII)) })
                addView(shortcutButton("→") { sendTerminalShortcut("\u001B[C".toByteArray(Charsets.US_ASCII)) })
                addView(shortcutButton(getString(R.string.terminal_shortcut_ctrl_c)) { sendTerminalShortcut(byteArrayOf(3)) })
                addView(shortcutButton(getString(R.string.terminal_shortcut_ctrl_d)) { sendTerminalShortcut(byteArrayOf(4)) })
                addView(shortcutButton(getString(R.string.terminal_shortcut_ctrl_l)) { sendTerminalShortcut(byteArrayOf(12)) })
            })
        }
        root.addView(shortcuts)

        root.addView(button(getString(R.string.terminal_close_current)) { closeActiveTerminalSession() })
        root.addView(text(
            getString(R.string.terminal_architecture_note, RuntimeAgentProtocol.MAX_TERMINAL_SESSIONS),
            13f,
            false,
        ).apply {
            setTextColor(Color.rgb(170, 176, 188))
            setPadding(0, dp(8), 0, 0)
        })

        root.addView(section(getString(R.string.terminal_section_agent_output)))
        outputText = TextView(this).apply {
            text = getString(R.string.terminal_agent_no_operation)
            textSize = 12.5f
            setTextColor(Color.rgb(224, 228, 236))
            setBackgroundColor(Color.rgb(24, 28, 35))
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextIsSelectable(true)
        }
        root.addView(outputText)

        renderTerminalTabs()
        renderTerminalSessionSummary()
        return pageScroll
    }

    private fun requestAgentStartOrUpgrade() {
        val runningCount = terminalSessions.values.count { it.remote.running }
        val start = {
            prepareForAgentLifecycleTransition(getString(R.string.terminal_agent_starting_upgrade))
            send(Action.START, controller.start { tokenStore.getOrCreate() })
        }
        if (runningCount <= 0) {
            start()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.terminal_agent_restart_title)
            .setMessage(getString(R.string.terminal_agent_restart_message, runningCount))
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.terminal_continue) { _, _ -> start() }
            .show()
    }

    private fun send(action: Action, command: RuntimeCommand) {
        if (!backend.isTermuxInstalled()) {
            setState(getString(R.string.terminal_agent_termux_unavailable), Tone.WARNING)
            outputText.text = getString(R.string.terminal_termux_missing)
            recoverFromFailedLifecycleTransition(action)
            return
        }
        if (!backend.hasRunCommandPermission()) {
            setState(getString(R.string.terminal_agent_permission_waiting), Tone.WARNING)
            outputText.text = getString(R.string.terminal_permission_missing)
            recoverFromFailedLifecycleTransition(action)
            return
        }
        try {
            val executionId = backend.execute(command)
            pending[executionId] = action
            setState(
                getString(
                    when (action) {
                        Action.START -> R.string.terminal_agent_starting
                        Action.STATUS -> R.string.terminal_agent_reading_status
                        Action.STOP -> R.string.terminal_agent_stopping
                    },
                ),
            )
            outputText.text = getString(R.string.terminal_command_sent, executionId)
        } catch (error: Throwable) {
            recoverFromFailedLifecycleTransition(action)
            setState(getString(R.string.terminal_agent_command_failed), Tone.WARNING)
            outputText.text = getString(
                R.string.terminal_send_failed,
                error.message ?: error.javaClass.simpleName,
            )
        }
    }

    private fun recoverFromFailedLifecycleTransition(action: Action) {
        if (action != Action.START && action != Action.STOP) return
        agentLifecycleTransition = false
        restoreTerminalSessions(forceFollowLatest = true)
    }

    private fun handleRuntimeResult(action: Action, result: RuntimeResult) {
        val success = result.exitCode == 0 && result.internalErrorMessage.isBlank()
        if (!success) {
            recoverFromFailedLifecycleTransition(action)
            setState(getString(R.string.terminal_agent_operation_failed), Tone.WARNING)
            return
        }
        when (action) {
            Action.START -> {
                if (
                    "SIFTALPHA_AGENT=RUNNING" in result.stdout &&
                    "SIFTALPHA_AGENT_READY=1" in result.stdout
                ) {
                    clearAllTerminalLocalState(getString(R.string.terminal_waiting_new_agent))
                    agentLifecycleTransition = false
                    setState(getString(R.string.terminal_agent_started_verifying))
                    checkHealth(
                        silentFailure = false,
                        appendOutput = true,
                        attempts = START_HEALTH_ATTEMPTS,
                        onConnected = { restoreTerminalSessions(forceFollowLatest = true) },
                    )
                } else {
                    agentLifecycleTransition = false
                    setState(getString(R.string.terminal_agent_start_incomplete), Tone.WARNING)
                }
            }
            Action.STATUS -> {
                when {
                    "SIFTALPHA_AGENT=RUNNING" in result.stdout -> {
                        setState(getString(R.string.terminal_agent_runtime_running_verifying))
                        checkHealth(
                            silentFailure = false,
                            appendOutput = true,
                            onConnected = { restoreTerminalSessions(forceFollowLatest = false) },
                        )
                    }
                    "SIFTALPHA_AGENT=STOPPED" in result.stdout -> setState(getString(R.string.terminal_agent_stopped))
                    else -> setState(getString(R.string.terminal_agent_unknown), Tone.WARNING)
                }
            }
            Action.STOP -> {
                agentLifecycleTransition = false
                if ("SIFTALPHA_AGENT=STOPPED" in result.stdout) {
                    setState(getString(R.string.terminal_agent_stopped))
                    clearAllTerminalLocalState(getString(R.string.terminal_agent_stopped_terminal))
                    terminalOutputText.text = getString(R.string.terminal_agent_stopped_output)
                    terminalOutputScroll.scrollTo(0, 0)
                } else {
                    setState(getString(R.string.terminal_agent_stop_incomplete), Tone.WARNING)
                }
            }
        }
    }

    private fun prepareForAgentLifecycleTransition(label: String) {
        saveActiveUiState()
        agentLifecycleTransition = true
        stopTerminalPolling()
        terminalResizeGeneration++
        setTerminalState(label)
    }

    private fun checkHealth(
        silentFailure: Boolean,
        appendOutput: Boolean = false,
        attempts: Int = 1,
        onConnected: (() -> Unit)? = null,
    ) {
        val token = tokenOrNull(silentFailure) ?: return
        setState(getString(R.string.terminal_agent_health_checking))
        Thread {
            var finalResult: Result<RuntimeAgentHealthClient.Health>? = null
            val maxAttempts = attempts.coerceAtLeast(1)
            for (index in 0 until maxAttempts) {
                val result = healthClient.check(token)
                finalResult = result
                if (result.isSuccess) break
                if (index + 1 < maxAttempts) Thread.sleep(START_HEALTH_RETRY_DELAY_MS)
            }
            val result = finalResult ?: healthClient.check(token)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { health ->
                    setState(getString(R.string.terminal_agent_connected), Tone.CONNECTED)
                    val diagnostic = buildString {
                        appendLine("SIFTALPHA_AGENT_HEALTH=PASS")
                        appendLine("AGENT=${health.agent}")
                        appendLine("VERSION=${health.version}")
                        appendLine("PROTOCOL=${health.protocol}")
                        appendLine("PID=${health.pid}")
                        appendLine("UPTIME_SECONDS=${health.uptimeSeconds}")
                        append("CAPABILITIES=${health.capabilities.joinToString(",")}")
                    }
                    if (appendOutput) outputText.append("\n\n--- localhost health ---\n$diagnostic")
                    else outputText.text = diagnostic
                    onConnected?.invoke()
                }.onFailure { error ->
                    setState(
                        getString(R.string.terminal_agent_not_connected),
                        if (silentFailure) Tone.NORMAL else Tone.WARNING,
                    )
                    if (!silentFailure) {
                        val diagnostic = "SIFTALPHA_AGENT_HEALTH=FAIL\n${error.message ?: error.javaClass.simpleName}"
                        if (appendOutput) outputText.append("\n\n--- localhost health ---\n$diagnostic")
                        else outputText.text = diagnostic
                    }
                }
            }
        }.start()
    }

    private fun restoreTerminalSessions(
        forceFollowLatest: Boolean,
        preferredSessionId: String? = restoredPreferredSessionId,
    ) {
        if (agentLifecycleTransition) return
        val token = tokenOrNull(silentFailure = true) ?: return
        Thread {
            val result = terminalClient.listSessions(token)
            runOnUiThread {
                if (isFinishing || isDestroyed || agentLifecycleTransition) return@runOnUiThread
                result.onSuccess { list ->
                    synchronizeTerminalSessions(list, preferredSessionId, forceFollowLatest)
                    restoredPreferredSessionId = null
                }.onFailure { error ->
                    setTerminalState(getString(R.string.terminal_session_list_failed), Tone.WARNING)
                    if (!forceFollowLatest) {
                        appendActiveDiagnostic(getString(
                            R.string.terminal_diag_session_list_failed,
                            error.message ?: error.javaClass.simpleName,
                        ))
                    }
                }
            }
        }.start()
    }

    private fun synchronizeTerminalSessions(
        list: RuntimeAgentTerminalClient.SessionList,
        preferredSessionId: String?,
        forceFollowLatest: Boolean,
    ) {
        saveActiveUiState()
        val remoteIds = list.sessions.mapNotNullTo(linkedSetOf()) { it.sessionId }
        terminalSessions.keys.toList().filterNot(remoteIds::contains).forEach(terminalSessions::remove)

        list.sessions.sortedBy { it.ordinal }.forEach { remote ->
            val sessionId = remote.sessionId ?: return@forEach
            val cols = remote.cols ?: DEFAULT_TERMINAL_COLS
            val rows = remote.rows ?: DEFAULT_TERMINAL_ROWS
            val existing = terminalSessions[sessionId]
            if (existing == null) {
                terminalSessions[sessionId] = LocalTerminalSession(
                    remote = remote,
                    cursor = remote.baseCursor,
                    cols = cols,
                    rows = rows,
                    emulator = newTerminalEmulator(cols, rows),
                )
            } else {
                existing.remote = remote
                if (existing.cursor < remote.baseCursor || existing.cursor > remote.nextCursor) {
                    existing.cursor = remote.baseCursor
                    existing.emulator = newTerminalEmulator(cols, rows)
                    existing.diagnostics.clear()
                }
                if (existing.cols != cols || existing.rows != rows) {
                    existing.cols = cols
                    existing.rows = rows
                    existing.emulator.resize(cols, rows)
                }
            }
        }

        val items = terminalSessions.map { (id, state) ->
            TerminalSessionSelectionPolicy.Item(id, state.remote.ordinal, state.remote.running)
        }
        val chosen = TerminalSessionSelectionPolicy.choose(
            preferredSessionId = preferredSessionId,
            currentSessionId = activeSessionId,
            sessions = items,
        )
        renderTerminalSessionSummary(list.maxSessions)
        if (chosen == null) {
            stopTerminalPolling()
            activeSessionId = null
            terminalInput.setText("")
            renderTerminalTabs()
            setTerminalState(getString(R.string.terminal_not_created))
            terminalOutputText.text = getString(R.string.terminal_agent_connected_create)
            terminalOutputScroll.scrollTo(0, 0)
            return
        }
        activateTerminalSession(chosen, followLatest = forceFollowLatest)
    }

    private fun createTerminalSession() {
        if (agentLifecycleTransition) return
        val runningCount = terminalSessions.values.count { it.remote.running }
        if (runningCount >= RuntimeAgentProtocol.MAX_TERMINAL_SESSIONS) {
            setTerminalState(
                getString(R.string.terminal_session_limit, RuntimeAgentProtocol.MAX_TERMINAL_SESSIONS),
                Tone.WARNING,
            )
            return
        }
        saveActiveUiState()
        val token = tokenOrNull(silentFailure = false) ?: return
        val (cols, rows) = terminalViewSize()
        setTerminalState(getString(R.string.terminal_creating_session))
        Thread {
            val result = terminalClient.startSession(token, cols, rows)
            runOnUiThread {
                if (isFinishing || isDestroyed || agentLifecycleTransition) return@runOnUiThread
                result.onSuccess { remote ->
                    val sessionId = remote.sessionId ?: return@onSuccess
                    if (terminalSessions.size >= RuntimeAgentProtocol.MAX_TERMINAL_SESSIONS) {
                        terminalSessions.entries
                            .filter { !it.value.remote.running }
                            .minByOrNull { it.value.remote.ordinal }
                            ?.key
                            ?.let(terminalSessions::remove)
                    }
                    terminalSessions[sessionId] = LocalTerminalSession(
                        remote = remote,
                        cursor = remote.baseCursor,
                        cols = remote.cols ?: cols,
                        rows = remote.rows ?: rows,
                        emulator = newTerminalEmulator(remote.cols ?: cols, remote.rows ?: rows),
                    )
                    renderTerminalSessionSummary()
                    activateTerminalSession(sessionId, followLatest = true)
                }.onFailure { error ->
                    setTerminalState(getString(R.string.terminal_create_failed), Tone.WARNING)
                    appendActiveDiagnostic(getString(
                        R.string.terminal_diag_create_failed,
                        error.message ?: error.javaClass.simpleName,
                    ))
                }
            }
        }.start()
    }

    private fun activateTerminalSession(sessionId: String, followLatest: Boolean) {
        val target = terminalSessions[sessionId] ?: return
        if (activeSessionId != sessionId) saveActiveUiState()
        stopTerminalPolling()
        terminalResizeGeneration++
        activeSessionId = sessionId
        if (followLatest) target.scrollPolicy.onSessionAdopted()
        terminalInput.setText(target.pendingInput)
        terminalInput.setSelection(terminalInput.text?.length ?: 0)
        renderTerminalTabs()
        renderTerminalSurface(target)
        updateActiveTerminalStateLabel()
        startTerminalPolling()
        if (target.remote.running) scheduleTerminalResize()
        terminalInput.requestFocus()
    }

    private fun switchTerminalSession(sessionId: String) {
        if (agentLifecycleTransition || activeSessionId == sessionId) return
        activateTerminalSession(sessionId, followLatest = false)
    }

    private fun closeActiveTerminalSession() {
        if (agentLifecycleTransition) return
        val sessionId = activeSessionId
        val state = sessionId?.let(terminalSessions::get)
        if (sessionId.isNullOrBlank() || state == null) {
            setTerminalState(getString(R.string.terminal_no_current_session))
            return
        }
        val token = tokenOrNull(silentFailure = false) ?: return
        saveActiveUiState()
        stopTerminalPolling()
        terminalResizeGeneration++
        setTerminalState(getString(R.string.terminal_closing_session, state.remote.ordinal))
        Thread {
            val result = terminalClient.closeSession(token, sessionId)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess {
                    terminalSessions.remove(sessionId)
                    activeSessionId = null
                    terminalInput.setText("")
                    val next = TerminalSessionSelectionPolicy.choose(
                        preferredSessionId = null,
                        currentSessionId = null,
                        sessions = terminalSessions.map { (id, local) ->
                            TerminalSessionSelectionPolicy.Item(id, local.remote.ordinal, local.remote.running)
                        },
                    )
                    renderTerminalSessionSummary()
                    if (next != null) {
                        activateTerminalSession(next, followLatest = true)
                    } else {
                        renderTerminalTabs()
                        setTerminalState(getString(R.string.terminal_closed_no_session))
                        terminalOutputText.text = getString(R.string.terminal_no_sessions)
                        terminalOutputScroll.scrollTo(0, 0)
                    }
                }.onFailure { error ->
                    setTerminalState(getString(R.string.terminal_close_failed), Tone.WARNING)
                    appendTerminalDiagnostic(
                        state,
                        getString(R.string.terminal_diag_close_failed, error.message ?: error.javaClass.simpleName),
                    )
                    startTerminalPolling()
                }
            }
        }.start()
    }

    private fun sendTerminalLine() {
        val session = requireActiveRunningSession() ?: return
        val value = terminalInput.text?.toString().orEmpty()
        terminalInput.setText("")
        session.pendingInput = ""
        session.scrollPolicy.onInputSent()
        scrollTerminalOutputToBottom(session)
        sendTerminalBytes(session.remote.sessionId!!, TerminalInputComposer.line(value))
        terminalInput.requestFocus()
    }

    private fun sendTerminalShortcut(bytes: ByteArray) {
        val session = requireActiveRunningSession() ?: return
        val pendingText = terminalInput.text?.toString().orEmpty()
        terminalInput.setText("")
        session.pendingInput = ""
        session.scrollPolicy.onInputSent()
        scrollTerminalOutputToBottom(session)
        sendTerminalBytes(session.remote.sessionId!!, TerminalInputComposer.shortcut(pendingText, bytes))
        terminalInput.requestFocus()
    }

    private fun requireActiveRunningSession(): LocalTerminalSession? {
        val session = activeTerminalSessionOrNull()
        if (session == null) {
            setTerminalState(getString(R.string.terminal_select_session), Tone.WARNING)
            return null
        }
        if (!session.remote.running) {
            setTerminalState(getString(R.string.terminal_session_exited), Tone.WARNING)
            return null
        }
        return session
    }

    private fun sendTerminalBytes(sessionId: String, bytes: ByteArray) {
        if (agentLifecycleTransition) return
        val token = tokenOrNull(silentFailure = false) ?: return
        terminalInputExecutor.execute {
            val result = terminalClient.sendInput(token, sessionId, bytes)
            runOnUiThread {
                if (isFinishing || isDestroyed || agentLifecycleTransition) return@runOnUiThread
                result.onFailure { error ->
                    val session = terminalSessions[sessionId] ?: return@onFailure
                    appendTerminalDiagnostic(
                        session,
                        getString(R.string.terminal_diag_send_failed, error.message ?: error.javaClass.simpleName),
                    )
                    if (activeSessionId == sessionId) {
                        setTerminalState(getString(R.string.terminal_send_failed_state), Tone.WARNING)
                    }
                }
            }
        }
    }

    private fun startTerminalPolling() {
        if (agentLifecycleTransition || activeSessionId == null) return
        val generation = ++terminalPollGeneration
        pollTerminalOnce(generation, delayMs = 0L)
    }

    private fun stopTerminalPolling() {
        terminalPollGeneration++
    }

    private fun pollTerminalOnce(generation: Int, delayMs: Long) {
        mainHandler.postDelayed({
            if (generation != terminalPollGeneration || agentLifecycleTransition) return@postDelayed
            val sessionId = activeSessionId ?: return@postDelayed
            val session = terminalSessions[sessionId] ?: return@postDelayed
            val token = tokenOrNull(silentFailure = true) ?: return@postDelayed
            val cursor = session.cursor
            Thread {
                val result = terminalClient.readOutput(token, sessionId, cursor)
                runOnUiThread {
                    if (
                        isFinishing || isDestroyed || agentLifecycleTransition ||
                        generation != terminalPollGeneration || activeSessionId != sessionId
                    ) return@runOnUiThread
                    val current = terminalSessions[sessionId] ?: return@runOnUiThread
                    result.onSuccess { output ->
                        if (output.truncated) {
                            current.emulator = newTerminalEmulator(current.cols, current.rows)
                            current.diagnostics.clear()
                            appendTerminalDiagnostic(current, getString(R.string.terminal_diag_truncated))
                        }
                        if (output.bytes.isNotEmpty()) current.emulator.feed(output.bytes)
                        current.cursor = output.nextCursor
                        current.remote = current.remote.copy(
                            running = output.running,
                            exitCode = output.exitCode,
                            baseCursor = output.baseCursor,
                            nextCursor = max(current.remote.nextCursor, output.nextCursor),
                        )
                        renderTerminalSurface(current)
                        renderTerminalTabs()
                        renderTerminalSessionSummary()
                        updateActiveTerminalStateLabel()
                        if (output.running || output.bytes.isNotEmpty()) {
                            pollTerminalOnce(
                                generation,
                                if (output.running) TERMINAL_POLL_INTERVAL_MS else 0L,
                            )
                        }
                    }.onFailure { error ->
                        if (agentLifecycleTransition) return@onFailure
                        setTerminalState(getString(R.string.terminal_read_failed), Tone.WARNING)
                        appendTerminalDiagnostic(
                            current,
                            getString(R.string.terminal_diag_read_failed, error.message ?: error.javaClass.simpleName),
                        )
                    }
                }
            }.start()
        }, delayMs)
    }

    private fun scheduleTerminalResize() {
        val sessionId = activeSessionId ?: return
        val session = terminalSessions[sessionId] ?: return
        if (
            agentLifecycleTransition || !session.remote.running ||
            !::terminalOutputScroll.isInitialized ||
            terminalOutputScroll.width <= 0 || terminalOutputScroll.height <= 0
        ) return
        val generation = ++terminalResizeGeneration
        mainHandler.postDelayed({
            if (
                generation != terminalResizeGeneration || agentLifecycleTransition ||
                activeSessionId != sessionId
            ) return@postDelayed
            resizeTerminalToView(sessionId)
        }, TERMINAL_RESIZE_DEBOUNCE_MS)
    }

    private fun resizeTerminalToView(sessionId: String) {
        val session = terminalSessions[sessionId] ?: return
        if (!session.remote.running) return
        val (cols, rows) = terminalViewSize()
        if (cols == session.cols && rows == session.rows) return
        val token = tokenOrNull(silentFailure = true) ?: return
        Thread {
            val result = terminalClient.resizeSession(token, sessionId, cols, rows)
            runOnUiThread {
                if (isFinishing || isDestroyed || agentLifecycleTransition) return@runOnUiThread
                val current = terminalSessions[sessionId] ?: return@runOnUiThread
                result.onSuccess { remote ->
                    current.remote = remote
                    current.cols = remote.cols ?: cols
                    current.rows = remote.rows ?: rows
                    current.emulator.resize(current.cols, current.rows)
                    if (activeSessionId == sessionId) {
                        renderTerminalSurface(current)
                        updateActiveTerminalStateLabel()
                    }
                }.onFailure { error ->
                    appendTerminalDiagnostic(
                        current,
                        getString(R.string.terminal_diag_resize_failed, error.message ?: error.javaClass.simpleName),
                    )
                }
            }
        }.start()
    }

    private fun saveActiveUiState() {
        val session = activeTerminalSessionOrNull() ?: return
        if (::terminalInput.isInitialized) session.pendingInput = terminalInput.text?.toString().orEmpty()
        if (::terminalOutputScroll.isInitialized) session.viewportScrollY = terminalOutputScroll.scrollY
    }

    private fun activeTerminalSessionOrNull(): LocalTerminalSession? = activeSessionId?.let(terminalSessions::get)

    private fun appendActiveDiagnostic(value: String) {
        activeTerminalSessionOrNull()?.let { appendTerminalDiagnostic(it, value) }
    }

    private fun appendTerminalDiagnostic(session: LocalTerminalSession, value: String) {
        if (session.diagnostics.size >= MAX_TERMINAL_DIAGNOSTICS) session.diagnostics.removeFirst()
        session.diagnostics.addLast(value)
        if (activeSessionId == session.remote.sessionId) renderTerminalSurface(session)
    }

    private fun renderTerminalSurface(session: LocalTerminalSession) {
        if (activeSessionId != session.remote.sessionId) return
        val preservedScrollY = session.viewportScrollY
        val terminalText = session.emulator.renderText(TERMINAL_UI_SCROLLBACK_LINES)
        val diagnosticText = session.diagnostics.joinToString("\n")
        terminalOutputText.text = when {
            terminalText.isBlank() -> diagnosticText
            diagnosticText.isBlank() -> terminalText
            else -> "$terminalText\n$diagnosticText"
        }
        terminalOutputScroll.post {
            if (activeSessionId != session.remote.sessionId) return@post
            if (session.scrollPolicy.followsLatest) {
                scrollTerminalOutputToBottom(session)
            } else {
                val target = preservedScrollY.coerceIn(0, terminalOutputMaxScroll())
                terminalOutputScroll.scrollTo(0, target)
                session.viewportScrollY = target
            }
        }
    }

    private fun scrollTerminalOutputToBottom(session: LocalTerminalSession) {
        if (activeSessionId != session.remote.sessionId) return
        terminalOutputScroll.post {
            if (activeSessionId != session.remote.sessionId) return@post
            val target = terminalOutputMaxScroll()
            terminalOutputScroll.scrollTo(0, target)
            session.viewportScrollY = target
        }
    }

    private fun terminalOutputMaxScroll(): Int {
        if (!::terminalOutputScroll.isInitialized || !::terminalOutputText.isInitialized) return 0
        val childHeight = terminalOutputText.height
        val viewport = (
            terminalOutputScroll.height - terminalOutputScroll.paddingTop - terminalOutputScroll.paddingBottom
        ).coerceAtLeast(0)
        return (childHeight - viewport).coerceAtLeast(0)
    }

    private fun isTerminalOutputNearBottom(): Boolean {
        if (!::terminalOutputScroll.isInitialized) return true
        val remaining = terminalOutputMaxScroll() - terminalOutputScroll.scrollY
        val lineHeight = (terminalOutputText.paint.fontMetrics.bottom - terminalOutputText.paint.fontMetrics.top)
            .toInt().coerceAtLeast(1)
        return remaining <= max(dp(20), lineHeight * 2)
    }

    private fun renderTerminalTabs() {
        if (!::terminalTabsHost.isInitialized) return
        terminalTabsHost.removeAllViews()
        if (terminalSessions.isEmpty()) {
            terminalTabsHost.addView(text(getString(R.string.terminal_tabs_empty), 12f, false).apply {
                setTextColor(Color.rgb(145, 151, 162))
                setPadding(0, dp(3), 0, dp(8))
            })
            return
        }
        terminalSessions.values.sortedBy { it.remote.ordinal }.forEach { session ->
            val sessionId = session.remote.sessionId ?: return@forEach
            val active = sessionId == activeSessionId
            val status = if (session.remote.running) "" else getString(R.string.terminal_tab_exited)
            val label = (if (active) "● " else "") + "T${session.remote.ordinal}$status"
            terminalTabsHost.addView(shortcutButton(label) { switchTerminalSession(sessionId) })
        }
    }

    private fun renderTerminalSessionSummary(maxSessions: Int = RuntimeAgentProtocol.MAX_TERMINAL_SESSIONS) {
        if (!::terminalSessionsText.isInitialized) return
        val running = terminalSessions.values.count { it.remote.running }
        terminalSessionsText.text = getString(
            R.string.terminal_sessions_summary,
            terminalSessions.size,
            maxSessions,
            running,
        )
    }

    private fun updateActiveTerminalStateLabel() {
        val session = activeTerminalSessionOrNull()
        if (session == null) {
            setTerminalState(getString(R.string.terminal_no_selected_session))
            return
        }
        if (session.remote.running) {
            setTerminalState(
                getString(
                    R.string.terminal_connected_state,
                    session.remote.ordinal,
                    session.remote.pid ?: -1,
                    session.cols,
                    session.rows,
                ),
                Tone.CONNECTED,
            )
        } else {
            setTerminalState(
                getString(
                    R.string.terminal_exited_state,
                    session.remote.ordinal,
                    session.remote.exitCode?.toString() ?: "?",
                ),
            )
        }
    }

    private fun terminalViewSize(): Pair<Int, Int> {
        if (
            !::terminalOutputScroll.isInitialized ||
            terminalOutputScroll.width <= 0 || terminalOutputScroll.height <= 0
        ) return DEFAULT_TERMINAL_COLS to DEFAULT_TERMINAL_ROWS

        val contentWidth = (
            terminalOutputScroll.width - terminalOutputText.paddingLeft - terminalOutputText.paddingRight
        ).coerceAtLeast(1)
        val contentHeight = (
            terminalOutputScroll.height - terminalOutputText.paddingTop - terminalOutputText.paddingBottom
        ).coerceAtLeast(1)
        val charWidth = terminalOutputText.paint.measureText("M").coerceAtLeast(1f)
        val metrics = terminalOutputText.paint.fontMetrics
        val lineHeight = (metrics.bottom - metrics.top).coerceAtLeast(1f)
        val cols = floor(contentWidth / charWidth).toInt().coerceIn(
            RuntimeAgentTerminalClient.MIN_COLS,
            RuntimeAgentTerminalClient.MAX_COLS,
        )
        val rows = floor(contentHeight / lineHeight).toInt().coerceIn(
            RuntimeAgentTerminalClient.MIN_ROWS,
            RuntimeAgentTerminalClient.MAX_ROWS,
        )
        return cols to rows
    }

    private fun clearAllTerminalLocalState(label: String) {
        stopTerminalPolling()
        terminalResizeGeneration++
        terminalSessions.clear()
        activeSessionId = null
        restoredPreferredSessionId = null
        if (::terminalInput.isInitialized) terminalInput.setText("")
        if (::terminalOutputScroll.isInitialized) terminalOutputScroll.scrollTo(0, 0)
        renderTerminalTabs()
        renderTerminalSessionSummary()
        if (::terminalStateText.isInitialized) setTerminalState(label)
    }

    private fun tokenOrNull(silentFailure: Boolean): String? =
        runCatching { tokenStore.getOrCreate() }
            .getOrElse {
                setState(getString(R.string.terminal_agent_token_failed), Tone.WARNING)
                if (!silentFailure) outputText.text = it.message ?: it.javaClass.simpleName
                null
            }

    private fun renderRuntimeResult(result: RuntimeResult) {
        outputText.text = buildString {
            appendLine("executionId = ${result.executionId}")
            appendLine("exitCode = ${result.exitCode}")
            if (result.stdout.isNotBlank()) {
                appendLine("\n--- stdout ---")
                append(result.stdout.trimEnd())
            }
            if (result.stderr.isNotBlank()) {
                appendLine("\n\n--- stderr ---")
                append(result.stderr.trimEnd())
            }
            if (result.internalErrorMessage.isNotBlank()) {
                appendLine("\n\ntermuxError = ${result.internalErrorMessage}")
            }
        }
    }

    private fun setState(value: String, tone: Tone = Tone.NORMAL) {
        stateText.text = value
        stateText.setTextColor(colorFor(tone))
    }

    private fun setTerminalState(value: String, tone: Tone = Tone.NORMAL) {
        terminalStateText.text = value
        terminalStateText.setTextColor(colorFor(tone))
    }

    private fun colorFor(tone: Tone): Int = when (tone) {
        Tone.CONNECTED -> Color.rgb(170, 224, 190)
        Tone.WARNING -> Color.rgb(240, 184, 120)
        Tone.NORMAL -> Color.rgb(190, 194, 204)
    }

    private fun appVersionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull().orEmpty().ifBlank { "?" }

    private fun section(value: String) = text(value, 16f, true).apply {
        setTextColor(Color.WHITE)
        setPadding(0, dp(20), 0, dp(8))
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(7) }
    }

    private fun shortcutButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        isFocusable = false
        isFocusableInTouchMode = false
        textSize = 12f
        setOnClickListener {
            action()
            if (::terminalInput.isInitialized) terminalInput.requestFocus()
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginEnd = dp(5)
            bottomMargin = dp(7)
        }
    }

    private fun text(value: String, size: Float, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size
        gravity = Gravity.START
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val STATE_ACTIVE_SESSION_ID = "v054.activeTerminalSessionId"
        private const val START_HEALTH_ATTEMPTS = 4
        private const val START_HEALTH_RETRY_DELAY_MS = 200L
        private const val TERMINAL_POLL_INTERVAL_MS = 220L
        private const val TERMINAL_RESIZE_DEBOUNCE_MS = 160L
        private const val TERMINAL_SCROLL_SETTLE_MS = 180L
        private const val TERMINAL_SCROLLBACK_LINES = 2000
        private const val TERMINAL_UI_SCROLLBACK_LINES = 500
        private const val MAX_TERMINAL_DIAGNOSTICS = 8
        private const val DEFAULT_TERMINAL_COLS = 80
        private const val DEFAULT_TERMINAL_ROWS = 24
        private val PENDING_ACTIONS = mutableMapOf<Int, Action>()

        private fun newTerminalEmulator(cols: Int, rows: Int) = TerminalEmulator(
            initialCols = cols,
            initialRows = rows,
            scrollbackLimit = TERMINAL_SCROLLBACK_LINES,
        )
    }
}
