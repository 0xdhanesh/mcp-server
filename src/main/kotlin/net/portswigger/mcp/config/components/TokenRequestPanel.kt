package net.portswigger.mcp.config.components

import net.portswigger.mcp.config.Design
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.config.RawHttpScheme
import net.portswigger.mcp.config.TokenRequestMode
import net.portswigger.mcp.config.TokenRequestProfile
import net.portswigger.mcp.config.TokenRequestReadiness
import net.portswigger.mcp.config.TokenSelectorMode
import net.portswigger.mcp.config.readiness
import net.portswigger.mcp.security.VariableStore
import net.portswigger.mcp.security.RequestTemplate
import java.awt.BorderLayout
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
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** Burp-persisted token request template. Secret values are supplied from VariableStore at send time. */
class TokenRequestPanel(private val config: McpConfig, private val store: VariableStore) : JPanel() {
    private val mode = JComboBox(arrayOf("CURL", "HTTP"))
    private val template = PlaceholderTextArea(9, 36)
    private val useHttps = javax.swing.JCheckBox("Use HTTPS for raw HTTP requests")
    private val variableName = JTextField("session")
    private val targetHost = JTextField()
    private val defaultIdentity = JTextField()
    private val selectorMode = JComboBox(arrayOf("JSONPath", "Regex"))
    private val selector = JTextField("$.access_token")
    private val inputName = PlaceholderTextField("username / password")
    private val inputIdentity = PlaceholderTextField("admin / developer")
    private val inputHost = JTextField()
    private val inputValue = PlaceholderPasswordField("username / password will be masked as you type")
    private val memoryValuesHint = JLabel()
    private val status = JLabel(" ")
    private val readinessStatus = JLabel(" ")
    private val readinessPanel = JPanel()

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
        add(field("Input mode: CURL / raw HTTP", mode))
        add(field("Curl command or raw HTTP/1.x request", textArea(template, 205, 620)))
        useHttps.isOpaque = false
        useHttps.alignmentX = LEFT_ALIGNMENT
        add(useHttps)
        mode.addActionListener { showMode() }
        template.document.addDocumentListener(onChange(::updateHandleHints))

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

