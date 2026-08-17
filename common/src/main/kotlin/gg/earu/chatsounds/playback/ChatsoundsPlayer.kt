package gg.earu.chatsounds.playback

import gg.earu.chatsounds.Chatsounds
import gg.earu.chatsounds.ClientConfig
import gg.earu.chatsounds.audio.AudioEngine
import gg.earu.chatsounds.audio.DspParams
import gg.earu.chatsounds.audio.PcmCache
import gg.earu.chatsounds.audio.Voice
import gg.earu.chatsounds.audio.VoiceParams
import gg.earu.chatsounds.data.Blacklist
import gg.earu.chatsounds.data.DataLoader
import gg.earu.chatsounds.data.SoundVariant
import gg.earu.chatsounds.modifiers.ModifierInstance
import gg.earu.chatsounds.parser.GroupNode
import gg.earu.chatsounds.parser.ParseNode
import gg.earu.chatsounds.parser.Parser
import gg.earu.chatsounds.parser.SoundNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.sounds.SoundSource
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.random.Random

/**
 * Client playback orchestration — the full port of player.lua's client half. A message
 * splits on ';' into independent parallel contexts; within one context the AST is flattened
 * (repeat duplication, ancestor modifier inheritance), variants get picked (seeded +
 * realm-matched), downloads fire up front, and sounds then play strictly in order — each
 * "done" after its (modifier-scaled) duration while overlapping tails keep ringing.
 */
object ChatsoundsPlayer {
    private const val CONTEXT_SEPARATOR = ';'
    private const val OUT_RATE = 48_000

    /** GMod trims ~75 ms of trailing hiss off every sound's scheduling duration. */
    private const val WHITENOISE_FUDGE_SECONDS = 0.075

    /** flatten_sounds guard: repeat modifiers cannot expand a message past this. */
    private const val MAX_ITERATIONS = 100

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile var enabled = true

    private class ActiveSound(
        val speakerId: UUID?,
        val params: VoiceParams,
        val voice: Voice,
        val stream: ChatStream,
        val modifiers: List<ModifierInstance>,
    )

    private val activeSounds = CopyOnWriteArrayList<ActiveSound>()

    /**
     * Bumped by every stop: playing contexts check it before starting each sound, so "sh"
     * also kills what was queued (downloading, or waiting out an earlier sound's duration),
     * not just the voices already audible.
     */
    private val stopEpoch = java.util.concurrent.atomic.AtomicInteger()

    /**
     * The ';'-prefix gate: normally a leading ';' blocks chatsounds for a message;
     * with invertPrefix only ';'-prefixed messages play (prefix stripped). Returns the
     * text to parse, or null when the message should stay silent.
     */
    fun effectiveText(text: String): String? {
        val prefixed = text.startsWith(CONTEXT_SEPARATOR)
        return if (ClientConfig.data.invertPrefix) {
            if (prefixed) text.substring(1) else null
        } else {
            if (prefixed) null else text
        }
    }

    /** [isOwn]: whether the local player sent the message (sh-mode 1 gating). */
    fun play(speakerId: UUID?, text: String, isOwn: Boolean = speakerId == null) {
        val effective = effectiveText(text) ?: return
        playDirect(speakerId, effective, isOwn)
    }

    /** The /saysound path: skips the ';' prefix gate, but ';' still splits contexts. */
    fun playDirect(speakerId: UUID?, text: String, isOwn: Boolean) {
        if (!enabled || !ClientConfig.data.enabled) return
        if (DataLoader.loading != null) return

        val lowered = text.lowercase(Locale.ROOT)
        for (chunk in lowered.split(CONTEXT_SEPARATOR)) {
            scope.launch {
                try {
                    playContext(speakerId, chunk, isOwn)
                } catch (e: Exception) {
                    Chatsounds.logger.error("Failed to play chatsounds context", e)
                }
            }
        }
    }

    private fun shAllowed(isOwn: Boolean): Boolean = when (ClientConfig.data.shMode) {
        0 -> false
        1 -> isOwn
        else -> true
    }

    fun stopAll() {
        stopEpoch.incrementAndGet()
        AudioEngine.stopAll()
    }

    // ---- AST flattening (player.lua flatten_sounds / get_all_modifiers / sound_pre_process) ----

    private fun getAllModifiers(scope: GroupNode?, out: MutableList<ModifierInstance>) {
        var cur = scope
        while (cur != null) {
            cur.modifiers?.forEach { if (!it.def.noInheritance) out.add(it) }
            cur = cur.parent
        }
    }

    private fun duplicateCount(modifiers: List<ModifierInstance>?): Int {
        modifiers?.forEach { inst ->
            inst.def.duplicateCount(inst)?.let { return it }
        }
        return 1
    }

    private fun flattenSounds(group: GroupNode, out: MutableList<SoundNode> = ArrayList(), totalIters: IntArray = intArrayOf(0)): MutableList<SoundNode> {
        fun addIters(input: Int): Int {
            var iters = minOf(MAX_ITERATIONS, input)
            if (totalIters[0] + iters > MAX_ITERATIONS) iters = 1
            totalIters[0] += iters
            return iters
        }

        for (child in group.children) {
            when {
                child is SoundNode -> {
                    val iters = addIters(duplicateCount(child.modifiers))
                    repeat(iters) {
                        // GMod parity: inherited modifiers append to the SAME node each iteration.
                        getAllModifiers(child.parentScope, child.modifiers)
                        out.add(child)
                    }
                }
                child is GroupNode && !child.isModifierExpression -> {
                    val iters = addIters(duplicateCount(child.modifiers))
                    repeat(iters) { flattenSounds(child, out, totalIters) }
                }
            }
        }
        return out
    }

