package com.siftalpha.studio.runtime

/**
 * Small UI-independent policy for deciding whether Terminal output should follow the newest content.
 *
 * User history browsing must never be pulled back to the bottom by background output. Sending input or
 * restoring a Session intentionally re-enables follow-latest behavior, matching a normal interactive
 * terminal.
 */
class TerminalScrollFollowPolicy {
    var followsLatest: Boolean = true
        private set

    fun onSessionAdopted() {
        followsLatest = true
    }

    fun onInputSent() {
        followsLatest = true
    }

    fun onUserTouchStarted() {
        followsLatest = false
    }

    fun onUserTouchFinished(isNearBottom: Boolean) {
        followsLatest = isNearBottom
    }

    fun reset() {
        followsLatest = true
    }
}
