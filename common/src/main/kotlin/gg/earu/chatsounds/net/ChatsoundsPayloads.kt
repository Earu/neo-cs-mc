package gg.earu.chatsounds.net

import gg.earu.chatsounds.Chatsounds
import io.netty.buffer.ByteBuf
import net.minecraft.core.UUIDUtil
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import java.util.UUID

/**
 * Wire format shared by every loader. All channels are OPTIONAL: vanilla clients/servers
 * interoperate untouched. Only text crosses the wire, never audio.
 */
object ChatsoundsPayloads {
    /** S->C: the server's repo_config.json — the client rebuilds its lists from it. */
    class RepoConfigPayload(val json: String) : CustomPacketPayload {
        companion object {
            val TYPE = CustomPacketPayload.Type<RepoConfigPayload>(Identifier.fromNamespaceAndPath(Chatsounds.MOD_ID, "repo_config"))
            val CODEC: StreamCodec<ByteBuf, RepoConfigPayload> =
                ByteBufCodecs.STRING_UTF8.map(::RepoConfigPayload) { it.json }
        }

        override fun type() = TYPE
    }

    /** S->C: a chat message to sound out, positioned at the sender. */
    class RelayPayload(val sender: UUID, val text: String) : CustomPacketPayload {
        companion object {
            val TYPE = CustomPacketPayload.Type<RelayPayload>(Identifier.fromNamespaceAndPath(Chatsounds.MOD_ID, "relay"))
            val CODEC: StreamCodec<ByteBuf, RelayPayload> = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, RelayPayload::sender,
                ByteBufCodecs.STRING_UTF8, RelayPayload::text,
                ::RelayPayload,
            )
        }

        override fun type() = TYPE
    }

    /** C->S: the untruncated text of a chat message, for the long-message rendezvous. */
    class SaySoundPayload(val text: String) : CustomPacketPayload {
        companion object {
            val TYPE = CustomPacketPayload.Type<SaySoundPayload>(Identifier.fromNamespaceAndPath(Chatsounds.MOD_ID, "saysound"))
            val CODEC: StreamCodec<ByteBuf, SaySoundPayload> =
                ByteBufCodecs.STRING_UTF8.map(::SaySoundPayload) { it.text }
        }

        override fun type() = TYPE
    }

    /**
     * C->S: the /saysound command. Its own channel (not [SaySoundPayload], which only ever
     * completes a chat rendezvous) so that its absence also tells the client the server is
     * vanilla or too old, and playback stays local.
     */
    class SaySoundCmdPayload(val text: String) : CustomPacketPayload {
        companion object {
            val TYPE = CustomPacketPayload.Type<SaySoundCmdPayload>(Identifier.fromNamespaceAndPath(Chatsounds.MOD_ID, "saysound_cmd"))
            val CODEC: StreamCodec<ByteBuf, SaySoundCmdPayload> =
                ByteBufCodecs.STRING_UTF8.map(::SaySoundCmdPayload) { it.text }
        }

        override fun type() = TYPE
    }
}
