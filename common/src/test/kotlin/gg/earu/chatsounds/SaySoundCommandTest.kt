package gg.earu.chatsounds

import gg.earu.chatsounds.client.SaySoundCommand
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The ';' gate is the command's only text rewriting: run() itself needs a client, so it
 * stays manually verified.
 */
class SaySoundCommandTest {
    @Test
    fun `a leading gate prefix is dropped`() {
        assertEquals("gaben", SaySoundCommand.normalize(";gaben"))
        assertEquals("gaben", SaySoundCommand.normalize(";;gaben"))
        assertEquals("gaben", SaySoundCommand.normalize("; gaben"))
    }

    @Test
    fun `separators inside the text survive`() {
        assertEquals("gaben;hello", SaySoundCommand.normalize("gaben;hello"))
        assertEquals("gaben;hello", SaySoundCommand.normalize(";gaben;hello"))
    }

    @Test
    fun `surrounding whitespace goes`() {
        assertEquals("gaben", SaySoundCommand.normalize("  gaben  "))
    }

    @Test
    fun `a bare prefix leaves nothing to play`() {
        assertEquals("", SaySoundCommand.normalize(";"))
        assertEquals("", SaySoundCommand.normalize("   "))
    }
}