        add(Box.createVerticalStrut(Design.Spacing.MD))
        add(Design.createSectionLabel("Memory-only request values"))
        add(memoryValuesHint.apply {
            font = Design.Typography.bodyMedium
            foreground = Design.Colors.onSurfaceVariant
            alignmentX = LEFT_ALIGNMENT
        })
        add(field("Input variable name", inputName))
        add(field("Identity / role", inputIdentity))
        add(field("Request host (blank = token request host)", inputHost))
        add(field("Value (cleared after storing)", inputValue))
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, Design.Spacing.SM)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(JButton("Store value in memory").apply { addActionListener { storeInputValue() } })
            add(Box.createHorizontalStrut(Design.Spacing.SM))
            add(JButton("Dry Run check").apply { addActionListener { dryRunCheck() } })
            add(Box.createHorizontalStrut(Design.Spacing.SM))
            add(JButton("Check Automatic Readiness").apply {
                addActionListener {
                    readinessPanel.isVisible = true
                    refreshReadiness()
                }
            })
        })

        readinessStatus.font = Design.Typography.bodyMedium
        readinessPanel.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            isVisible = false
            add(readinessStatus)
            add(JLabel("Automatic retry only runs for MCP HTTP sends that pass allowTokenRequest=true.").apply {
                font = Design.Typography.labelMedium
                foreground = Design.Colors.onSurfaceVariant
            })
        }
        add(readinessPanel)

        config.tokenRequestProfile()?.let(::load)
        showMode()
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

    private fun textArea(area: JTextArea, height: Int, width: Int = 320): JScrollPane {
        area.lineWrap = false
        area.font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12)
        return JScrollPane(area).apply {
            preferredSize = Dimension(width, height)
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        }
    }

    private fun showMode() {
        template.placeholder = if (mode.selectedIndex == 0) CURL_PLACEHOLDER else HTTP_PLACEHOLDER
        template.toolTipText = template.placeholder
        useHttps.isVisible = mode.selectedIndex == 1
        revalidate()
        updateHandleHints()
    }

    /** Mirrors the {{name}} handles in the active request editor into the memory-only input hints. */
    private fun updateHandleHints() {
        val source = template.text
        val names = HANDLE.findAll(source).map { it.groupValues[1] }.distinct().toList()
            .ifEmpty { listOf("username", "password") }
        val handles = names.map { "{{$it}}" }
        val joined = if (handles.size == 1) handles.single() else handles.dropLast(1).joinToString(", ") + " and " + handles.last()
        memoryValuesHint.text = "Store $joined for each role. Values are never saved with the request."
        inputName.placeholder = names.joinToString(" / ")
        inputValue.placeholder = names.joinToString(" / ") + " will be masked as you type"
    }

    private fun onChange(action: () -> Unit) = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = action()
        override fun removeUpdate(e: DocumentEvent) = action()
        override fun changedUpdate(e: DocumentEvent) = action()
    }

    private fun current(): TokenRequestProfile {
        val isCurl = template.text.trimStart().startsWith("curl ", ignoreCase = true)
        require(template.text.isBlank() || isCurl == (mode.selectedIndex == 0)) {
            "Request format does not match the selected input mode."
        }
        return TokenRequestProfile(
            mode = TokenRequestMode.TEMPLATE,
            template = template.text,
            rawHttpScheme = if (mode.selectedIndex == 1 && !useHttps.isSelected) RawHttpScheme.HTTP else RawHttpScheme.HTTPS,
            variableName = variableName.text.trim(),
            targetHost = targetHost.text.trim(),
            defaultIdentity = defaultIdentity.text.trim(),
            selectorMode = if (selectorMode.selectedIndex == 0) TokenSelectorMode.JSON_PATH else TokenSelectorMode.REGEX,
            selector = selector.text.trim()
        )
    }

    private fun save() {
        try {
            config.saveTokenRequestProfile(current(), store)
            status.foreground = Design.Colors.onSurfaceVariant
            // status.text = "Token request saved in Burp extension data."
            refreshReadiness()
        } catch (error: Exception) {
            status.foreground = Design.Colors.error
            status.text = when (error.message) {
                "Choose JSONPath to extract $.access_token." -> error.message!!
                "JSON values must use variable handles before saving." -> error.message!!
                "Body parameter values must use variable handles before saving." -> error.message!!
                "Credential headers must use stored variable handles only." -> error.message!!
                "Request format does not match the selected input mode." -> error.message!!
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
        useHttps.isSelected = false
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

    /** Shows whether the saved token request can run on its own after a 401. Only updates once the result is shown. */
    private fun refreshReadiness() {
        if (!readinessPanel.isVisible) return
        val profile = config.tokenRequestProfile()
        val readiness = profile?.let { runCatching { it.readiness(store) }.getOrNull() }
        val (ready, message) = when {
            profile == null -> false to "no token request is saved. Click Save request first."
            readiness == null -> false to "the saved token request could not be parsed. Fix it and click Save request."
            else -> automaticMessage(readiness)
        }
        readinessStatus.foreground = if (ready) Design.Colors.onSurface else Design.Colors.error
        readinessStatus.text = "Automatic 401 retry: " + (if (ready) "Ready, " else "Not ready, ") + message
        revalidate()
    }

    private fun automaticMessage(r: TokenRequestReadiness): Pair<Boolean, String> {
        val host = r.requestHost
        val default = r.defaultRole
        return when {
            default.isNotBlank() && r.missingForDefault.isEmpty() ->
                true to "a new token will be requested as role \"$default\" from $host."
            default.isNotBlank() && r.readyRoles.size == 1 ->
                true to "role \"$default\" is missing ${handles(r.missingForDefault)}, so role \"${r.readyRoles.single()}\" will be used instead."
            default.isNotBlank() ->
                false to "role \"$default\" has no stored value for ${handles(r.missingForDefault)} on $host. Add them under Memory-only request values."
            r.readyRoles.size == 1 ->
                true to "a new token will be requested as role \"${r.readyRoles.single()}\", the only role with every value."
            r.readyRoles.size > 1 ->
                false to "roles ${r.readyRoles.joinToString(", ")} all have every value. Set a default role to choose one."
            else ->
                false to "no role has stored values for ${handles(r.requiredNames)} on $host. Add them under Memory-only request values."
        }
    }

    /** Checks the request currently in the editor against stored values. Nothing is saved or sent. */
    private fun dryRunCheck() {
        val readiness = runCatching { current().readiness(store) }.getOrNull()
        if (readiness == null) {
            status.foreground = Design.Colors.error
            status.text = "Dry run: the request in the editor could not be parsed. Check its syntax."
            return
        }
        val host = readiness.requestHost
        val roles = store.listNames().filter { it.host == host }.map { it.identity }.distinct().sorted()
        val coverage = when {
            readiness.requiredNames.isEmpty() -> "It uses no {{name}} handles."
            roles.isEmpty() -> "No values are stored for $host yet."
            else -> roles.joinToString("; ") { role ->
                val missing = readiness.missingFor(role, store)
                if (missing.isEmpty()) "$role: all set" else "$role: missing ${handles(missing)}"
            } + "."
        }
        status.foreground = Design.Colors.onSurfaceVariant
        status.text = if (readiness.requiredNames.isEmpty()) "Dry run (nothing sent): request to $host is valid. $coverage"
        else "Dry run (nothing sent): request to $host needs ${handles(readiness.requiredNames)}. $coverage"
    }

    private fun handles(names: Collection<String>): String = names.joinToString(", ") { "{{$it}}" }

    private fun load(profile: TokenRequestProfile) {
        val request = if (profile.mode == TokenRequestMode.HTTP_FIELDS) profile.requestTemplate() else profile.template
        mode.selectedIndex = if (request.trimStart().startsWith("curl ", ignoreCase = true)) 0 else 1
        template.text = request
        useHttps.isSelected = profile.rawHttpScheme == RawHttpScheme.HTTPS
        variableName.text = profile.variableName
        targetHost.text = profile.targetHost
        defaultIdentity.text = profile.defaultIdentity
        selectorMode.selectedIndex = if (profile.selectorMode == TokenSelectorMode.JSON_PATH) 0 else 1
        selector.text = profile.selector
        status.text = "Token request loaded from Burp extension data."
    }
}

private val CURL_PLACEHOLDER = """curl -X POST http://localhost/token \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    -d 'grant_type=client_credentials&client_id={{cid}}&client_password={{cpass}}'"""

private val HTTP_PLACEHOLDER = """POST /token HTTP/1.1
Host: 127.0.0.1:5000
Content-Type: application/x-www-form-urlencoded
Content-Length: 0

grant_type=client_credentials&client_id={{cid}}&client_password={{cpass}}"""

/** Paints [placeholder] in a muted color while the field is empty. */
private fun paintPlaceholder(g: java.awt.Graphics, field: JTextField, placeholder: String) {
    if (field.document.length > 0) return
    val g2 = g.create() as java.awt.Graphics2D
    try {
        g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.font = field.font
        g2.color = Design.Colors.onSurfaceVariant
        val insets = field.insets
        val metrics = g2.fontMetrics
        val y = insets.top + (field.height - insets.top - insets.bottom - metrics.height) / 2 + metrics.ascent
        g2.drawString(placeholder, insets.left, y)
    } finally {
        g2.dispose()
    }
}

private val HANDLE = Regex("\\{\\{([A-Za-z_][A-Za-z0-9_]*)}}")

private class PlaceholderTextArea(rows: Int, columns: Int) : JTextArea(rows, columns) {
    var placeholder: String = ""
        set(value) {
            field = value
            getAccessibleContext().accessibleDescription = value
            repaint()
        }

    override fun paintComponent(g: java.awt.Graphics) {
        super.paintComponent(g)
        if (document.length > 0) return
        val g2 = g.create() as java.awt.Graphics2D
        try {
            g2.color = Design.Colors.onSurfaceVariant
            g2.font = font
            val metrics = g2.fontMetrics
            val x = insets.left
            var y = insets.top + metrics.ascent
            placeholder.lineSequence().forEach { line ->
                g2.drawString(line, x, y)
                y += metrics.height
            }
        } finally {
            g2.dispose()
        }
    }
}

private class PlaceholderTextField(placeholder: String) : JTextField() {
    var placeholder: String = placeholder
        set(value) { field = value; repaint() }

    override fun paintComponent(g: java.awt.Graphics) {
        super.paintComponent(g)
        paintPlaceholder(g, this, placeholder)
    }
}

private class PlaceholderPasswordField(placeholder: String) : JPasswordField() {
    var placeholder: String = placeholder
        set(value) { field = value; repaint() }

    override fun paintComponent(g: java.awt.Graphics) {
        super.paintComponent(g)
        paintPlaceholder(g, this, placeholder)
    }
}
