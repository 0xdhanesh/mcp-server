package net.portswigger.mcp

import burp.api.montoya.MontoyaApi
import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.tools.ToolActivity
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class McpServerIntegrationTest {
    private var accessKeyRequired = false
    private val client = TestSseMcpClient()
    private val api = mockk<MontoyaApi>(relaxed = true)
    private val serverManager = KtorServerManager(api)
    private val testPort = findAvailablePort()
    private val persistedObject = mockk<PersistedObject>()
    private val serverReady = CountDownLatch(1)
    private var serverStartFailure: Throwable? = null

    init {
        every { persistedObject.getBoolean(any()) } returns true
        every { persistedObject.getBoolean("requireMcpAccessKey") } answers { accessKeyRequired }
        every { persistedObject.getString(any()) } returns "127.0.0.1"
        every { persistedObject.getString("mcpAccessKey") } returns "test-access-key"
        every { persistedObject.getInteger("port") } returns testPort
        every { persistedObject.setBoolean(any(), any()) } returns Unit
        every { persistedObject.setBoolean("requireMcpAccessKey", any()) } answers {
            accessKeyRequired = secondArg<Boolean>()
        }
        every { persistedObject.setString(any(), any()) } returns Unit
        every { persistedObject.setInteger(any(), any()) } returns Unit
    }

    private val mockLogging = mockk<Logging>().apply {
        every { logToError(any<String>()) } returns Unit
        every { logToOutput(any<String>()) } returns Unit
    }

    private val config = McpConfig(persistedObject, mockLogging)

    @BeforeEach
    fun setup() {
        ToolActivity.clear()
        serverManager.start(config) { state ->
            when (state) {
                ServerState.Running -> serverReady.countDown()
                is ServerState.Failed -> {
                    serverStartFailure = state.exception
                    serverReady.countDown()
                }
                else -> Unit
            }
        }
        check(serverReady.await(10, TimeUnit.SECONDS)) { "Server failed to start after timeout" }
        serverStartFailure?.let { throw IllegalStateException("Server failed to start", it) }
    }

    private fun findAvailablePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    @AfterEach
    fun tearDown() {
        runBlocking {
            if (client.isConnected()) {
                client.close()
            }
        }
        serverManager.stop {}
        ToolActivity.clear()
    }

    @Test
    fun `MCP listener rejects requests without its access key`() {
        accessKeyRequired = true
        val url = URL("http://127.0.0.1:$testPort")
        val missing = url.openConnection() as HttpURLConnection
        missing.connectTimeout = 3000
        missing.readTimeout = 3000
        assertEquals(401, missing.responseCode)
        missing.disconnect()

        val wrong = url.openConnection() as HttpURLConnection
        wrong.setRequestProperty("X-Burp-Mcp-Key", "wrong-key")
        wrong.connectTimeout = 3000
        wrong.readTimeout = 3000
        assertEquals(401, wrong.responseCode)
        wrong.disconnect()

        val valid = url.openConnection() as HttpURLConnection
        valid.setRequestProperty("X-Burp-Mcp-Key", "test-access-key")
        valid.connectTimeout = 3000
        valid.readTimeout = 3000
        assertEquals(200, valid.responseCode)
        valid.disconnect()
    }

    @Test
    fun `server should accept connections and list tools`() = runBlocking {
        try {
            client.connectToServer("http://127.0.0.1:${testPort}")
            assertTrue(client.isConnected(), "Client should be connected to server")
            
            val tools = client.listTools()
            assertFalse(tools.isEmpty(), "Server should have registered tools")
            
            val toolNames = tools.map { it.name }
            assertTrue(toolNames.contains("output_project_options"), "Server should have output_project_options tool")
            assertTrue(toolNames.contains("output_user_options"), "Server should have output_user_options tool")
            assertTrue(toolNames.contains("list_logger_requests"), "Server should have list_logger_requests tool")
            assertTrue(toolNames.contains("set_logger_comment"), "Server should have set_logger_comment tool")
            assertTrue(toolNames.contains("acquire_session_var"), "Server should have acquire_session_var tool")
            assertTrue(toolNames.contains("acquire_configured_session_var"), "Server should have acquire_configured_session_var tool")
            assertTrue(toolNames.contains("get_session_auth_status"), "Server should have get_session_auth_status tool")
            
            val pingResult = client.ping()
            assertNotNull(pingResult, "Ping should return a result")

            val result = client.callTool("url_encode", mapOf("content" to "activity-test-value"))
            assertNotNull(result)
            val call = ToolActivity.snapshot().single()
            assertEquals("url_encode", call.toolName)
            assertTrue(call.input.contains("activity-test-value"))
            assertTrue(call.output.contains("content"))
        } catch (e: Exception) {
            fail("Connection failed: ${e.message}")
        }
    }
}
