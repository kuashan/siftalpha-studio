package com.siftalpha.studio.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodeWebProjectDetectorTest {

    @Test
    fun viteUsesAuthoritativeDefaultPortWhenNoOverrideExists() {
        val detection = NodeWebProjectDetector.detect(
            dependencies = setOf("vite"),
            packageStartCommand = "vite",
            nodeSources = emptyList(),
        )
        assertEquals("vite", detection?.framework)
        assertEquals(5173, detection?.port)
        assertEquals("127.0.0.1", detection?.host)
    }

    @Test
    fun nextStartPortOverrideWinsOverFrameworkDefault() {
        val detection = NodeWebProjectDetector.detect(
            dependencies = setOf("next"),
            packageStartCommand = "next start -p 4310",
            nodeSources = emptyList(),
        )
        assertEquals("next", detection?.framework)
        assertEquals(4310, detection?.port)
    }

    @Test
    fun expressFindsProcessEnvFallbackPortFromSource() {
        val detection = NodeWebProjectDetector.detect(
            dependencies = setOf("express"),
            packageStartCommand = "node server.js",
            nodeSources = listOf(
                """
                    const express = require('express')
                    const port = process.env.PORT || 8000
                    express().listen(port)
                """.trimIndent(),
            ),
        )
        assertEquals("express", detection?.framework)
        assertEquals(8000, detection?.port)
        assertEquals("source", detection?.source)
    }

    @Test
    fun fastifyObjectListenPortIsDetected() {
        val detection = NodeWebProjectDetector.detect(
            dependencies = setOf("fastify"),
            packageStartCommand = "node app.mjs",
            nodeSources = listOf("app.listen({ port: 9090, host: '127.0.0.1' })"),
        )
        assertEquals("fastify", detection?.framework)
        assertEquals(9090, detection?.port)
    }

    @Test
    fun genericNodeScriptWithoutWebEvidenceIsNotInvented() {
        assertNull(
            NodeWebProjectDetector.detect(
                dependencies = setOf("lodash"),
                packageStartCommand = "node worker.js",
                nodeSources = listOf("setInterval(() => console.log('tick'), 1000)"),
            ),
        )
    }
}
