package gg.earu.chatsounds.client.compat

import gg.earu.chatsounds.Chatsounds
import gg.earu.chatsounds.audio.VoiceParams
import net.minecraft.client.resources.sounds.Sound
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.sounds.SoundManager
import net.minecraft.client.sounds.WeighedSoundEvents
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundSource

/**
 * Minimal SoundInstance facade over a voice's live params, handed to Dynamic Surroundings.
 * DS reads position (captureState), category, and — inside its inRange gate — the resolved
 * Sound's attenuation distance, so a real Sound object must be provided (a null there NPEs
 * inside DS and every environment calculation silently clears to dry).
 */
class VoiceSoundInstance(private val params: VoiceParams) : SoundInstance {
    private val location = ResourceLocation(Chatsounds.MOD_ID, "voice")
    private val sound = Sound(
        location.toString(), // 1.20.1 Sound takes the path as a string
        net.minecraft.util.valueproviders.ConstantFloat.of(1f),
        net.minecraft.util.valueproviders.ConstantFloat.of(1f),
        1,
        Sound.Type.FILE,
        false,
        false,
        params.maxDistance.toInt(),
    )

    override fun getLocation(): ResourceLocation = location
    override fun resolve(manager: SoundManager): WeighedSoundEvents? = null
    override fun getSound(): Sound = sound
    override fun getSource(): SoundSource = SoundSource.PLAYERS
    override fun isLooping(): Boolean = false
    override fun isRelative(): Boolean = params.relative
    override fun getDelay(): Int = 0
    override fun getVolume(): Float = params.volume
    override fun getPitch(): Float = 1f
    override fun getX(): Double = params.x
    override fun getY(): Double = params.y
    override fun getZ(): Double = params.z
    override fun getAttenuation(): SoundInstance.Attenuation = SoundInstance.Attenuation.LINEAR
}