    // ---- Variant selection (cs_player.GetWantedSound) ----

    private fun getWantedSound(sound: SoundNode, lastSound: SoundVariant?, seed: Long): SoundVariant? {
        var pool = DataLoader.lookup.list[sound.key].orEmpty()
        if (pool.isEmpty()) return null

        val rng = Random(seed)
        var index = rng.nextInt(pool.size) + 1 // 1-based, GMod parity
        var modified = false

        for (inst in sound.modifiers) {
            val result = inst.def.onSelection(inst, index, pool) ?: continue
            index = result.first
            pool = result.second
            modified = true
        }

        // Match realms together if we can, so one sentence keeps a consistent voice.
        if (!modified && lastSound != null) {
            val realmPool = pool.filter { it.realm == lastSound.realm }
            if (realmPool.isNotEmpty()) {
                pool = realmPool
                index = rng.nextInt(pool.size) + 1
            }
        }

        if (pool.isEmpty()) return null
        return pool[index.coerceIn(1, pool.size) - 1]
    }

    // ---- Context playback ----

    private suspend fun playContext(speakerId: UUID?, chunk: String, isOwn: Boolean) {
        val group = Parser.parse(chunk, DataLoader.lookup)
        val sounds = flattenSounds(group)
        if (sounds.isEmpty()) return

        val timeSeed = System.currentTimeMillis() / 1000L

        class Queued(val node: SoundNode, val variant: SoundVariant?)

        var lastSound: SoundVariant? = null
        val queued = sounds.mapIndexed { i, node ->
            if (node.key == "sh") return@mapIndexed Queued(node, null)
            val variant = getWantedSound(node, lastSound, timeSeed + i * 1028L)
                ?: return@mapIndexed Queued(node, null)
            lastSound = variant
            Queued(node, variant)
        }

        // Downloads all fire immediately (bounded by the HTTP queue); playback walks in order.
        val downloads = queued.map { q -> q.variant?.let { v -> scope.async { SoundDownloader.ensure(v) } } }

        var epoch = stopEpoch.get()
        for ((i, q) in queued.withIndex()) {
            if (q.node.key == "sh") {
                if (shAllowed(isOwn)) stopAll()
                // Our own sh must not kill the rest of THIS context ("sh gaben" plays gaben).
                epoch = stopEpoch.get()
                continue
            }
            if (stopEpoch.get() != epoch) return // an sh landed while we were queued
            val variant = q.variant ?: continue
            val file = downloads[i]?.await() ?: continue

            val clip = try {
                PcmCache.getOrDecode(variant.url, file)
            } catch (e: Exception) {
                Chatsounds.logger.warn("Failed to decode {}: {}", variant.url, e.message)
                continue
            }

            val params = VoiceParams()
            params.volume = 0f // silent until the first client tick positions it
            val dsp = DspParams()
            val stream = ChatStream(clip, dsp, OUT_RATE)
            stream.duration = clip.durationSeconds - WHITENOISE_FUDGE_SECONDS
            stream.overlap = true
            stream.lifetime = null

            for (inst in q.node.modifiers) {
                inst.def.onStreamInit(inst, stream)
            }

            val duration = max(0.0, stream.duration)

            // Blocked sounds still occupy their scheduled slot silently (GMod removes the
            // stream but lets the duration timer run).
            if (Blacklist.isSoundBlocked(q.node.key, variant)) {
                delay((duration * 1000).toLong())
                continue
            }

            if (stopEpoch.get() != epoch) return // an sh landed during download/decode
            val voice = AudioEngine.play(clip, params, dsp)
            stream.voice = voice
            activeSounds.add(ActiveSound(speakerId, params, voice, stream, q.node.modifiers))

            // A stream can outlive its scheduling slot (looping/overlap); remove it once done.
            if (stream.overlap || stream.lifetime != null) {
                val lifetime = max(stream.lifetime ?: duration, clip.durationSeconds)
                scope.launch {
                    delay(((lifetime + 1) * 1000).toLong())
                    voice.stop()
                }
            }

            delay((duration * 1000).toLong())
            if (!stream.overlap && stream.lifetime == null) {
                voice.stop()
            }
        }
    }

    /**
     * Game-thread tick: entity positions/volumes into each voice's param block, plus every
     * modifier's OnStreamThink (expression-driven pitch/volume, legacy lerps, seeks).
     */
    fun clientTick() {
        if (activeSounds.isEmpty()) return
        val mc = Minecraft.getInstance()
        val level = mc.level
        val categoryVolume = mc.options.getSoundSourceVolume(SoundSource.PLAYERS) *
            ClientConfig.data.volume.toFloat()

        val maxDistance = ClientConfig.data.maxDistance.toFloat()
        for (active in activeSounds) {
            if (active.voice.finished) {
                activeSounds.remove(active)
                continue
            }

            for (inst in active.modifiers) {
                inst.def.onStreamThink(inst, active.stream)
            }

            val params = active.params
            params.maxDistance = maxDistance
            val speaker = active.speakerId?.let { level?.getPlayerByUUID(it) }
            when {
                active.speakerId == null -> {
                    params.relative = true
                    params.volume = categoryVolume
                }
                speaker == null -> {
                    // Speaker not in this level / out of tracking range: GMod's dormant behavior.
                    params.volume = 0f
                }
                // Own voice stays positional (a source at the listener sounds like flat
                // playback anyway) so environmental processors like Dynamic Surroundings
                // don't skip it the way they skip relative sounds.
                else -> {
                    params.relative = false
                    val eye = speaker.eyePosition
                    params.x = eye.x
                    params.y = eye.y
                    params.z = eye.z
                    params.volume = categoryVolume
                }
            }
        }
    }
}
