package net.portswigger.mcp.config

import io.ktor.util.network.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.portswigger.mcp.ExtensionVersion
import net.portswigger.mcp.ServerState
import net.portswigger.mcp.Swing
import net.portswigger.mcp.config.components.*
import net.portswigger.mcp.security.VariableStore
import java.awt.BorderLayout
import java.awt.Component.CENTER_ALIGNMENT
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Rectangle
import java.awt.font.TextAttribute
import javax.swing.*
import javax.swing.Box.*
import javax.swing.JOptionPane.ERROR_MESSAGE

class ConfigUi(
    private val config: McpConfig,
    private val variableStore: VariableStore = VariableStore()
) {

    private val panel = JPanel(BorderLayout())
    val component: JComponent get() = panel

    private val listenerHandles = mutableListOf<ListenerHandle>()

    private val enabledToggle: ToggleSwitch = Design.createToggleSwitch(false) { enabled ->
        if (suppressToggleEvents) return@createToggleSwitch

        if (enabled) {
            ConfigValidation.validateServerConfig(hostField.text, portField.text)?.let { error ->
                validationErrorLabel.text = error
                validationErrorLabel.isVisible = true
                suppressToggleEvents = true
                enabledToggle.setState(false, animate = true)
                suppressToggleEvents = false
                return@createToggleSwitch
            }
        }

        validationErrorLabel.isVisible = false
        config.enabled = enabled
        toggleListener?.invoke(enabled)
    }
    private val validationErrorLabel = WarningLabel()
    private val hostField = JTextField(15)
    private val portField = JTextField(5)
    private val restartNotice = WarningLabel("Restart the MCP server after changing its host or port.")

    private lateinit var serverConfigurationPanel: ServerConfigurationPanel
    private lateinit var advancedOptionsPanel: AdvancedOptionsPanel
    private lateinit var autoApproveTargetsPanel: AutoApproveTargetsPanel
    private lateinit var tokenRequestPanel: TokenRequestPanel
    private val activityLogPanel = ActivityLogPanel()

    private var toggleListener: ((Boolean) -> Unit)? = null
    private var suppressToggleEvents: Boolean = false

    private val dataAccessRefreshListener: () -> Unit = {
        SwingUtilities.invokeLater {
            serverConfigurationPanel.updateDataAccessCheckboxes()
        }
    }

    init {
        enabledToggle.setState(config.enabled, animate = false)
        hostField.text = config.host
        portField.text = config.port.toString()

        initializeComponents()
        buildUi()
    }

    private fun initializeComponents() {
        serverConfigurationPanel = ServerConfigurationPanel(
            config = config, enabledToggle = enabledToggle, validationErrorLabel = validationErrorLabel
        )

        advancedOptionsPanel = AdvancedOptionsPanel(
            hostField = hostField, portField = portField, restartNotice = restartNotice
        )

        autoApproveTargetsPanel = AutoApproveTargetsPanel(config = config)

        tokenRequestPanel = TokenRequestPanel(config, variableStore)

        setupConfigListeners()
    }

    private fun setupConfigListeners() {
        val handle = config.addDataAccessChangeListener(dataAccessRefreshListener)
        listenerHandles.add(handle)
    }

    fun cleanup() {
        activityLogPanel.dispose()
        listenerHandles.forEach { it.remove() }
        listenerHandles.clear()

        if (::autoApproveTargetsPanel.isInitialized) {
            autoApproveTargetsPanel.cleanup()
        }
    }

    fun onEnabledToggled(listener: (Boolean) -> Unit) {
        toggleListener = listener
    }

    fun getConfig(): McpConfig {
        config.host = hostField.text
        portField.text.toIntOrNull()?.let { config.port = it }
        return config
    }

    fun updateServerState(state: ServerState) {
        CoroutineScope(Dispatchers.Swing).launch {
            suppressToggleEvents = true

            val enableAdvancedOptions = state is ServerState.Stopped || state is ServerState.Failed
            if (::advancedOptionsPanel.isInitialized) {
                advancedOptionsPanel.setFieldsEnabled(enableAdvancedOptions)
            }

            when (state) {
                ServerState.Starting, ServerState.Stopping -> {
                    enabledToggle.isEnabled = false
                }

                ServerState.Running -> {
                    enabledToggle.isEnabled = true
                    enabledToggle.setState(true, animate = false)
                }

                ServerState.Stopped -> {
                    enabledToggle.isEnabled = true
                    enabledToggle.setState(false, animate = false)
                }

                is ServerState.Failed -> {
                    enabledToggle.isEnabled = true
                    enabledToggle.setState(false, animate = false)

                    val friendlyMessage = when (state.exception) {
                        is UnresolvedAddressException -> "Unable to resolve address"
                        else -> state.exception.message ?: state.exception.javaClass.simpleName
                    }

                    Dialogs.showMessageDialog(
                        panel, "Failed to start ${ExtensionVersion.NAME}: $friendlyMessage", ERROR_MESSAGE
                    )
                }
            }

            suppressToggleEvents = false
        }
    }

    private fun buildUi() {
        val settings = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(serverConfigurationPanel)
            add(createVerticalStrut(Design.Spacing.MD))
            add(advancedOptionsPanel)
            add(createVerticalStrut(Design.Spacing.SM))
            add(restartNotice)
            add(createVerticalStrut(Design.Spacing.LG))
            add(creditLine())
        }
        val tabs = JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT).apply {
            addTab("Activity Log", JPanel(BorderLayout()).apply {
                background = Design.Colors.surface
                border = BorderFactory.createEmptyBorder(Design.Spacing.MD, Design.Spacing.MD, Design.Spacing.MD, Design.Spacing.MD)
                add(activityLogPanel, BorderLayout.CENTER)
            })
            addTab("Token Request", scrollablePage(tokenRequestPanel))
            addTab("Auto-Approved HTTP Requests", scrollablePage(autoApproveTargetsPanel))
            addTab("MCP Settings", scrollablePage(settings))
            minimumSize = Dimension(0, 0)
        }
        panel.add(tabs, BorderLayout.CENTER)
    }

    private fun scrollablePage(content: JComponent): JScrollPane = JScrollPane(
        ScrollablePage(content)
    ).apply {
        border = null
        viewport.background = Design.Colors.surface
        verticalScrollBar.unitIncrement = 16
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
    }

    private fun creditLine(): JComponent {
        return JPanel(FlowLayout(FlowLayout.CENTER, 0, 0)).apply {
            isOpaque = false
            alignmentX = CENTER_ALIGNMENT
            add(JLabel("- Vibed by 0xdhanesh || ").apply {
                font = Design.Typography.bodyLarge
                foreground = Design.Colors.onSurfaceVariant
            })
            add(Anchor(
                text = "linkedin",
                url = "https://linkedin.com/in/dhanesh-sivasamy"
            ).apply {
                font = Design.Typography.bodyLarge.deriveFont(
                    mapOf(TextAttribute.UNDERLINE to TextAttribute.UNDERLINE_ON)
                )
            })
        }
    }
}

private class ScrollablePage(content: JComponent) : JPanel(BorderLayout()), Scrollable {
    init {
        background = Design.Colors.surface
        border = BorderFactory.createEmptyBorder(
            Design.Spacing.MD, Design.Spacing.MD, Design.Spacing.MD, Design.Spacing.MD
        )
        add(content, BorderLayout.NORTH)
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = 16
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        (if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width).coerceAtLeast(16)
    override fun getScrollableTracksViewportWidth(): Boolean = (parent?.width ?: 0) >= preferredSize.width
    override fun getScrollableTracksViewportHeight(): Boolean = false
}
