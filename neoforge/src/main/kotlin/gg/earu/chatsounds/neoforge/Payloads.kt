package gg.earu.chatsounds.neoforge

import gg.earu.chatsounds.net.ChatsoundsPayloads
import gg.earu.chatsounds.server.ChatsoundsServer
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent

/**
 * NeoForge channel registration for the shared payloads. Client-side handling lives in
 * [ClientPayloadHandler], whose class must only load when a handler actually runs — never
 * on a dedicated server.
 */
object Payloads {
    fun register(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("1").optional()

        registrar.playToClient(ChatsoundsPayloads.RepoConfigPayload.TYPE, ChatsoundsPayloads.RepoConfigPayload.CODEC) { payload, context ->
            context.enqueueWork { ClientPayloadHandler.handleRepoConfig(payload) }
        }

        registrar.playToClient(ChatsoundsPayloads.RelayPayload.TYPE, ChatsoundsPayloads.RelayPayload.CODEC) { payload, context ->
            context.enqueueWork { ClientPayloadHandler.handleRelay(payload) }
        }

        registrar.playToServer(ChatsoundsPayloads.SaySoundPayload.TYPE, ChatsoundsPayloads.SaySoundPayload.CODEC) { payload, context ->
            val player = context.player() as? ServerPlayer ?: return@playToServer
            context.enqueueWork { ChatsoundsServer.handleLongMessage(player, payload.text) }
        }
    }
}
