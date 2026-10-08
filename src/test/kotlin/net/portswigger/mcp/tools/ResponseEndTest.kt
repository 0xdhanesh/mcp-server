package net.portswigger.mcp.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JTextPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.border.TitledBorder
import org.junit.jupiter.api.Assumptions

class ResponseEndTest {
    @Test
    fun `blank marker leaves the response unchanged`() {
        val response = "HTTP/1.1 200 OK\r\n\r\nbody"
        assertEquals(response, presentResponse(response, null, false))
        assertEquals(response, presentResponse(response, "", true))
    }

    @Test
    fun `marker reports offsets and can truncate`() {
        val response = "HTTP/1.1 200 OK\r\n\r\nhello END tail"
        val shown = presentResponse(response, "END", false)
        assertTrue(shown.startsWith(response))
        assertTrue(shown.contains("found at character"))
        assertTrue(shown.contains("Characters after the marker: 5"))

        val truncated = presentResponse(response, "END", true)
        assertTrue(truncated.startsWith("HTTP/1.1 200 OK\r\n\r\nhello END"))
        assertFalse(truncated.substringBefore("\n\nEnd marker").contains("tail"))
        assertTrue(truncated.contains("truncated at the marker"))
    }

    @Test
    fun `escaped newline marker matches a real CRLF`() {
        val response = "HTTP/1.1 200 OK\r\n\r\nbody"
        val hit = findResponseEnd(response, "\\r\\n\\r\\n")
        assertTrue(hit.found)
        assertEquals("\r\n\r\n", hit.markerText)
        assertEquals(response.indexOf("\r\n\r\n"), hit.startIndex)
    }

    @Test
    fun `missing marker is reported without dropping the response`() {
        val response = "HTTP/1.1 200 OK\r\n\r\nbody"
        val shown = presentResponse(response, "NOT-HERE", true)
        assertTrue(shown.startsWith(response))
        assertTrue(shown.contains("was not found"))
    }

    @Test
    fun `extension status json lists tools and commands`() {
        val raw = """
            HTTP/1.1 200 OK
            Content-Type: application/json

            {"extension":"ATOR","commands":"status, refresh, export, import","tools":[{"name":"Repeater","enabled":true},{"name":"Extensions","enabled":false}]}
        """.trimIndent().replace("\n", "\r\n")

        val summary = summarizeExtensionResponse("X-ATOR-Command", "status", raw)
        assertTrue(summary.contains("Extension: ATOR"))
        assertTrue(summary.contains("Commands: status, refresh, export, import"))
        assertTrue(summary.contains("Repeater enabled=true"))
        assertTrue(summary.contains("Extensions enabled=false"))
    }

    @Test
    fun `http mode names used by repeater are accepted`() {
        assertEquals(burp.api.montoya.http.HttpMode.HTTP_2, parseHttpMode("HTTP/2"))
        assertEquals(burp.api.montoya.http.HttpMode.HTTP_1, parseHttpMode("h1"))
        assertEquals(burp.api.montoya.http.HttpMode.AUTO, parseHttpMode("auto"))
    }

