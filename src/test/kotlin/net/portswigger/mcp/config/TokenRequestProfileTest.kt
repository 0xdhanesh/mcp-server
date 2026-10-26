package net.portswigger.mcp.config

import burp.api.montoya.http.HttpService
import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import net.portswigger.mcp.config.components.TokenRequestPanel
import net.portswigger.mcp.security.VariableStore
import net.portswigger.mcp.security.RequestTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities

class TokenRequestProfileTest {
    private val values = mutableMapOf<String, String>()
    private val storage = mockk<PersistedObject>(relaxed = true).also { stored ->
        every { stored.getString(any()) } answers { values[firstArg()] }
        every { stored.setString(any(), any()) } answers { values[firstArg()] = secondArg() }
    }
    private val config = McpConfig(storage, mockk<Logging>(relaxed = true))
    private val store = VariableStore()

    @Test
    fun `HTTP fields persist and reload as a request template without credential values`() = withAuthService {
        val profile = TokenRequestProfile(
            mode = TokenRequestMode.HTTP_FIELDS,
            method = "POST",
            url = "https://auth.target/token",
            headers = "Content-Type: application/json",
            body = """{"grant_type":"password","username":"{{username}}","password":"{{password}}"}""",
            targetHost = "api.target",
            defaultIdentity = "user"
        )

        config.saveTokenRequestProfile(profile, store)
        val reloaded = McpConfig(storage, mockk<Logging>(relaxed = true)).tokenRequestProfile()
        assertEquals(profile, reloaded)
        assertTrue(profile.requestTemplate().contains("--url \"https://auth.target/token\""))
        assertTrue(values.getValue("tokenRequestProfileJson").contains("{{password}}"))
        assertFalse(values.getValue("tokenRequestProfileJson").contains("private-password"))
    }

    @Test
    fun `literal body credentials and inactive mode fields cannot be persisted`() = withAuthService {
        val profile = TokenRequestProfile(
            mode = TokenRequestMode.HTTP_FIELDS,
            url = "https://auth.target/token",
            headers = "Content-Type: application/json",
            body = """{"password":"private-password"}"""
        )
        assertThrows(IllegalArgumentException::class.java) { config.saveTokenRequestProfile(profile, store) }
        assertThrows(IllegalArgumentException::class.java) {
            config.saveTokenRequestProfile(profile.copy(body = """{"password":"{{password}}"}""", template = "private-password"), store)
        }
        assertFalse(values.containsKey("tokenRequestProfileJson"))
    }

    @Test
    fun `HTTP fields infer JSON and form content types when omitted`() = withAuthService {
        val json = TokenRequestProfile(
            mode = TokenRequestMode.HTTP_FIELDS, url = "https://auth.target/token",
            body = """{"username":"{{username}}"}"""
        )
        val form = json.copy(body = "grant_type=password&username={{username}}")
        config.saveTokenRequestProfile(json, store)
        assertTrue(json.requestTemplate().contains("Content-Type: application/json"))
        config.saveTokenRequestProfile(form, store)
        assertTrue(form.requestTemplate().contains("Content-Type: application/x-www-form-urlencoded"))
    }

    @Test
    fun `multiline curl from Burp can be saved with credential handles`() = withAuthService {
        val profile = TokenRequestProfile(
            template = """curl -X POST https://auth.target/token \
                |  -H "Content-Type: application/json" \
                |  -d '{"username":"{{username}}","password":"{{password}}"}'""".trimMargin()
        )
        config.saveTokenRequestProfile(profile, store)
        val parsed = RequestTemplate.parse(profile.requestTemplate())
        assertTrue(parsed.raw.startsWith("POST /token HTTP/1.1"))
        assertTrue(parsed.raw.contains("\"password\":\"{{password}}\""))
    }

