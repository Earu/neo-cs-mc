package gg.earu.chatsounds.audio

import gg.earu.chatsounds.Chatsounds
import org.lwjgl.openal.AL10
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.math.sin

/** Positional/gain parameters written by the game thread each tick, read by the DSP thread. */
class VoiceParams {
    /** Listener-side gain: sound-category volume x mod config (modifier volume is DSP-side). */
    @Volatile var volume: Float = 1f
    @Volatile var x: Double = 0.0
    @Volatile var y: Double = 0.0
    @Volatile var z: Double = 0.0
    /** First-person own voice: position relative to the listener (0,0,0 = "flat volume"). */
    @Volatile var relative: Boolean = false
    @Volatile var maxDistance: Float = 64f
}

/**
 * Mixer-side parameters, the port of the JS stream fields (webaudio.lua). Everything is in
 * the "JS domain": sample positions/lengths at the output rate, matching the browser's
 * decodeAudioData-resampled buffers, so loops, seeks, echo indexing, and LFO phases behave
 * exactly like GMod.
 */
class DspParams {
    /** stream.speed — absolute playback rate; reverse is the sticky flag below. */
    @Volatile var jsSpeed: Double = 1.0
    @Volatile var reverse: Boolean = false
    /** stream.vol_both user part; normalization gain is folded in by the mixer underneath it. */
    @Volatile var volumeMod: Float = 1f
    /** 0 none, 1 lowpass, 2 highpass. */
    @Volatile var filterType: Int = 0
    @Volatile var filterFraction: Float = 1f
    @Volatile var useEcho: Boolean = false
    @Volatile var echoDelaySamples: Int = 48_000
    @Volatile var echoFeedback: Float = 0.75f
    @Volatile var lfoPitchTime: Double = 0.0
    @Volatile var lfoPitchAmount: Double = 0.0
    @Volatile var lfoVolumeTime: Double = 0.0
    @Volatile var lfoVolumeAmount: Double = 0.0
    /** Playthrough count; -1 = infinite (JS default 1 = play once). */
    @Volatile var maxLoop: Int = 1
    /** One-shot seek request in JS-domain samples; the mixer consumes values >= 0. */
    @Volatile var seekPosition: Double = -1.0
}

class Voice(
    val clip: PcmClip,
    val params: VoiceParams,
    val dsp: DspParams,
) {
    @Volatile var stopRequested: Boolean = false
    /** True once the source drained everything (or was stopped). */
    @Volatile var finished: Boolean = false

    // DSP-thread state.
    internal var source = 0
    internal var freeBuffers = IntArray(0)
    internal var freeCount = 0
    /** JS-domain playhead (advances by jsSpeed per output sample). */
    internal var position = 0.0
    internal var donePlaying = false
    internal var filterSm = 0f
    internal var echoBuffer: FloatArray? = null
    internal var allQueued = false
    internal var initialized = false

    // Environmental audio integration (Sound Physics Remastered or Dynamic Surroundings).
    internal var usesSoundPhysics = false
    internal var dsContext: Any? = null
    internal var dsCounter = 0

    fun stop() {
        stopRequested = true
    }
}

/**
 * The mixer: a dedicated daemon thread owning our own AL sources on Minecraft's AL context.
 * OpenAL has no per-thread context binding, so calling AL from this thread is safe once the
 * game's sound library exists — and vanilla keeps the AL listener (position, orientation,
 * master gain) updated every frame for free.
 *
 * Per-voice synthesis is the faithful mono port of the webaudio.lua ScriptProcessor loop:
 * nearest-neighbor rate conversion (that aliasing IS the chatsounds sound), one-pole
 * filters, feedback-echo ring buffer, pitch/volume LFOs, sticky reverse, sample-accurate
 * loop counting, and loudness normalization folded underneath every modifier. Deliberate
 * divergences: 3D pan/attenuation is OpenAL's job (GMod hand-rolled it), per-voice clamping
 * replaces the shared-output clamp, and one-pole filter state persists across blocks
 * (GMod's reset every ScriptProcessor callback was an artifact of the block boundary).
 */
object AudioEngine {
    private const val OUT_RATE = 48_000
    private const val BLOCK_FRAMES = 960 // 20 ms
    private const val QUEUE_DEPTH = 4
    /** Echo tails ring out until quieter than this (GMod kept them until stream removal). */
    private const val ECHO_TAIL_FLOOR = 1e-4f

