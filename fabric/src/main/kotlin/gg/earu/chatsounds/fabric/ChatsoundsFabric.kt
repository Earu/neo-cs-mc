package gg.earu.chatsounds.fabric

import gg.earu.chatsounds.Chatsounds
import gg.earu.chatsounds.net.ChatsoundsPayloads
import gg.earu.chatsounds.platform.Platform
import gg.earu.chatsounds.server.ChatsoundsServer
import net.fabricmc.api.EnvType
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import java.nio.file.Path

/** Channel ids + buf codecs for the 1.20.1 raw-channel networking (pre-payload-types API). */
object FabricChannels {
    val REPO_CONFIG = ResourceLocation(ChatsoundsPayloads.NAMESPACE, ChatsoundsPayloads.RepoConfigPayload.PATH)
    val RELAY = ResourceLocation(ChatsoundsPayloads.NAMESPACE, ChatsoundsPayloads.RelayPayload.PATH)
    val SAYSOUND = ResourceLocation(ChatsoundsPayloads.NAMESPACE, ChatsoundsPayloads.SaySoundPayload.PATH)

    const val MAX_STR = 65_536

    fun sendToPlayer(player: ServerPlayer, message: ChatsoundsPayloads.Message) {
        val buf = PacketByteBufs.create()
        val channel = when (message) {
            is ChatsoundsPayloads.RepoConfigPayload -> {
                buf.writeUtf(message.json, MAX_STR)
                REPO_CONFIG
            }
            is ChatsoundsPayloads.RelayPayload -> {
                buf.writeUUID(message.sender)
                buf.writeUtf(message.text, MAX_STR)
                RELAY
            }
            is ChatsoundsPayloads.SaySoundPayload -> {
                buf.writeUtf(message.text, MAX_STR)
                SAYSOUND
            }
        }
        ServerPlayNetworking.send(player, channel, buf)
    }
}

class ChatsoundsFabric : ModInitializer {
    class FabricPlatform : Platform {
        override val configDir: Path = FabricLoader.getInstance().configDir.resolve("chatsounds")
        override val isClient: Boolean = FabricLoader.getInstance().environmentType == EnvType.CLIENT
        override val modVersion: String = FabricLoader.getInstance()
            .getModContainer(Chatsounds.MOD_ID).map { it.metadata.version.friendlyString }.orElse("dev")
    }

    override fun onInitialize() {
        Chatsounds.init(FabricPlatform())

        ChatsoundsServer.sendToPlayer = FabricChannels::sendToPlayer
        ChatsoundsServer.canSendTo = { player -> ServerPlayNetworking.canSend(player, FabricChannels.RELAY) }

        ServerPlayNetworking.registerGlobalReceiver(FabricChannels.SAYSOUND) { server, player, _, buf, _ ->
            val text = buf.readUtf(FabricChannels.MAX_STR)
            server.execute { ChatsoundsServer.handleMessage(player, text) }
        }

        ServerPlayConnectionEvents.JOIN.register { handler, _, _ -> ChatsoundsServer.onPlayerJoin(handler.player) }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> ChatsoundsServer.onPlayerLeave(handler.player) }

        ServerMessageEvents.CHAT_MESSAGE.register { message, sender, _ ->
            ChatsoundsServer.handleMessage(sender, message.signedContent())
        }
    }
}
