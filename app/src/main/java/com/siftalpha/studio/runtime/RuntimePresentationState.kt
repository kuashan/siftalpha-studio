package com.siftalpha.studio.runtime

/**
 * Resolves the user-facing lifecycle state from process state plus capability-specific readiness.
 *
 * A live process is necessary but not sufficient to call a Web project "RUNNING": while the
 * expected local endpoint is not accepting connections, the truthful user-facing state is STARTING.
 * Non-Web projects retain the process-backed RUNNING semantic because Studio has no stronger
 * universal readiness contract for arbitrary CLI/background programs.
 */
object RuntimePresentationState {
    fun resolve(
        runtimeState: RuntimeState,
        webExpected: Boolean,
        endpointReachable: Boolean,
    ): RuntimeState = when {
        runtimeState == RuntimeState.RUNNING && webExpected && !endpointReachable ->
            RuntimeState.STARTING
        else -> runtimeState
    }
}