    private val pending = ConcurrentLinkedQueue<Voice>()
    private val voices = CopyOnWriteArrayList<Voice>()
    @Volatile private var stopAllRequested = false
    @Volatile private var running = false

    /**
     * Sources within this many blocks of the listener pan omnidirectionally instead of
     * hard left/right (AL_EXT_SOURCE_RADIUS). Without it, a voice at your own head flips
     * ears constantly from tiny listener/source offsets while moving or looking around.
     */
    private const val NEAR_FIELD_RADIUS = 2f
    private var sourceRadiusSupported = false
    private var checkedSourceRadius = false

    internal val blockFloats = FloatArray(BLOCK_FRAMES)
    private val blockShorts = ShortArray(BLOCK_FRAMES)
    private val blockBytes: ByteBuffer = ByteBuffer.allocateDirect(BLOCK_FRAMES * 2).order(ByteOrder.nativeOrder())

    fun start() {
        if (running) return
        synchronized(this) {
            if (running) return
            running = true
        }
        gg.earu.chatsounds.client.compat.DsurroundBridge.init()
        thread(name = "chatsounds-audio", isDaemon = true) { runLoop() }
    }

    fun play(clip: PcmClip, params: VoiceParams, dsp: DspParams): Voice {
        val voice = Voice(clip, params, dsp)
        pending.add(voice)
        return voice
    }

    /** The "sh" path: immediate stop of every chatsound voice. */
    fun stopAll() {
        stopAllRequested = true
    }

    val activeVoices: Int get() = voices.size

    private fun runLoop() {
        Chatsounds.logger.info("Audio engine thread started")
        while (running) {
            // The game tears its AL context down on shutdown and F3+T device reloads; AL
            // calls without a context all fail with AL_INVALID_OPERATION. Exit quietly —
            // the next played sound restarts the engine against the fresh context.
            if (org.lwjgl.openal.ALC10.alcGetCurrentContext() == 0L) {
                for (voice in voices) voice.finished = true
                voices.clear()
                pending.clear()
                synchronized(this) { running = false }
                Chatsounds.logger.info("OpenAL context gone; audio engine thread exiting")
                return
            }
            try {
                tick()
            } catch (e: Throwable) {
                Chatsounds.logger.error("Audio engine tick failed", e)
            }
            Thread.sleep(5)
        }
    }

    private fun tick() {
        if (stopAllRequested) {
            stopAllRequested = false
            for (voice in voices) voice.stopRequested = true
        }

        while (true) {
            val voice = pending.poll() ?: break
            voices.add(voice)
        }

        for (voice in voices) {
            if (!voice.initialized) initVoice(voice)
            pump(voice)
            if (voice.finished) {
                destroyVoice(voice)
                voices.remove(voice)
            }
        }

        val err = AL10.alGetError()
        if (err != AL10.AL_NO_ERROR) {
            Chatsounds.logger.warn("OpenAL error 0x{}", Integer.toHexString(err))
        }
    }

    private fun initVoice(voice: Voice) {
        voice.source = AL10.alGenSources()
        voice.freeBuffers = IntArray(QUEUE_DEPTH)
        AL10.alGenBuffers(voice.freeBuffers)
        voice.freeCount = QUEUE_DEPTH

        // Minecraft enables AL_EXT_source_distance_model, so the distance model is PER
        // SOURCE — without setting it here a source gets no attenuation at all. Mirror
        // vanilla Channel.linearAttenuation: linear falloff to zero at maxDistance.
        AL10.alSourcei(voice.source, org.lwjgl.openal.AL11.AL_DISTANCE_MODEL, org.lwjgl.openal.AL11.AL_LINEAR_DISTANCE_CLAMPED)
        AL10.alSourcef(voice.source, AL10.AL_ROLLOFF_FACTOR, 1f)
        AL10.alSourcef(voice.source, AL10.AL_REFERENCE_DISTANCE, 0f)
        AL10.alSourcef(voice.source, AL10.AL_MAX_DISTANCE, voice.params.maxDistance)

        if (!checkedSourceRadius) {
            checkedSourceRadius = true
            sourceRadiusSupported = AL10.alIsExtensionPresent("AL_EXT_SOURCE_RADIUS")
        }
        if (sourceRadiusSupported) {
            AL10.alSourcef(voice.source, org.lwjgl.openal.EXTSourceRadius.AL_SOURCE_RADIUS, NEAR_FIELD_RADIUS)
        }

        voice.initialized = true
    }

