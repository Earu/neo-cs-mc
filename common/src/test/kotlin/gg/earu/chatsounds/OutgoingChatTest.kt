package gg.earu.chatsounds

import gg.earu.chatsounds.client.OutgoingChat
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The full text ships on the mod channel only for messages vanilla will trim; the chat
 * message itself is never touched, so there is no cancel/suppress decision to test.
 */
class OutgoingChatTest {
    private var sent: String? = null

    init {
        OutgoingChat.sendLong = { text ->
            sent = text
            true
        }
    }

    @AfterTest
    fun reset() {
        OutgoingChat.sendLong = null
    }

    private fun long(prefix: String = "") = prefix + "a".repeat(OutgoingChat.VANILLA_CHAT_LIMIT + 1)

    @Test
    fun `short messages ship nothing`() {
        OutgoingChat.beforeChatSend("hello there")
        assertNull(sent)
    }

    @Test
    fun `a message exactly at the cap ships nothing`() {
        OutgoingChat.beforeChatSend("a".repeat(OutgoingChat.VANILLA_CHAT_LIMIT))
        assertNull(sent)
    }

    @Test
    fun `long messages ship the full text`() {
        OutgoingChat.beforeChatSend(long())
        assertEquals(long(), sent)
    }

    @Test
    fun `long commands ship nothing`() {
        OutgoingChat.beforeChatSend(long("/say "))
        assertNull(sent)
    }

    @Test
    fun `no loader hook is a no-op`() {
        OutgoingChat.sendLong = null
        OutgoingChat.beforeChatSend(long())
        assertNull(sent)
    }

    @Test
    fun `whitespace is collapsed like vanilla will before the prefix match`() {
        OutgoingChat.beforeChatSend("  ${long()}\t\n  x  ")
        assertEquals("${long()} x", sent)
    }

    @Test
    fun `text that only clears the cap before collapsing ships nothing`() {
        // Vanilla's normalizeChatMessage collapses first, so this never gets trimmed.
        OutgoingChat.beforeChatSend("a".repeat(250) + " ".repeat(20) + "b")
        assertNull(sent)
    }
}