    @Test
    fun `readiness selects one complete role but refuses to guess between multiple roles`() = withAuthService {
        val profile = TokenRequestProfile(
            template = "curl https://auth.target/token --json " +
                "'{\"username\":\"{{username}}\",\"password\":\"{{password}}\"}'"
        )
        store.put("username", "viewer-name", "viewer", "auth.target")
        assertEquals(null, profile.readiness(store).selectedRole())
        store.put("password", "viewer-secret", "viewer", "auth.target")
        assertEquals("viewer", profile.readiness(store).selectedRole())
        assertEquals("viewer", profile.copy(defaultIdentity = "admin").readiness(store).selectedRole())
        store.put("username", "admin-name", "admin", "auth.target")
        store.put("password", "admin-secret", "admin", "auth.target")
        assertEquals(null, profile.readiness(store).selectedRole())
        assertTrue(profile.readiness(store).summary().contains("Multiple roles"))
        assertEquals("viewer", profile.copy(defaultIdentity = "viewer").readiness(store).selectedRole())
    }

    @Test
    fun `stored username matching a role does not reject a handled token request`() = withAuthService {
        store.put("username", "admin", "admin", "auth.target")
        val profile = TokenRequestProfile(
            mode = TokenRequestMode.HTTP_FIELDS,
            url = "https://auth.target/token",
            body = """{"username":"{{username}}"}""",
            defaultIdentity = "admin"
        )
        config.saveTokenRequestProfile(profile, store)
        assertEquals(profile, config.tokenRequestProfile())
        assertFalse(values.getValue("tokenRequestProfileJson").contains("\"username\":\"admin\""))
    }

    @Test
    fun `raw HTTP request can use explicit HTTP transport on a nonstandard port`() {
        mockkStatic(HttpService::class)
        try {
            every { HttpService.httpService("127.0.0.1", 5000, false) } returns mockk()
            val profile = TokenRequestProfile(
                template = "POST /token HTTP/1.1\r\nHost: 127.0.0.1:5000\r\nContent-Type: application/json\r\n\r\n" +
                    """{"username":"{{username}}","password":"{{password}}"}""",
                rawHttpScheme = RawHttpScheme.HTTP
            )
            config.saveTokenRequestProfile(profile, store)
            assertTrue(RequestTemplate.parse(profile.requestTemplate()).raw.startsWith("POST /token HTTP/1.1"))
            assertEquals(profile, config.tokenRequestProfile())
            assertThrows(IllegalArgumentException::class.java) {
                RequestTemplate.parse("POST http://other.host/token HTTP/1.1\r\nHost: 127.0.0.1:5000\r\n\r\n")
            }
        } finally {
            unmockkStatic(HttpService::class)
        }
    }

    @Test
    fun `raw JSON token request rejects unquoted handles and incomplete Host`() = withAuthService {
        val valid = TokenRequestProfile(
            template = "POST /token HTTP/1.1\r\nHost: auth.target\r\n" +
                "Content-Type: application/json\r\n\r\n" +
                """{"username":"{{username}}","password":"{{password}}"}"""
        )
        config.saveTokenRequestProfile(valid, store)
        val invalidJson = valid.copy(template = valid.template.replace("\"{{username}}\"", "{{username}}"))
        assertEquals(
            "Invalid JSON body. Quote each {{name}} handle as a JSON string.",
            assertThrows(IllegalArgumentException::class.java) {
                config.saveTokenRequestProfile(invalidJson, store)
            }.message
        )
        val invalidHost = valid.copy(template = valid.template.replace("Host: auth.target", "Host: localhost:"))
        assertEquals(
            "Host header needs a hostname and, for a nonstandard port, a numeric port.",
            assertThrows(IllegalArgumentException::class.java) {
                config.saveTokenRequestProfile(invalidHost, store)
            }.message
        )
    }