    private fun pump(voice: Voice) {
        val src = voice.source

        if (voice.stopRequested) {
            AL10.alSourceStop(src)
            voice.finished = true
            return
        }

        var processed = AL10.alGetSourcei(src, AL10.AL_BUFFERS_PROCESSED)
        while (processed-- > 0) {
            val buf = AL10.alSourceUnqueueBuffers(src)
            if (voice.freeCount < voice.freeBuffers.size) {
                voice.freeBuffers[voice.freeCount++] = buf
            }
        }

        val p = voice.params
        AL10.alSourcei(src, AL10.AL_SOURCE_RELATIVE, if (p.relative) AL10.AL_TRUE else AL10.AL_FALSE)
        if (p.relative) {
            AL10.alSource3f(src, AL10.AL_POSITION, 0f, 0f, 0f)
        } else {
            AL10.alSource3f(src, AL10.AL_POSITION, p.x.toFloat(), p.y.toFloat(), p.z.toFloat())
        }
        AL10.alSourcef(src, AL10.AL_MAX_DISTANCE, p.maxDistance)
        AL10.alSourcef(src, AL10.AL_GAIN, maxOf(0f, p.volume))

        while (!voice.allQueued && voice.freeCount > 0) {
            val produced = synthesizeBlock(voice)
            if (produced == 0) break
            for (i in 0 until produced) {
                var v = blockFloats[i]
                if (v > 1f) v = 1f else if (v < -1f) v = -1f
                blockShorts[i] = (v * 32767f).toInt().toShort()
            }
            val buf = voice.freeBuffers[--voice.freeCount]
            blockBytes.clear()
            blockBytes.asShortBuffer().put(blockShorts, 0, produced)
            blockBytes.limit(produced * 2)
            AL10.alBufferData(buf, AL10.AL_FORMAT_MONO16, blockBytes, OUT_RATE)
            AL10.alSourceQueueBuffers(src, buf)
        }

        val state = AL10.alGetSourcei(src, AL10.AL_SOURCE_STATE)
        val queued = AL10.alGetSourcei(src, AL10.AL_BUFFERS_QUEUED)
        if (state != AL10.AL_PLAYING && queued > 0) {
            // Initial start and underrun recovery share this path.
            AL10.alSourcePlay(src)
        }
        if (voice.allQueued && queued == 0 && state != AL10.AL_PLAYING) {
            voice.finished = true
        }

        // Environmental audio bridges. Sound Physics Remastered takes priority over
        // Dynamic Surroundings (both drive the same EFX sends; applying both would fight).
        // Engage once the voice is positioned (the first client tick raises volume from 0);
        // re-evaluate ~3x/s so moving speakers stay correct.
        voice.dsCounter++
        if (voice.usesSoundPhysics) {
            if (voice.dsCounter % 70 == 0) {
                gg.earu.chatsounds.client.compat.SoundPhysicsBridge.process(src, p.x, p.y, p.z)
            }
        } else {
            val ctx = voice.dsContext
            if (ctx != null) {
                if (voice.dsCounter % 10 == 0) gg.earu.chatsounds.client.compat.DsurroundBridge.tick(ctx)
                if (voice.dsCounter % 70 == 0) gg.earu.chatsounds.client.compat.DsurroundBridge.calc(ctx)
            } else if (voice.dsCounter % 10 == 0 && !p.relative && p.volume > 0f) {
                if (gg.earu.chatsounds.client.compat.SoundPhysicsBridge.process(src, p.x, p.y, p.z)) {
                    voice.usesSoundPhysics = true
                } else {
                    voice.dsContext = gg.earu.chatsounds.client.compat.DsurroundBridge.register(
                        src, gg.earu.chatsounds.client.compat.VoiceSoundInstance(p)
                    )
                }
            }
        }
    }

