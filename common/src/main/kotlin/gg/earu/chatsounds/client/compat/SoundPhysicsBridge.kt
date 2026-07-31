package gg.earu.chatsounds.client.compat

import gg.earu.chatsounds.Chatsounds
import net.minecraft.sounds.SoundSource
import java.lang.reflect.Method

/**
 * Optional Sound Physics Remastered (sound_physics_remastered) integration. SPR exposes a
 * public static entry point designed for third-party OpenAL sources (Simple Voice Chat uses
 * it the same way):
 *
 *   SoundPhysics.processSound(int sourceID, double x, double y, double z, SoundSource, <id>)
 *
 * One call raycasts the environment and applies reverb/occlusion EFX to the source; when
 * SPR is disabled in its config the call is a cheap no-op. The sound-id parameter type
 * renamed across MC versions (ResourceLocation -> Identifier), so the method is resolved by
 * name/arity and the id instance is built from whatever type it declares. Everything is
 * reflective — no compile dependency; drift disables the bridge with one log line.
 */
object SoundPhysicsBridge {
    private var resolved = false
    private var broken = false

    private var processSound: Method? = null
    private var soundId: Any? = null
    private var loggedFirstUse = false

    fun init() {
        if (resolved || broken) return
        try {
            val loader = javaClass.classLoader
            val clazz = Class.forName("com.sonicether.soundphysics.SoundPhysics", false, loader)

            val method = clazz.declaredMethods.firstOrNull { m ->
                m.name == "processSound" && m.parameterCount == 6 &&
                    m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    m.parameterTypes[4] == SoundSource::class.java
            } ?: error("no processSound(int, double, double, double, SoundSource, id) overload")

            // Build the sound-id instance from the declared parameter type, whatever era it is.
            val idType = method.parameterTypes[5]
            val idString = "${Chatsounds.MOD_ID}:voice"
            soundId = runCatching { idType.getMethod("parse", String::class.java).invoke(null, idString) }
                .recoverCatching { idType.getMethod("tryParse", String::class.java).invoke(null, idString)!! }
                .recoverCatching { idType.getConstructor(String::class.java).newInstance(idString) }
                .getOrThrow()

            processSound = method
            resolved = true
            Chatsounds.logger.info("Sound Physics Remastered detected — chatsounds voices will get environmental audio")
        } catch (_: ClassNotFoundException) {
            broken = true // not installed; stay silent and never retry
        } catch (e: Throwable) {
            broken = true
            Chatsounds.logger.warn("Sound Physics Remastered found but its API changed; bridge disabled ({})", e.toString())
        }
    }

    val present: Boolean
        get() {
            init()
            return resolved
        }

    /** Raycasts the environment and applies EFX to the source; safe from the audio thread. */
    fun process(sourceId: Int, x: Double, y: Double, z: Double): Boolean {
        if (!present) return false
        return try {
            processSound!!.invoke(null, sourceId, x, y, z, SoundSource.PLAYERS, soundId)
            if (!loggedFirstUse) {
                loggedFirstUse = true
                Chatsounds.logger.info("Chatsounds voice processed by Sound Physics Remastered (source {})", sourceId)
            }
            true
        } catch (e: Throwable) {
            broken = true
            resolved = false
            Chatsounds.logger.warn("Sound Physics processing failed; bridge disabled ({})", e.toString())
            false
        }
    }
}
