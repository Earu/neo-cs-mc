package gg.earu.chatsounds

import gg.earu.chatsounds.client.compat.SoundPhysicsBridge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The sound id handed to Sound Physics Remastered must be built without naming a Minecraft
 * method: production Fabric runs on intermediary names, so a by-name lookup resolves in dev
 * and on NeoForge but not in the jar players actually install.
 */
class SoundPhysicsBridgeTest {
    private val id = "chatsounds:voice"

    /** Mojang-mapped runtime (dev, NeoForge). */
    class OfficialId private constructor(private val value: String) {
        override fun toString() = value

        companion object {
            @JvmStatic
            fun parse(value: String) = OfficialId(value)

            @JvmStatic
            fun withDefaultNamespace(value: String): OfficialId {
                require(!value.contains(':')) { "non [a-z0-9/._-] character in path" }
                return OfficialId("minecraft:$value")
            }
        }
    }

    /** Intermediary runtime (production Fabric, 1.21.x). */
    class IntermediaryId private constructor(private val value: String) {
        override fun toString() = value

        companion object {
            @JvmStatic
            fun method_60654(value: String) = IntermediaryId(value)

            @JvmStatic
            fun method_12829(value: String): IntermediaryId {
                require(!value.contains(':')) { "non [a-z0-9/._-] character in path" }
                return IntermediaryId("minecraft:$value")
            }
        }
    }

    /** 1.20.1, which predates the static factories. */
    class ConstructorOnlyId(private val value: String) {
        override fun toString() = value
    }

    class UnusableId private constructor(@Suppress("unused") private val value: String)

    @Test
    fun `builds the id under official names`() {
        assertEquals(id, SoundPhysicsBridge.makeSoundId(OfficialId::class.java, id).toString())
    }

    @Test
    fun `builds the id under intermediary names`() {
        assertEquals(id, SoundPhysicsBridge.makeSoundId(IntermediaryId::class.java, id).toString())
    }

    @Test
    fun `falls back to the public constructor`() {
        assertEquals(id, SoundPhysicsBridge.makeSoundId(ConstructorOnlyId::class.java, id).toString())
    }

    @Test
    fun `reports drift instead of returning a wrong id`() {
        assertFailsWith<NoSuchMethodException> { SoundPhysicsBridge.makeSoundId(UnusableId::class.java, id) }
    }
}
