package gg.earu.chatsounds.server

import gg.earu.chatsounds.Chatsounds
import gg.earu.chatsounds.data.RepoConfig
import gg.earu.chatsounds.net.ChatsoundsPayloads
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

    @Volatile private var config = ServerConfigData()
    @Volatile private var repoConfigJson: String = ""

    /** Wired by the loader module (SimpleChannel on Forge / ServerPlayNetworking on Fabric). */
    var sendToPlayer: (ServerPlayer, ChatsoundsPayloads.Message) -> Unit = { _, _ -> }
    var canSendTo: (ServerPlayer) -> Boolean = { _ -> false }

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
        if (canSendTo(player)) {
            sendToPlayer(player, ChatsoundsPayloads.RepoConfigPayload(repoConfigJson))
        }
    }

    fun onPlayerLeave(player: ServerPlayer) {
        spam.forget(player.uuid)
    }

    fun handleMessage(player: ServerPlayer, text: String) {
        if (text.length >= STR_NETWORKING_LIMIT) {
            Chatsounds.logger.warn("Message too long: {} chars by {}", text.length, player.gameProfile.name)
            return
        }

        val exempt = config.exemptOps && player.hasPermissions(2)
        if (spam.isSpam(player.uuid, text, System.nanoTime() / 1e9, exempt)) return

        val payload = ChatsoundsPayloads.RelayPayload(player.uuid, text)
        val radiusSq = config.radiusBlocks * config.radiusBlocks
        for (listener in player.server.playerList.players) {
            if (listener.level().dimension() != player.level().dimension()) continue
            if (listener.distanceToSqr(player) > radiusSq) continue
            if (!canSendTo(listener)) continue
            sendToPlayer(listener, payload)
        }
    }
}