    @Test
    fun `configured items show role bindings and JSON keys without stored values`() = withAuthService {
        config.saveTokenRequestProfile(TokenRequestProfile(
            template = "POST /token HTTP/1.1\r\nHost: auth.target\r\nContent-Type: application/json\r\n\r\n" +
                """{"username":"{{usrname}}","password":"{{pwd}}"}""",
            targetHost = "api.target"
        ), store)
        store.put("usrname", "alice-login", "alice", "auth.target")
        store.put("pwd", "alice-password", "alice", "auth.target")
        store.put("session", "alice-token", "alice", "api.target")
        store.put("usrname", "bob-login", "bob", "auth.target")
        store.put("usrname", "admin-login", "admin", "api.target")
        store.put("pwd", "admin-password", "admin", "api.target")
        SwingUtilities.invokeAndWait {
            val panel = TokenRequestPanel(config, store)
            val summary = components(panel.configuredPage).filterIsInstance<JTextArea>()
                .first { !it.isEditable && it.text.startsWith("Saved request:") }
            val table = components(panel.configuredPage).filterIsInstance<JTable>().single()
            assertTrue(summary.text.contains("username -> {{usrname}}, password -> {{pwd}}"))
            assertTrue(summary.text.contains("POST https://auth.target:443/token"))
            assertFalse(summary.text.contains("alice-password"))
            assertFalse(summary.text.contains("alice-token"))
            assertEquals(3, table.rowCount)
            val rows = (0 until table.rowCount).associate { row ->
                table.getValueAt(row, 0) as String to (0 until table.columnCount).map { table.getValueAt(row, it) as String }
            }
            assertEquals("None (ready to request)", rows.getValue("alice")[2])
            assertEquals("Stored", rows.getValue("alice")[3])
            assertEquals("{{pwd}}", rows.getValue("bob")[2])
            assertEquals("api.target", rows.getValue("admin")[4])
            store.put("pwd", "bob-password", "bob", "auth.target")
            components(panel.configuredPage).filterIsInstance<JButton>().first { it.text == "Refresh configured items" }.doClick()
            assertEquals("None (ready to request)", table.getValueAt(2, 2))
            assertFalse(summary.text.contains("bob-password"))
        }
    }

    @Test
    fun `MCP tab offers curl and raw HTTP with examples and reloads the saved request`() = withAuthService {
        SwingUtilities.invokeAndWait {
            val panel = TokenRequestPanel(config, store)
            val mode = field(panel, "Input mode: CURL / raw HTTP") as JComboBox<*>
            assertEquals(listOf("CURL", "HTTP"), (0 until mode.itemCount).map(mode::getItemAt))
            val editor = (field(panel, "Curl command or raw HTTP/1.x request") as JScrollPane).viewport.view as JTextArea
            assertEquals("", editor.text)
            assertTrue(editor.accessibleContext.accessibleDescription.startsWith("curl -X POST"))
            assertTrue(editor.accessibleContext.accessibleDescription.contains("token-providing.domain.com"))
            assertTrue(editor.accessibleContext.accessibleDescription.contains("{{client_secret}}"))
            assertEquals("token-consuming.domain.com / leave for same domain",
                field(panel, "Token use host (blank = request host)").accessibleContext.accessibleDescription)
            assertEquals("", (field(panel, "Token use host (blank = request host)") as JTextField).text)
            mode.selectedIndex = 1
            assertTrue(editor.accessibleContext.accessibleDescription.startsWith("POST /oauth/token HTTP/1.1"))
            assertTrue(editor.accessibleContext.accessibleDescription.contains("Host: token-providing.domain.com"))
            assertFalse(editor.accessibleContext.accessibleDescription.contains("Content-Length:"))
            editor.text = "POST /token HTTP/1.1\r\nHost: auth.target\r\nContent-Type: application/json\r\n\r\n" +
                """{"username":"{{username}}","password":"{{password}}"}"""
            components(panel).filterIsInstance<javax.swing.JCheckBox>()
                .first { it.text == "Use HTTPS for raw HTTP requests" }.isSelected = true
            (field(panel, "Default role for automatic 401 retry (optional with one ready role)") as JTextField).text = "user"
            components(panel).filterIsInstance<JButton>().first { it.text == "Save request" }.doClick()
            assertEquals(TokenRequestMode.TEMPLATE, config.tokenRequestProfile()?.mode)
            assertEquals(RawHttpScheme.HTTPS, config.tokenRequestProfile()?.rawHttpScheme)
            assertEquals("user", config.tokenRequestProfile()?.defaultIdentity)
            val reloadedPanel = TokenRequestPanel(config, store)
            assertEquals(1, (field(reloadedPanel, "Input mode: CURL / raw HTTP") as JComboBox<*>).selectedIndex)
            assertEquals(editor.text, ((field(reloadedPanel, "Curl command or raw HTTP/1.x request") as JScrollPane).viewport.view as JTextArea).text)
            assertEquals("user", (field(reloadedPanel, "Default role for automatic 401 retry (optional with one ready role)") as JTextField).text)
            (field(reloadedPanel, "Input mode: CURL / raw HTTP") as JComboBox<*>).selectedIndex = 0
            components(reloadedPanel).filterIsInstance<JButton>().first { it.text == "Save request" }.doClick()
            assertEquals(TokenRequestMode.TEMPLATE, config.tokenRequestProfile()?.mode)
            assertEquals(editor.text, config.tokenRequestProfile()?.template)
            assertTrue(components(reloadedPanel).filterIsInstance<JLabel>()
                .any { it.text == "Request format does not match the selected input mode." })
        }
        assertNotNull(config.tokenRequestProfile())
    }