    /** Fills [blockFloats]; returns frames produced (0 = nothing left to play). Internal for the parity tests. */
    internal fun synthesizeBlock(voice: Voice): Int {
        val dsp = voice.dsp
        val samples = voice.clip.samples
        val srcLen = samples.size
        if (srcLen == 0) {
            voice.allQueued = true
            return 0
        }

        // JS-domain buffer length: what decodeAudioData would have produced at the output rate.
        val srcRatio = voice.clip.sampleRate.toDouble() / OUT_RATE
        val lenJs = Math.round(srcLen / srcRatio).coerceAtLeast(1)

        val seek = dsp.seekPosition
        if (seek >= 0) {
            voice.position = seek
            dsp.seekPosition = -1.0
        }

        val volBoth = dsp.volumeMod * voice.clip.normalizeGain
        val filterType = dsp.filterType
        val filterFraction = dsp.filterFraction
        val useEcho = dsp.useEcho
        val echoFeedback = dsp.echoFeedback
        val lfoVolumeTime = dsp.lfoVolumeTime
        val lfoVolumeAmount = dsp.lfoVolumeAmount
        val lfoPitchTime = dsp.lfoPitchTime
        val lfoPitchAmount = dsp.lfoPitchAmount
        val maxLoop = dsp.maxLoop
        val reverse = dsp.reverse
        val baseSpeed = dsp.jsSpeed

        var echoDelay = 0
        var echo: FloatArray? = null
        if (useEcho) {
            echoDelay = dsp.echoDelaySamples.coerceAtLeast(1)
            var size = 1
            while (size < echoDelay) size = size shl 1
            if (voice.echoBuffer?.size != size) voice.echoBuffer = FloatArray(size)
            echo = voice.echoBuffer
        }

        var sm = voice.filterSm
        var position = voice.position
        var done = voice.donePlaying
        var produced = 0
        var blockPeak = 0f

        while (produced < BLOCK_FRAMES) {
            if (done || (maxLoop > 0 && position > lenJs.toDouble() * maxLoop)) {
                done = true
                if (!useEcho) break
            }

            var indexJs = (position.toLong() % lenJs)
            if (reverse) indexJs = lenJs - indexJs
            val srcIndex = ((indexJs * srcRatio).toInt()).coerceIn(0, srcLen - 1)

            var out = 0f
            if (!done) {
                val raw = samples[srcIndex]
                if (filterType == 0) {
                    out = raw * volBoth
                } else {
                    sm += (raw - sm) * filterFraction
                    out = (if (filterType == 1) sm else raw - sm) * volBoth
                }
                if (out > 1f) out = 1f else if (out < -1f) out = -1f
            }

            if (lfoVolumeTime != 0.0) {
                out = (out * sin(position / OUT_RATE * 10.0 * lfoVolumeTime) * lfoVolumeAmount).toFloat()
            }

            if (useEcho && echo != null) {
                val echoIndex = (position.toLong() % echoDelay).toInt()
                echo[echoIndex] = echo[echoIndex] * echoFeedback + out
                out = echo[echoIndex]
            }

            var speed = baseSpeed
            if (lfoPitchTime != 0.0) {
                speed -= sin(position / OUT_RATE * 10.0 * lfoPitchTime) * lfoPitchAmount
                val half = lfoPitchAmount * 0.5
                speed += half * half
            }
            position += speed

            if (out.isNaN() || out.isInfinite()) out = 0f
            val a = if (out < 0) -out else out
            if (a > blockPeak) blockPeak = a
            blockFloats[produced++] = out
        }

        voice.filterSm = sm
        voice.position = position
        voice.donePlaying = done

        // Echo tails ring out after the dry signal ends; cut once inaudible.
        if (done && (!useEcho || (produced > 0 && blockPeak < ECHO_TAIL_FLOOR))) {
            voice.allQueued = true
        }
        return produced
    }

    private fun destroyVoice(voice: Voice) {
        if (!voice.initialized) return
        voice.dsContext?.let {
            gg.earu.chatsounds.client.compat.DsurroundBridge.unregister(voice.source, it)
            voice.dsContext = null
        }
        AL10.alSourceStop(voice.source)
        var queued = AL10.alGetSourcei(voice.source, AL10.AL_BUFFERS_QUEUED)
        while (queued-- > 0) AL10.alDeleteBuffers(AL10.alSourceUnqueueBuffers(voice.source))
        AL10.alDeleteSources(voice.source)
        for (i in 0 until voice.freeCount) AL10.alDeleteBuffers(voice.freeBuffers[i])
        voice.initialized = false
        voice.echoBuffer = null
    }
}
