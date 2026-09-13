package com.siftalpha.studio.runtime

import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxProotDnsBridgeTest {
    @Test
    fun resolverBridgeUsesTermuxResolverWithoutHardCodedPublicDns() {
        val shell = TermuxProotDnsBridge.setupShell()

        assertTrue(shell.contains("${'$'}PREFIX/etc/resolv.conf"))
        assertTrue(shell.contains("SIFTALPHA_TERMUX_RESOLV:/etc/resolv.conf"))
        assertTrue(shell.contains("command -v proot-distro"))
        assertTrue(shell.contains("SIFTALPHA_REAL_PROOT_DISTRO"))
        assertTrue(!shell.contains("8.8.8.8"))
        assertTrue(!shell.contains("1.1.1.1"))
    }
}
