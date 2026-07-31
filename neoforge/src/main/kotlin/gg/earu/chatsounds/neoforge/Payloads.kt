package gg.earu.chatsounds.neoforge

import gg.earu.chatsounds.net.ChatsoundsPayloads
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.network.NetworkDirection
import net.minecraftforge.network.NetworkEvent
import net.minecraftforge.network.NetworkRegistry
import net.minecraftforge.network.PacketDistributor
import java.util.function.Supplier

/**
 * Forge 1.20.1 SimpleChannel wiring for the shared messages. The channel is OPTIONAL
 * (acceptMissingOr) so vanilla clients/servers interoperate untouched.
 */
object Payloads {
    private const val PROTOCOL = "1"

    val channel = NetworkRegistry.ChannelBuilder
        .named(ResourceLocation(ChatsoundsPayloads.NAMESPACE, "main"))
        .networkProtocolVersion { PROTOCOL }
        .clientAcceptedVersions(NetworkRegistry.acceptMissingOr(PROTOCOL))
        .serverAcceptedVersions(NetworkRegistry.acceptMissingOr(PROTOCOL))
        .simpleChannel()

    fun register() {
        channel.registerMessage(
            0,
            ChatsoundsPayloads.RepoConfigPayload::class.java,
            { msg, buf -> buf.writeUtf(msg.json, 65_536) },
            { buf -> ChatsoundsPayloads.RepoConfigPayload(buf.readUtf(65_536)) },
            ::handleRepoConfig,
        )
        channel.registerMessage(
            1,
            ChatsoundsPayloads.RelayPayload::class.java,
            { msg, buf -> buf.writeUUID(msg.sender); buf.writeUtf(msg.text, 65_536) },
            { buf -> ChatsoundsPayloads.RelayPayload(buf.readUUID(), buf.readUtf(65_536)) },
            ::handleRelay,
        )
        channel.registerMessage(
            2,
            ChatsoundsPayloads.SaySoundPayload::class.java,
            { msg, buf -> buf.writeUtf(msg.text, 65_536) },
            { buf -> ChatsoundsPayloads.SaySoundPayload(buf.readUtf(65_536)) },
            ::handleSaySound,
        )
    }

    fun sendToPlayer(player: ServerPlayer, message: ChatsoundsPayloads.Message) {
        channel.send(PacketDistributor.PLAYER.with { player }, message)
    }

    fun canSendTo(player: ServerPlayer): Boolean =
        channel.isRemotePresent(player.connection.connection)

    private fun handleRepoConfig(msg: ChatsoundsPayloads.RepoConfigPayload, ctx: Supplier<NetworkEvent.Context>) {
        ctx.get().enqueueWork { ClientPayloadHandler.handleRepoConfig(msg) }
        ctx.get().packetHandled = true
    }

    private fun handleRelay(msg: ChatsoundsPayloads.RelayPayload, ctx: Supplier<NetworkEvent.Context>) {
        ctx.get().enqueueWork { ClientPayloadHandler.handleRelay(msg) }
        ctx.get().packetHandled = true
    }

    private fun handleSaySound(msg: ChatsoundsPayloads.SaySoundPayload, ctx: Supplier<NetworkEvent.Context>) {
        val player = ctx.get().sender
        if (player != null) {
            ctx.get().enqueueWork { gg.earu.chatsounds.server.ChatsoundsServer.handleMessage(player, msg.text) }
        }
        ctx.get().packetHandled = true
    }
}
