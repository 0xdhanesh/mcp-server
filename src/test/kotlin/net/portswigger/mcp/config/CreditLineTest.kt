package net.portswigger.mcp.config

import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import javax.swing.JLabel

class CreditLineTest {

    @Test
    fun headerCreditsLinkedIn() {
        val ui = ConfigUi(McpConfig(mockk<PersistedObject>(relaxed = true), mockk<Logging>(relaxed = true)), emptyList())
        try {
            val components = ArrayList<Component>()
            walk(ui.component, components)

            assertTrue(components.any { it is JLabel && it !is Anchor && it.text == "- Vibed by 0xdhanesh || " })
            val link = components.filterIsInstance<Anchor>().single { it.text == "linkedin" }
            assertEquals("https://linkedin.com/in/dhanesh-sivasamy", link.url)
        } finally {
            ui.cleanup()
        }
    }

    private fun walk(component: Component, out: MutableList<Component>) {
        out.add(component)
        if (component is Container) {
            component.components.forEach { walk(it, out) }
        }
    }
}
