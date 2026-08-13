package gg.earu.chatsounds

import gg.earu.chatsounds.server.PendingLongMessages
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PendingLongMessagesTest {
    private val player = UUID.randomUUID()
    private val other = UUID.randomUUID()
    private val full = "a".repeat(256) + "bbb"
    private val truncated = full.take(256)

    @Test
    fun `pairs the chat message with the text it was cut from`() {
        val pending = PendingLongMessages()
        pending.offer(player, full, now = 0.0)
        assertEquals(full, pending.take(player, truncated, now = 0.1))
    }

    @Test
    fun `a pair is consumed once`() {
        val pending = PendingLongMessages()
        pending.offer(player, full, now = 0.0)
        pending.take(player, truncated, now = 0.1)
        assertNull(pending.take(player, truncated, now = 0.2))
    }

    @Test
    fun `an unrelated chat message does not claim the text`() {
        val pending = PendingLongMessages()
        pending.offer(player, full, now = 0.0)
        assertNull(pending.take(player, "hello", now = 0.1))
    }

    @Test
    fun `a stale entry is dropped`() {
        val pending = PendingLongMessages(ttlSeconds = 5.0)
        pending.offer(player, full, now = 0.0)
        assertNull(pending.take(player, truncated, now = 5.1))
    }

    @Test
    fun `entries do not cross players`() {
        val pending = PendingLongMessages()
        pending.offer(player, full, now = 0.0)
        assertNull(pending.take(other, truncated, now = 0.1))
    }

    @Test
    fun `a newer message replaces the one still waiting`() {
        val pending = PendingLongMessages()
        val newer = "z".repeat(256) + "yy"
        pending.offer(player, full, now = 0.0)
        pending.offer(player, newer, now = 0.1)
        assertEquals(newer, pending.take(player, newer.take(256), now = 0.2))
    }

    @Test
    fun `leaving clears what was waiting`() {
        val pending = PendingLongMessages()
        pending.offer(player, full, now = 0.0)
        pending.forget(player)
        assertNull(pending.take(player, truncated, now = 0.1))
    }

    @Test
    fun `a chat message identical to the long text does not pair`() {
        // Nothing was truncated, so there is no long half to substitute.
        val pending = PendingLongMessages()
        pending.offer(player, full, now = 0.0)
        assertNull(pending.take(player, full, now = 0.1))
    }
}
