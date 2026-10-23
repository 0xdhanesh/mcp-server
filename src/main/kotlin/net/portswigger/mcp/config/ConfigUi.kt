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
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Component.LEFT_ALIGNMENT
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
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
            addTab("", JPanel())
            setTabComponentAt(tabCount - 1, JSeparator(SwingConstants.VERTICAL).apply {
                preferredSize = Dimension(1, 18)
            })
            setEnabledAt(tabCount - 1, false)
            addTab("MCP Settings", scrollablePage(settings))
            setTabComponentAt(tabCount - 1, JLabel("MCP Settings", GearIcon(), SwingConstants.LEADING).apply {
                iconTextGap = Design.Spacing.SM
            })
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
            alignmentX = LEFT_ALIGNMENT
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

/** Outline gear drawn in the host component's foreground color, matching Burp's settings icon. */
private class GearIcon(private val size: Int = 16) : Icon {
    override fun getIconWidth(): Int = size
    override fun getIconHeight(): Int = size

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g2.color = c?.foreground ?: Design.Colors.onSurface
            g2.translate(x + size / 2.0, y + size / 2.0)
            val scale = size / 16.0
            g2.scale(scale, scale)
            g2.stroke = BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

            val teeth = 8
            val outer = 7.2
            val inner = 5.4
            val path = Path2D.Double()
            for (i in 0 until teeth * 4) {
                val angle = Math.PI * 2 * i / (teeth * 4) - Math.PI / 2
                val r = if ((i / 2) % 2 == 0) outer else inner
                val px = Math.cos(angle) * r
                val py = Math.sin(angle) * r
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.closePath()
            g2.draw(path)
            g2.draw(Ellipse2D.Double(-2.3, -2.3, 4.6, 4.6))
        } finally {
            g2.dispose()
        }
    }
}
