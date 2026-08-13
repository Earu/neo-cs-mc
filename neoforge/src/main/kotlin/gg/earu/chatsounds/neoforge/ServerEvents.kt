package gg.earu.chatsounds.neoforge

import gg.earu.chatsounds.server.ChatsoundsServer
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.ServerChatEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.network.PacketDistributor

object ServerEvents {
    fun wire() {
        ChatsoundsServer.sendToPlayer = { player, payload -> PacketDistributor.sendToPlayer(player, payload) }
        ChatsoundsServer.canSendTo = { player, type -> player.connection.hasChannel(type) }
    }

    @SubscribeEvent
    fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        (event.entity as? ServerPlayer)?.let { ChatsoundsServer.onPlayerJoin(it) }
    }

    @SubscribeEvent
    fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        (event.entity as? ServerPlayer)?.let { ChatsoundsServer.onPlayerLeave(it) }
    }

    @SubscribeEvent
    fun onServerChat(event: ServerChatEvent) {
        ChatsoundsServer.handleMessage(event.player, event.rawText)
    }

    /** Flushes chat messages whose long-text payload never arrived. */
    @SubscribeEvent
    fun onServerTick(event: net.neoforged.neoforge.event.tick.ServerTickEvent.Post) {
        ChatsoundsServer.serverTick(event.server)
    }
}
