package net.portswigger.mcp.config.components

import net.portswigger.mcp.config.Design
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.config.RawHttpScheme
import net.portswigger.mcp.config.TokenRequestMode
import net.portswigger.mcp.config.TokenRequestProfile
import net.portswigger.mcp.config.TokenSelectorMode
import net.portswigger.mcp.config.readiness
import net.portswigger.mcp.security.VariableStore
import net.portswigger.mcp.security.RequestTemplate
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField

/** Burp-persisted token request template. Secret values are supplied from VariableStore at send time. */
class TokenRequestPanel(private val config: McpConfig, private val store: VariableStore) : JPanel() {
    private val mode = JComboBox(arrayOf("Curl or raw HTTP", "HTTP fields"))
    private val template = JTextArea(7, 36)
    private val rawHttpScheme = JComboBox(arrayOf("HTTPS", "HTTP"))
    private val method = JComboBox(arrayOf("POST", "GET", "PUT", "PATCH", "DELETE"))
    private val url = JTextField()
    private val headers = JTextArea(4, 36)
    private val body = JTextArea(5, 36)
    private val variableName = JTextField("session")
    private val targetHost = JTextField()
    private val defaultIdentity = JTextField()
    private val selectorMode = JComboBox(arrayOf("JSONPath", "Regex"))
    private val selector = JTextField("$.access_token")
    private val inputName = JTextField()
    private val inputIdentity = JTextField()
    private val inputHost = JTextField()
    private val inputValue = JPasswordField()
    private val cards = JPanel(CardLayout())
    private val status = JLabel(" ")
    private val readinessStatus = JLabel(" ")

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = LEFT_ALIGNMENT
        background = Design.Colors.surface
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Design.Colors.outlineVariant),
            BorderFactory.createEmptyBorder(Design.Spacing.MD, Design.Spacing.MD, Design.Spacing.MD, Design.Spacing.MD)
        )

        add(Design.createSectionLabel("Token Request"))
        add(Box.createVerticalStrut(Design.Spacing.SM))
        add(JLabel("Save a request template. Use {{name}} handles for credentials.").apply {
            font = Design.Typography.bodyMedium
            foreground = Design.Colors.onSurfaceVariant
            alignmentX = LEFT_ALIGNMENT
        })
        add(Box.createVerticalStrut(Design.Spacing.MD))
        add(field("Input mode", mode))

        cards.isOpaque = false
        cards.add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(field("Curl command or raw HTTP/1.x request", textArea(template, 170)))
            add(field("Raw HTTP transport (curl uses its URL)", rawHttpScheme))
        }, "template")
        cards.add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(field("Method", method))
            add(field("Request URL (URI)", url))
            add(field("Headers (one per line)", textArea(headers, 100)))
            add(field("Body parameters (JSON, form, or {{body}})", textArea(body, 130)))
        }, "fields")
        cards.alignmentX = LEFT_ALIGNMENT
        add(cards)
        mode.addActionListener { showMode() }

        add(field("Stored variable name", variableName))
        add(field("Token use host (blank = request host)", targetHost))
        add(field("Default role for automatic 401 retry (optional with one ready role)", defaultIdentity))
        add(field("Extract token with", selectorMode))
        add(field("JSONPath or regex", selector))

        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, Design.Spacing.SM)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(JButton("Save request").apply { addActionListener { save() } })
            add(Box.createHorizontalStrut(Design.Spacing.SM))
            add(JButton("Clear saved request").apply { addActionListener { clear() } })
        })
        status.alignmentX = LEFT_ALIGNMENT
        status.font = Design.Typography.bodyMedium
        status.foreground = Design.Colors.onSurfaceVariant
        add(status)
        readinessStatus.alignmentX = LEFT_ALIGNMENT
        readinessStatus.font = Design.Typography.bodyMedium
        readinessStatus.foreground = Design.Colors.onSurfaceVariant
        add(readinessStatus)
        add(JLabel("MCP HTTP sends require allowTokenRequest=true to acquire a token after 401.").apply {
            alignmentX = LEFT_ALIGNMENT
            font = Design.Typography.labelMedium
            foreground = Design.Colors.onSurfaceVariant
        })

        add(Box.createVerticalStrut(Design.Spacing.MD))
        add(Design.createSectionLabel("Memory-only request values"))
        add(JLabel("Store {{username}} and {{password}} for each role. Values are never saved with the request.").apply {
            font = Design.Typography.bodyMedium
            foreground = Design.Colors.onSurfaceVariant
            alignmentX = LEFT_ALIGNMENT
        })
        add(field("Input variable name", inputName))
        add(field("Identity / role", inputIdentity))
        add(field("Request host (blank = token request host)", inputHost))
        add(field("Value (cleared after storing)", inputValue))
        add(JButton("Store value in memory").apply {
            alignmentX = LEFT_ALIGNMENT
            addActionListener { storeInputValue() }
        })
        add(JButton("Check 401 readiness").apply {
            alignmentX = LEFT_ALIGNMENT
            addActionListener { refreshReadiness() }
        })

        config.tokenRequestProfile()?.let(::load)
        showMode()
        refreshReadiness()
    }

    private fun field(label: String, component: JComponent): JPanel = JPanel(BorderLayout(0, Design.Spacing.SM / 2)).apply {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        border = BorderFactory.createEmptyBorder(0, 0, Design.Spacing.SM, 0)
        add(JLabel(label).apply {
            font = Design.Typography.bodyMedium
            foreground = Design.Colors.onSurface
        }, BorderLayout.NORTH)
        add(component, BorderLayout.CENTER)
    }

    private fun textArea(area: JTextArea, height: Int): JScrollPane {
        area.lineWrap = false
        area.font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12)
        return JScrollPane(area).apply {
            preferredSize = Dimension(320, height)
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        }
    }

    private fun showMode() {
        (cards.layout as CardLayout).show(cards, if (mode.selectedIndex == 0) "template" else "fields")
    }

    private fun current(): TokenRequestProfile = TokenRequestProfile(
        mode = if (mode.selectedIndex == 0) TokenRequestMode.TEMPLATE else TokenRequestMode.HTTP_FIELDS,
        template = if (mode.selectedIndex == 0) template.text else "",
        rawHttpScheme = if (rawHttpScheme.selectedIndex == 0) RawHttpScheme.HTTPS else RawHttpScheme.HTTP,
        method = method.selectedItem.toString(),
        url = if (mode.selectedIndex == 1) url.text else "",
        headers = if (mode.selectedIndex == 1) headers.text else "",
        body = if (mode.selectedIndex == 1) body.text else "",
        variableName = variableName.text.trim(),
        targetHost = targetHost.text.trim(),
        defaultIdentity = defaultIdentity.text.trim(),
        selectorMode = if (selectorMode.selectedIndex == 0) TokenSelectorMode.JSON_PATH else TokenSelectorMode.REGEX,
        selector = selector.text.trim()
    )

    private fun save() {
        try {
            config.saveTokenRequestProfile(current(), store)
            status.foreground = Design.Colors.onSurfaceVariant
            status.text = "Token request saved in Burp extension data."
            refreshReadiness()
        } catch (error: Exception) {
            status.foreground = Design.Colors.error
            status.text = when (error.message) {
                "Choose JSONPath to extract $.access_token." -> error.message!!
                "JSON values must use variable handles before saving." -> error.message!!
                "Body parameter values must use variable handles before saving." -> error.message!!
                "Credential headers must use stored variable handles only." -> error.message!!
                else -> "Could not save. Check syntax and replace credential values with {{name}} handles."
            }
        }
    }

    private fun storeInputValue() {
        val chars = inputValue.password
        try {
            val host = inputHost.text.trim().ifBlank { RequestTemplate.parse(current().requestTemplate()).host }
            val handle = store.put(inputName.text.trim(), String(chars), inputIdentity.text.trim(), host)
            inputValue.text = ""
            status.foreground = Design.Colors.onSurfaceVariant
            status.text = "Stored $handle in memory."
            refreshReadiness()
        } catch (error: Exception) {
            status.foreground = Design.Colors.error
            status.text = if (error.message == "Enter the actual variable value; {{name}} is a handle, not a value.") {
                error.message
            } else "Could not store value. Check variable name, identity, request, and host."
        } finally {
            chars.fill('\u0000')
            inputValue.text = ""
        }
    }

    private fun clear() {
        config.clearTokenRequestProfile()
        mode.selectedIndex = 0
        template.text = ""
        rawHttpScheme.selectedIndex = 0
        method.selectedItem = "POST"
        url.text = ""
        headers.text = ""
        body.text = ""
        variableName.text = "session"
        targetHost.text = ""
        defaultIdentity.text = ""
        selectorMode.selectedIndex = 0
        selector.text = "$.access_token"
        inputValue.text = ""
        status.foreground = Design.Colors.onSurfaceVariant
        status.text = "Saved token request cleared."
        refreshReadiness()
    }

    private fun refreshReadiness() {
        val profile = config.tokenRequestProfile()
        readinessStatus.text = if (profile == null) "Automatic 401: no token request saved." else {
            "Automatic 401: " + runCatching { profile.readiness(store).summary() }
                .getOrDefault("Saved token request is invalid.")
        }
    }

    private fun load(profile: TokenRequestProfile) {
        mode.selectedIndex = if (profile.mode == TokenRequestMode.TEMPLATE) 0 else 1
        template.text = profile.template
        rawHttpScheme.selectedIndex = if (profile.rawHttpScheme == RawHttpScheme.HTTPS) 0 else 1
        method.selectedItem = profile.method
        url.text = profile.url
        headers.text = profile.headers
        body.text = profile.body
        variableName.text = profile.variableName
        targetHost.text = profile.targetHost
        defaultIdentity.text = profile.defaultIdentity
        selectorMode.selectedIndex = if (profile.selectorMode == TokenSelectorMode.JSON_PATH) 0 else 1
        selector.text = profile.selector
        status.text = "Token request loaded from Burp extension data."
    }
}
