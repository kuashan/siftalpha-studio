package com.siftalpha.studio.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectConfigurationInspectorTest {

    @Test
    fun `required env parser preserves explicit requirement semantics`() {
        val metadata = """
            {
              "name": "sample",
              "requiredEnv": [
                {
                  "name": "GEMINI_API_KEY",
                  "secret": true,
                  "required": true,
                  "description": "Gemini credential"
                },
                {
                  "name": "DATABASE_PATH",
                  "secret": false,
                  "required": true,
                  "description": "Runtime-local database"
                },
                {
                  "name": "OPTIONAL_TOKEN",
                  "secret": true,
                  "required": false
                }
              ]
            }
        """.trimIndent()

        val result = ProjectConfigurationInspector.parseRequiredEnv(metadata)

        assertEquals(3, result.size)
        assertEquals("GEMINI_API_KEY", result[0].name)
        assertTrue(result[0].secret)
        assertTrue(result[0].required)
        assertEquals("Gemini credential", result[0].description)
        assertEquals("DATABASE_PATH", result[1].name)
        assertFalse(result[1].secret)
        assertTrue(result[1].required)
        assertEquals("OPTIONAL_TOKEN", result[2].name)
        assertFalse(result[2].required)
    }

    @Test
    fun `string required env entries are supported without inventing secret status`() {
        val result = ProjectConfigurationInspector.parseRequiredEnv(
            """{"requiredEnv":["DATABASE_PATH","WORKERS"]}""",
        )

        assertEquals(listOf("DATABASE_PATH", "WORKERS"), result.map { it.name })
        assertTrue(result.all { it.required })
        assertTrue(result.none { it.secret })
    }

    @Test
    fun `duplicate and invalid env names are ignored safely`() {
        val result = ProjectConfigurationInspector.parseRequiredEnv(
            """
                {
                  "requiredEnv": [
                    {"name":"VALID_KEY","secret":true},
                    {"name":"VALID_KEY","secret":false},
                    {"name":"BAD-NAME","secret":true},
                    {"name":"1BAD","secret":true}
                  ]
                }
            """.trimIndent(),
        )

        assertEquals(1, result.size)
        assertEquals("VALID_KEY", result.single().name)
        assertTrue(result.single().secret)
    }

    @Test
    fun `configured env parser never returns values`() {
        val text = """
            # comment
            GEMINI_API_KEY=real-secret-value
            EMPTY=
            QUOTED_EMPTY=""
            export DATABASE_PATH=/root/data/app.db
            INVALID-NAME=value
        """.trimIndent()

        val result = ProjectConfigurationInspector.parseConfiguredEnvKeys(text)

        assertEquals(setOf("GEMINI_API_KEY", "DATABASE_PATH"), result)
        assertFalse(result.any { "real-secret-value" in it })
    }

    @Test
    fun `env example candidates include commented assignments but are only candidates`() {
        val result = ProjectConfigurationInspector.parseEnvCandidateKeys(
            """
                GEMINI_API_KEY=
                # TUSHARE_TOKEN=
                # export OPENAI_API_KEY=
                PORT=8000
                # plain comment
            """.trimIndent(),
        )

        assertEquals(listOf("GEMINI_API_KEY", "TUSHARE_TOKEN", "OPENAI_API_KEY", "PORT"), result)
        assertTrue(ProjectConfigurationInspector.looksSensitive("GEMINI_API_KEY"))
        assertTrue(ProjectConfigurationInspector.looksSensitive("TUSHARE_TOKEN"))
        assertFalse(ProjectConfigurationInspector.looksSensitive("PORT"))
    }

    @Test
    fun `missing required env array is not treated as an inferred requirement`() {
        val metadata = """{"name":"sample","secrets":{"binanceApi":true}}"""

        assertTrue(ProjectConfigurationInspector.parseRequiredEnv(metadata).isEmpty())
    }

    @Test
    fun `python direct environment access becomes required configuration`() {
        val result = ProjectConfigurationInspector.parsePythonConfiguration(
            """
                import os
                api_key = os.environ["GEMINI_API_KEY"]
                database = os.environ.get("DATABASE_PATH")
            """.trimIndent(),
        )

        assertEquals(listOf("GEMINI_API_KEY"), result.required.map { it.name })
        assertEquals(listOf("DATABASE_PATH"), result.candidates.map { it.name })
        assertTrue(result.required.single().secret)
        assertFalse(result.candidates.single().secret)
    }

    @Test
    fun `python getenv candidates preserve source order and ignore common process variables`() {
        val result = ProjectConfigurationInspector.parsePythonConfiguration(
            """
                import os
                first = os.getenv("SERVICE_TOKEN")
                path = os.getenv("PATH")
                second = os.environ.get('PORT', '8080')
                duplicate = os.getenv("SERVICE_TOKEN")
            """.trimIndent(),
        )

        assertEquals(listOf("SERVICE_TOKEN", "PORT"), result.candidates.map { it.name })
        assertTrue(result.candidates.first().secret)
        assertFalse(result.candidates.last().secret)
    }

    @Test
    fun `direct python access wins over optional getter candidate`() {
        val result = ProjectConfigurationInspector.parsePythonConfiguration(
            """
                import os
                optional = os.getenv("SERVICE_TOKEN")
                required = os.environ["SERVICE_TOKEN"]
            """.trimIndent(),
        )

        assertEquals(listOf("SERVICE_TOKEN"), result.required.map { it.name })
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun `python detector preserves exact environment variable spelling`() {
        val result = ProjectConfigurationInspector.parsePythonConfiguration(
            """
                from os import environ
                value = environ["lower_case_key"]
                optional = environ.get("mixedCase", "default")
            """.trimIndent(),
        )

        assertEquals(listOf("lower_case_key"), result.required.map { it.name })
        assertEquals(listOf("mixedCase"), result.candidates.map { it.name })
    }
}
