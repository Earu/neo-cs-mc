package gg.earu.chatsounds.fabric

import gg.earu.chatsounds.Chatsounds
import gg.earu.chatsounds.net.ChatsoundsPayloads
import gg.earu.chatsounds.platform.Platform
import gg.earu.chatsounds.server.ChatsoundsServer
import net.fabricmc.api.EnvType
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Path

class ChatsoundsFabric : ModInitializer {
    class FabricPlatform : Platform {
        override val configDir: Path = FabricLoader.getInstance().configDir.resolve("chatsounds")
        override val isClient: Boolean = FabricLoader.getInstance().environmentType == EnvType.CLIENT
        override val modVersion: String = FabricLoader.getInstance()
            .getModContainer(Chatsounds.MOD_ID).map { it.metadata.version.friendlyString }.orElse("dev")
    }

    override fun onInitialize() {
        Chatsounds.init(FabricPlatform())

        PayloadTypeRegistry.playS2C().register(ChatsoundsPayloads.RepoConfigPayload.TYPE, ChatsoundsPayloads.RepoConfigPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(ChatsoundsPayloads.RelayPayload.TYPE, ChatsoundsPayloads.RelayPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(ChatsoundsPayloads.SaySoundPayload.TYPE, ChatsoundsPayloads.SaySoundPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(ChatsoundsPayloads.SaySoundCmdPayload.TYPE, ChatsoundsPayloads.SaySoundCmdPayload.CODEC)

        ChatsoundsServer.sendToPlayer = { player, payload -> ServerPlayNetworking.send(player, payload) }
        ChatsoundsServer.canSendTo = { player, type -> ServerPlayNetworking.canSend(player, type) }

        ServerPlayNetworking.registerGlobalReceiver(ChatsoundsPayloads.SaySoundPayload.TYPE) { payload, context ->
            context.server().execute { ChatsoundsServer.handleLongMessage(context.player(), payload.text) }
        }

        ServerPlayNetworking.registerGlobalReceiver(ChatsoundsPayloads.SaySoundCmdPayload.TYPE) { payload, context ->
            context.server().execute { ChatsoundsServer.handleSaySound(context.player(), payload.text) }
        }

        ServerPlayConnectionEvents.JOIN.register { handler, _, _ -> ChatsoundsServer.onPlayerJoin(handler.player) }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> ChatsoundsServer.onPlayerLeave(handler.player) }

        ServerMessageEvents.CHAT_MESSAGE.register { message, sender, _ ->
            ChatsoundsServer.handleMessage(sender, message.signedContent())
        }

        // Flushes chat messages whose long-text payload never arrived.
        ServerTickEvents.END_SERVER_TICK.register { server -> ChatsoundsServer.serverTick(server) }
    }
}
