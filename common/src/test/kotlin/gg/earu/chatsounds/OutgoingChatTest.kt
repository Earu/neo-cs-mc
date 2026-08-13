package gg.earu.chatsounds

import gg.earu.chatsounds.client.OutgoingChat
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Only messages vanilla cannot carry take the mod channel. addToHistory stays false here:
 * the history call needs a running client, the routing decision does not.
 */
class OutgoingChatTest {
    private var sent: String? = null

    private fun acceptLong(accept: Boolean) {
        OutgoingChat.sendLong = { text ->
            sent = text
            accept
        }
    }

    @AfterTest
    fun reset() {
        OutgoingChat.sendLong = null
    }

    private fun long(prefix: String = "") = prefix + "a".repeat(OutgoingChat.VANILLA_CHAT_LIMIT + 1)

    @Test
    fun `short messages stay on the vanilla path`() {
        acceptLong(true)
        assertFalse(OutgoingChat.intercept("hello there", addToHistory = false))
        assertNull(sent)
    }

    @Test
    fun `a message exactly at the cap stays on the vanilla path`() {
        acceptLong(true)
        assertFalse(OutgoingChat.intercept("a".repeat(OutgoingChat.VANILLA_CHAT_LIMIT), addToHistory = false))
        assertNull(sent)
    }

    @Test
    fun `long messages take the mod channel`() {
        acceptLong(true)
        assertTrue(OutgoingChat.intercept(long(), addToHistory = false))
        assertEquals(long(), sent)
    }

    @Test
    fun `long commands stay on the vanilla path`() {
        acceptLong(true)
        assertFalse(OutgoingChat.intercept(long("/say "), addToHistory = false))
        assertNull(sent)
    }

    @Test
    fun `a server without the channel keeps the vanilla path`() {
        acceptLong(false)
        assertFalse(OutgoingChat.intercept(long(), addToHistory = false))
    }

    @Test
    fun `no loader hook keeps the vanilla path`() {
        OutgoingChat.sendLong = null
        assertFalse(OutgoingChat.intercept(long(), addToHistory = false))
    }

    @Test
    fun `whitespace is collapsed like vanilla does before sending`() {
        acceptLong(true)
        assertTrue(OutgoingChat.intercept("  ${long()}\t\n  x  ", addToHistory = false))
        assertEquals("${long()} x", sent)
    }
}
