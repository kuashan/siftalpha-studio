package com.siftalpha.studio.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebProjectDetectorTest {

    @Test
    fun detectsStreamlitFromRequirementsAndUsesDefaultPort() {
        val result = WebProjectDetector.detect(
            requirements = "requests==2.34.2\nstreamlit>=1.40\n",
            pyproject = null,
            pythonSources = emptyList(),
        )
        assertEquals("streamlit", result?.framework)
        assertEquals("dependencies", result?.source)
        assertEquals("127.0.0.1", result?.host)
        assertEquals(8501, result?.port)
    }

    @Test
    fun detectsFastApiFromPyproject() {
        val result = WebProjectDetector.detect(
            requirements = null,
            pyproject = "dependencies = [\"fastapi>=0.115\", \"uvicorn\"]",
            pythonSources = emptyList(),
        )
        assertEquals("fastapi", result?.framework)
        assertEquals("dependencies", result?.source)
        assertEquals(8000, result?.port)
    }

    @Test
    fun runCommandPortOverridesFrameworkDefault() {
        val result = WebProjectDetector.detect(
            requirements = "fastapi\nuvicorn\n",
            pyproject = null,
            pythonSources = listOf("from fastapi import FastAPI\napp = FastAPI()"),
            runCommand = "uvicorn app:app --host 127.0.0.1 --port 9234",
        )
        assertEquals("fastapi", result?.framework)
        assertEquals(9234, result?.port)
    }

    @Test
    fun detectsGradioFromSource() {
        val result = WebProjectDetector.detect(
            requirements = null,
            pyproject = null,
            pythonSources = listOf("import gradio as gr\napp = gr.Interface(lambda x: x, 'text', 'text')"),
        )
        assertEquals("gradio", result?.framework)
        assertEquals("source", result?.source)
        assertEquals(7860, result?.port)
    }

    @Test
    fun extractsFlaskPortFromSource() {
        val result = WebProjectDetector.detect(
            requirements = null,
            pyproject = null,
            pythonSources = listOf(
                "from flask import Flask\n" +
                    "app = Flask(__name__)\n" +
                    "app.run(host='0.0.0.0', port=5011)\n",
            ),
        )
        assertEquals("flask", result?.framework)
        assertEquals(5011, result?.port)
        assertEquals("127.0.0.1", result?.host)
    }

    @Test
    fun detectsStdlibHttpServerAndLiteralPortFromSource() {
        val result = WebProjectDetector.detect(
            requirements = null,
            pyproject = null,
            pythonSources = listOf(
                "from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer\n" +
                    "server = ThreadingHTTPServer(('127.0.0.1', 9127), BaseHTTPRequestHandler)\n" +
                    "server.serve_forever()\n",
            ),
        )
        assertEquals("python-http", result?.framework)
        assertEquals("source", result?.source)
        assertEquals(9127, result?.port)
    }

    @Test
    fun resolvesSimplePortConstantUsedByHttpServer() {
        val result = WebProjectDetector.detect(
            requirements = null,
            pyproject = null,
            pythonSources = listOf(
                "from http.server import ThreadingHTTPServer\n" +
                    "PORT = 9127\n" +
                    "server = ThreadingHTTPServer(('127.0.0.1', PORT), Handler)\n",
            ),
        )
        assertEquals("python-http", result?.framework)
        assertEquals(9127, result?.port)
    }

    @Test
    fun resolvesServerPortWhenBindAndPortAreBothVariables() {
        val result = WebProjectDetector.detect(
            requirements = null,
            pyproject = null,
            pythonSources = listOf(
                "from http.server import ThreadingHTTPServer\n" +
                    "SERVER_BIND = '127.0.0.1'\n" +
                    "SERVER_PORT = 9127\n" +
                    "server = ThreadingHTTPServer((SERVER_BIND, SERVER_PORT), Handler)\n" +
                    "server.serve_forever()\n",
            ),
        )
        assertEquals("python-http", result?.framework)
        assertEquals("source", result?.source)
        assertEquals("127.0.0.1", result?.host)
        assertEquals(9127, result?.port)
    }

    @Test
    fun resolvesEnvBackedPortVariableUsedByHttpServer() {
        val result = WebProjectDetector.detect(
            requirements = null,
            pyproject = null,
            pythonSources = listOf(
                "import os\n" +
                    "from http.server import ThreadingHTTPServer\n" +
                    "SERVER_BIND = '127.0.0.1'\n" +
                    "LISTEN_ON = int(os.getenv('SIFTALPHA_TEST_PORT', '9127'))\n" +
                    "server = ThreadingHTTPServer((SERVER_BIND, LISTEN_ON), Handler)\n" +
                    "server.serve_forever()\n",
            ),
        )
        assertEquals("python-http", result?.framework)
        assertEquals("source", result?.source)
        assertEquals("127.0.0.1", result?.host)
        assertEquals(9127, result?.port)
    }

    @Test
    fun resolvesEnvironGetDefaultPortUsedByHttpServer() {
        val result = WebProjectDetector.detect(
            requirements = null,
            pyproject = null,
            pythonSources = listOf(
                "import os\n" +
                    "from http.server import HTTPServer\n" +
                    "HOST = '127.0.0.1'\n" +
                    "PORT_FROM_ENV = int(os.environ.get('PORT', 9346))\n" +
                    "HTTPServer((HOST, PORT_FROM_ENV), Handler).serve_forever()\n",
            ),
        )
        assertEquals("python-http", result?.framework)
        assertEquals(9346, result?.port)
    }

    @Test
    fun detectsPythonModuleHttpServerFromRunCommand() {
        val result = WebProjectDetector.detect(
            requirements = null,
            pyproject = null,
            pythonSources = emptyList(),
            runCommand = "python3 -m http.server 9345 --bind 127.0.0.1",
        )
        assertEquals("python-http", result?.framework)
        assertEquals("run-command", result?.source)
        assertEquals(9345, result?.port)
    }

    @Test
    fun detectsDashDependency() {
        val result = WebProjectDetector.detect(
            requirements = "plotly\ndash==2.18.2\n",
            pyproject = null,
            pythonSources = emptyList(),
        )
        assertEquals("dash", result?.framework)
        assertEquals(8050, result?.port)
    }

    @Test
    fun ordinaryPythonIsNotMisclassified() {
        val result = WebProjectDetector.detect(
            requirements = "requests\nwebsocket-client\n",
            pyproject = null,
            pythonSources = listOf("import sqlite3\nprint('hello')"),
        )
        assertNull(result)
    }
}