    @Test
    fun `repeater send button returns the response that appears after the click`() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless())

        val response = JTextArea("")
        val request = JTextArea("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        val notes = JTextArea()
        val notesPanel = JPanel(BorderLayout())
        notesPanel.border = TitledBorder("Notes")
        notesPanel.add(notes, BorderLayout.CENTER)
        val send = JButton("Send")
        send.addActionListener {
            response.text = "HTTP/1.1 200 OK\r\n\r\nhello END"
        }
        val panel = JPanel(BorderLayout())
        panel.add(send, BorderLayout.NORTH)
        panel.add(request, BorderLayout.WEST)
        panel.add(response, BorderLayout.CENTER)
        panel.add(notesPanel, BorderLayout.SOUTH)
        val sub = JTabbedPane()
        sub.addTab("history-7", panel)
        val top = JTabbedPane()
        top.addTab("Repeater", sub)
        val frame = JFrame()

        SwingUtilities.invokeAndWait {
            frame.contentPane.add(top)
            frame.setSize(800, 400)
            frame.isVisible = true
        }
        try {
            val sent = RepeaterUi.trySend(frame, "history-7", 2_000)
            assertTrue(sent is RepeaterUiSend.Response, "expected a response, got $sent")
            assertTrue((sent as RepeaterUiSend.Response).text.contains("hello END"))
            assertTrue(RepeaterUi.trySetNotes(frame, "history-7", "checked"))
            assertEquals("checked", notes.text)
        } finally {
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }

    @Test
    fun `send button is found from its tooltip when getToolTipText is hidden`() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless())

        val response = JTextArea("")
        val request = JTextArea("POST /login HTTP/1.1\r\nHost: example.com\r\n\r\n")
        val send = object : JButton() {
            override fun getToolTipText(): String? = null
        }
        send.toolTipText = "Send Ctrl+Enter"
        send.addActionListener { response.text = "HTTP/1.1 204 No Content\r\n\r\n" }
        val panel = JPanel(BorderLayout())
        panel.add(send, BorderLayout.NORTH)
        panel.add(request, BorderLayout.WEST)
        panel.add(response, BorderLayout.CENTER)
        val sub = JTabbedPane()
        sub.addTab("login", panel)
        val top = JTabbedPane()
        top.addTab("Repeater", sub)
        val frame = JFrame()
        SwingUtilities.invokeAndWait {
            frame.contentPane.add(top)
            frame.setSize(800, 400)
            frame.isVisible = true
        }
        try {
            val sent = RepeaterUi.trySend(frame, "login", 2_000)
            check(sent is RepeaterUiSend.Response) { "expected a response, got $sent" }
            assertTrue(sent.text.contains("204"))
            assertEquals("Send button", sent.via)
        } finally {
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }

    @Test
    fun `ctrl enter on the request editor sends when the button is absent`() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless())

        val response = JTextArea("")
        val request = JTextArea("GET /shortcut HTTP/1.1\r\nHost: example.com\r\n\r\n")
        val actionKey = "repeater-send"
        request.actionMap.put(actionKey, object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                response.text = "HTTP/1.1 200 OK\r\n\r\nfrom-shortcut"
            }
        })
        val stroke = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
        request.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(stroke, actionKey)
        val panel = JPanel(BorderLayout())
        panel.add(request, BorderLayout.WEST)
        panel.add(response, BorderLayout.CENTER)
        val sub = JTabbedPane()
        sub.addTab("shortcut", panel)
        val top = JTabbedPane()
        top.addTab("Repeater", sub)
        val frame = JFrame()
        SwingUtilities.invokeAndWait {
            frame.contentPane.add(top)
            frame.setSize(800, 400)
            frame.isVisible = true
        }
        try {
            val sent = RepeaterUi.trySend(frame, "shortcut", 2_000)
            check(sent is RepeaterUiSend.Response) { "expected a response, got $sent" }
            assertTrue(sent.text.contains("from-shortcut"))
            assertEquals("Send shortcut", sent.via)
        } finally {
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }

    @Test
    fun `notes are written into the notes tab rather than a titled border`() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless())

        val notes = JTextPane()
        var committed = false
        notes.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(event: java.awt.event.FocusEvent) {
                committed = notes.text == "checked in notes"
            }
        })
        val side = JTabbedPane()
        side.addTab("Inspector", JPanel())
        side.addTab("Notes", JScrollPane(notes))
        val request = JTextArea("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        val panel = JPanel(BorderLayout())
        panel.add(request, BorderLayout.CENTER)
        panel.add(side, BorderLayout.EAST)
        val sub = JTabbedPane()
        sub.addTab("history-9", panel)
        val top = JTabbedPane()
        top.addTab("Repeater", sub)
        val frame = JFrame()
        SwingUtilities.invokeAndWait {
            frame.contentPane.add(top)
            frame.setSize(900, 400)
            frame.isVisible = true
        }
        try {
            assertTrue(RepeaterUi.trySetNotes(frame, "history-9", "checked in notes"))
            assertEquals("checked in notes", notes.text)
            assertTrue(committed)
            assertEquals(side.indexOfTab("Notes"), side.selectedIndex)
        } finally {
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }
}
