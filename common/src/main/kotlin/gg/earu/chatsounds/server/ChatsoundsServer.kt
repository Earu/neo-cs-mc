package gg.earu.chatsounds.server

import gg.earu.chatsounds.Chatsounds
import gg.earu.chatsounds.data.RepoConfig
import gg.earu.chatsounds.net.ChatsoundsPayloads
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Loader-agnostic server side: relays chat text (never audio) to modded listeners in
 * range, with spam control and the server's repo config as authority. The loader module
 * wires [sendToPlayer]/[canSendTo] at init.
 */
object ChatsoundsServer {
    @Serializable
    data class ServerConfigData(
        /** Hearing radius in blocks; sounds relay only to players this close to the speaker. */
        val radiusBlocks: Double = 128.0,
        /** Ops are exempt from spam control (GMod admin parity). */
        val exemptOps: Boolean = true,
    )

    private const val STR_NETWORKING_LIMIT = 60_000

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val spam = SpamBucket()
    private val pending = PendingLongMessages()

    @Volatile private var config = ServerConfigData()
    @Volatile private var repoConfigJson: String = ""

    /** Wired by the loader module (PacketDistributor / ServerPlayNetworking). */
    var sendToPlayer: (ServerPlayer, CustomPacketPayload) -> Unit = { _, _ -> }
    var canSendTo: (ServerPlayer, CustomPacketPayload.Type<*>) -> Boolean = { _, _ -> false }

    fun loadConfig() {
        val dir = Chatsounds.platform.configDir
        val file = dir.resolve("server_config.json")
        config = if (file.exists()) {
            try {
                json.decodeFromString<ServerConfigData>(file.readText())
            } catch (e: Exception) {
                Chatsounds.logger.error("Failed to load server_config.json: {}", e.message)
                ServerConfigData()
            }
        } else {
            dir.createDirectories()
            ServerConfigData().also { file.writeText(json.encodeToString(it)) }
        }

        repoConfigJson = RepoConfig.encode(RepoConfig.load())
        if (repoConfigJson.length > STR_NETWORKING_LIMIT) {
            Chatsounds.logger.error("repo_config.json too big to network, falling back to defaults")
            repoConfigJson = RepoConfig.encode(RepoConfig.default)
        }
    }

    fun onPlayerJoin(player: ServerPlayer) {
        if (repoConfigJson.isEmpty()) loadConfig()
        if (canSendTo(player, ChatsoundsPayloads.RepoConfigPayload.TYPE)) {
            sendToPlayer(player, ChatsoundsPayloads.RepoConfigPayload(repoConfigJson))
        }
    }

    fun onPlayerLeave(player: ServerPlayer) {
        spam.forget(player.uuid)
        pending.forget(player.uuid)
    }

    /**
     * The saysound payload: the untruncated text of a chat message crossing on the same
     * connection. Payload and chat message reach the server thread in either order, so
     * whichever side completes the rendezvous relays; the chat message itself stays fully
     * vanilla (signed, moderated, broadcast, logged).
     */
    fun handleLongMessage(player: ServerPlayer, text: String) {
        if (text.length >= STR_NETWORKING_LIMIT) {
            Chatsounds.logger.warn("Message too long: {} chars by {}", text.length, player.gameProfile.name)
            return
        }
        val ready = pending.offerFull(player.uuid, text, System.nanoTime() / 1e9) ?: return
        Chatsounds.logger.info("Restored a trimmed chat message from {} to its full {} chars", player.gameProfile.name, ready.length)
        relay(player, ready)
    }

    /**
     * The /saysound command: no chat message exists for it, so it relays on its own. The
     * sender already played it locally (and unconditionally, whatever his prefix settings),
     * so he is left out here.
     */
    fun handleSaySound(player: ServerPlayer, text: String) {
        if (text.length >= STR_NETWORKING_LIMIT) {
            Chatsounds.logger.warn("Message too long: {} chars by {}", text.length, player.gameProfile.name)
            return
        }
        relay(player, text, includeSender = false)
    }

    fun handleMessage(player: ServerPlayer, text: String) {
        if (text.length >= STR_NETWORKING_LIMIT) {
            Chatsounds.logger.warn("Message too long: {} chars by {}", text.length, player.gameProfile.name)
            return
        }

        // null: bears the truncation signature, waiting for its payload (serverTick flushes).
        val ready = pending.offerChat(player.uuid, text, System.nanoTime() / 1e9) ?: return
        if (ready.length > text.length) {
            Chatsounds.logger.info("Restored a trimmed chat message from {} to its full {} chars", player.gameProfile.name, ready.length)
        }
        relay(player, ready)
    }

    /** Call every server tick: releases chat messages whose payload never arrived. */
    fun serverTick(server: net.minecraft.server.MinecraftServer) {
        for (entry in pending.flush(System.nanoTime() / 1e9)) {
            val player = server.playerList.getPlayer(entry.player) ?: continue
            relay(player, entry.text)
        }
    }

    private fun relay(player: ServerPlayer, text: String, includeSender: Boolean = true) {
        // GAMEMASTERS = the old permission level 2 (op).
        val exempt = config.exemptOps && player.permissions().hasPermission(
            net.minecraft.server.permissions.Permission.HasCommandLevel(net.minecraft.server.permissions.PermissionLevel.GAMEMASTERS)
        )
        if (spam.isSpam(player.uuid, text, System.nanoTime() / 1e9, exempt)) return

        val payload = ChatsoundsPayloads.RelayPayload(player.uuid, text)
        val radiusSq = config.radiusBlocks * config.radiusBlocks
        for (listener in player.level().server.playerList.players) {
            if (!includeSender && listener === player) continue
            if (listener.level().dimension() != player.level().dimension()) continue
            if (listener.distanceToSqr(player) > radiusSq) continue
            if (!canSendTo(listener, ChatsoundsPayloads.RelayPayload.TYPE)) continue
            sendToPlayer(listener, payload)
        }
    }
}
