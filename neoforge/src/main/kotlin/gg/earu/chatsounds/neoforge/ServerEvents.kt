package gg.earu.chatsounds.neoforge

import gg.earu.chatsounds.server.ChatsoundsServer
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.event.ServerChatEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

object ServerEvents {
    fun wire() {
        ChatsoundsServer.sendToPlayer = Payloads::sendToPlayer
        ChatsoundsServer.canSendTo = Payloads::canSendTo
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
    fun onServerTick(event: net.minecraftforge.event.TickEvent.ServerTickEvent) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return
        net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer()?.let { ChatsoundsServer.serverTick(it) }
    }
}
