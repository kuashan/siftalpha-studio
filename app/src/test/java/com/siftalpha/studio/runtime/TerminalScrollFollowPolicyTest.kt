package com.siftalpha.studio.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalScrollFollowPolicyTest {

    @Test
    fun userHistoryBrowsingDisablesFollowUntilReturningNearBottom() {
        val policy = TerminalScrollFollowPolicy()
        assertTrue(policy.followsLatest)

        policy.onUserTouchStarted()
        assertFalse(policy.followsLatest)

        policy.onUserTouchFinished(isNearBottom = false)
        assertFalse(policy.followsLatest)

        policy.onUserTouchStarted()
        policy.onUserTouchFinished(isNearBottom = true)
        assertTrue(policy.followsLatest)
    }

    @Test
    fun inputAndSessionRestoreReenableFollowLatest() {
        val policy = TerminalScrollFollowPolicy()
        policy.onUserTouchStarted()
        policy.onUserTouchFinished(isNearBottom = false)
        assertFalse(policy.followsLatest)

        policy.onInputSent()
        assertTrue(policy.followsLatest)

        policy.onUserTouchStarted()
        policy.onSessionAdopted()
        assertTrue(policy.followsLatest)
    }

    @Test
    fun resetReturnsToFollowLatest() {
        val policy = TerminalScrollFollowPolicy()
        policy.onUserTouchStarted()
        assertFalse(policy.followsLatest)
        policy.reset()
        assertTrue(policy.followsLatest)
    }
}
