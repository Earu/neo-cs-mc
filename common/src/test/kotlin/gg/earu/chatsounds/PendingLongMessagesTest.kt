package gg.earu.chatsounds

import gg.earu.chatsounds.server.PendingLongMessages
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Payload and chat message reach the server thread in either order (observed both ways in
 * practice), so the rendezvous must complete from whichever side arrives second.
 */
class PendingLongMessagesTest {
    private val player = UUID.randomUUID()
    private val other = UUID.randomUUID()
    private val full = "a".repeat(PendingLongMessages.VANILLA_CHAT_LIMIT) + "bbb"
    private val truncated = full.take(PendingLongMessages.VANILLA_CHAT_LIMIT)

    @Test
    fun `payload first, chat second pairs`() {
        val pending = PendingLongMessages()
        assertNull(pending.offerFull(player, full, now = 0.0))
        assertEquals(full, pending.offerChat(player, truncated, now = 0.1))
    }

    @Test
    fun `chat first, payload second pairs`() {
        val pending = PendingLongMessages()
        assertNull(pending.offerChat(player, truncated, now = 0.0))
        assertEquals(full, pending.offerFull(player, full, now = 0.1))
    }

    @Test
    fun `a short chat message passes straight through`() {
        val pending = PendingLongMessages()
        assertEquals("hello", pending.offerChat(player, "hello", now = 0.0))
    }

    @Test
    fun `an unrelated chat message does not claim the payload`() {
        val pending = PendingLongMessages()
        pending.offerFull(player, full, now = 0.0)
        assertEquals("hello", pending.offerChat(player, "hello", now = 0.1))
    }

    @Test
    fun `entries do not cross players`() {
        val pending = PendingLongMessages()
        pending.offerFull(player, full, now = 0.0)
        assertNull(pending.offerChat(other, truncated, now = 0.1))
    }

    @Test
    fun `a stale payload is not paired`() {
        val pending = PendingLongMessages(ttlSeconds = 5.0)
        pending.offerFull(player, full, now = 0.0)
        assertNull(pending.offerChat(player, truncated, now = 5.1))
    }

    @Test
    fun `a waiting chat message is released truncated once its grace expires`() {
        val pending = PendingLongMessages(graceSeconds = 1.0)
        pending.offerChat(player, truncated, now = 0.0)
        assertTrue(pending.flush(now = 0.5).isEmpty())
        val flushed = pending.flush(now = 1.5)
        assertEquals(1, flushed.size)
        assertEquals(truncated, flushed.single().text)
        assertEquals(player, flushed.single().player)
    }

    @Test
    fun `a flushed chat message is not flushed twice`() {
        val pending = PendingLongMessages(graceSeconds = 1.0)
        pending.offerChat(player, truncated, now = 0.0)
        pending.flush(now = 1.5)
        assertTrue(pending.flush(now = 2.5).isEmpty())
    }

    @Test
    fun `pairing consumes the waiting chat message before its flush`() {
        val pending = PendingLongMessages(graceSeconds = 1.0)
        pending.offerChat(player, truncated, now = 0.0)
        assertEquals(full, pending.offerFull(player, full, now = 0.2))
        assertTrue(pending.flush(now = 1.5).isEmpty())
    }

    @Test
    fun `a chat message past its grace does not pair with a late payload`() {
        val pending = PendingLongMessages(graceSeconds = 1.0)
        pending.offerChat(player, truncated, now = 0.0)
        assertNull(pending.offerFull(player, full, now = 1.5))
    }

    @Test
    fun `a replaced waiting chat message is released rather than dropped`() {
        val pending = PendingLongMessages()
        val second = "z".repeat(PendingLongMessages.VANILLA_CHAT_LIMIT)
        pending.offerChat(player, truncated, now = 0.0)
        assertEquals(truncated, pending.offerChat(player, second, now = 0.1))
    }

    @Test
    fun `leaving clears both sides`() {
        val pending = PendingLongMessages()
        pending.offerFull(player, full, now = 0.0)
        pending.offerChat(player, truncated, now = 0.1).let { /* paired */ }
        pending.offerChat(player, truncated, now = 0.2)
        pending.forget(player)
        assertTrue(pending.flush(now = 5.0).isEmpty())
    }
}