    @Test
    fun `MCP tab loads legacy HTTP fields into curl editor`() = withAuthService {
        config.saveTokenRequestProfile(TokenRequestProfile(
            mode = TokenRequestMode.HTTP_FIELDS,
            url = "https://auth.target/token",
            body = "username={{username}}"
        ), store)
        SwingUtilities.invokeAndWait {
            val panel = TokenRequestPanel(config, store)
            assertEquals(0, (field(panel, "Input mode: CURL / raw HTTP") as JComboBox<*>).selectedIndex)
            val editor = (field(panel, "Curl command or raw HTTP/1.x request") as JScrollPane).viewport.view as JTextArea
            assertTrue(editor.text.contains("https://auth.target/token"))
            assertTrue(editor.text.contains("{{username}}"))
        }
    }

    @Test
    fun `MCP tab accepts memory-only input values without persisting them`() = withAuthService {
        SwingUtilities.invokeAndWait {
            val panel = TokenRequestPanel(config, store)
            ((field(panel, "Curl command or raw HTTP/1.x request") as JScrollPane).viewport.view as JTextArea).text =
                "curl https://auth.target/token --json '{\"username\":\"{{username}}\"}'"
            (field(panel.configuredPage, "Input variable name") as JTextField).text = "username"
            (field(panel.configuredPage, "Identity / role") as JTextField).text = "admin"
            (field(panel.configuredPage, "Value (cleared after storing)") as JPasswordField).text = "example-user"
            components(panel.configuredPage).filterIsInstance<JButton>().first { it.text == "Store value in memory" }.doClick()
            assertEquals(listOf(VariableStore.Name("username", "admin", "auth.target")), store.listNames())
            assertTrue((field(panel.configuredPage, "Value (cleared after storing)") as JPasswordField).password.isEmpty())
            assertFalse(values.containsKey("tokenRequestProfileJson"))
        }
    }

    private fun withAuthService(block: () -> Unit) {
        mockkStatic(HttpService::class)
        try {
            val service = mockk<HttpService>()
            every { service.secure() } returns true
            every { service.port() } returns 443
            every { HttpService.httpService("auth.target", 443, true) } returns service
            block()
        } finally {
            unmockkStatic(HttpService::class)
        }
    }

    private fun field(root: Container, label: String): JComponent {
        val name = components(root).filterIsInstance<JLabel>().first { it.text == label }
        val parent = name.parent as JPanel
        return (parent.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER) as JComponent
    }

    private fun components(root: Container): Sequence<Component> = sequence {
        for (component in root.components) {
            yield(component)
            if (component is Container) yieldAll(components(component))
        }
    }
}
