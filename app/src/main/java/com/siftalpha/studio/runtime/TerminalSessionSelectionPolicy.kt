package com.siftalpha.studio.runtime

/** UI-independent selection policy for a bounded set of Terminal Sessions. */
object TerminalSessionSelectionPolicy {
    data class Item(
        val sessionId: String,
        val ordinal: Int,
        val running: Boolean,
    )

    fun choose(
        preferredSessionId: String?,
        currentSessionId: String?,
        sessions: List<Item>,
    ): String? {
        if (sessions.isEmpty()) return null
        val ids = sessions.mapTo(hashSetOf()) { it.sessionId }
        preferredSessionId?.takeIf(ids::contains)?.let { return it }
        currentSessionId?.takeIf(ids::contains)?.let { return it }
        return sessions
            .filter { it.running }
            .maxByOrNull { it.ordinal }
            ?.sessionId
            ?: sessions.maxByOrNull { it.ordinal }?.sessionId
    }
}
