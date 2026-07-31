package gg.earu.chatsounds.fabric

import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import gg.earu.chatsounds.Chatsounds
import gg.earu.chatsounds.ClientConfig
import gg.earu.chatsounds.audio.AudioEngine
import gg.earu.chatsounds.client.CompletionOverlay
import gg.earu.chatsounds.client.IncomingChat
import gg.earu.chatsounds.data.Blacklist
import gg.earu.chatsounds.data.DataLoader
import gg.earu.chatsounds.data.RepoConfig
import gg.earu.chatsounds.mixin.ChatScreenAccessor
import gg.earu.chatsounds.net.ChatsoundsPayloads
import gg.earu.chatsounds.playback.ChatsoundsPlayer
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

class ChatsoundsFabricClient : ClientModInitializer {
    private companion object {
        const val VANILLA_CHAT_LIMIT = 256
        const val LONG_MESSAGE_LIMIT = 60_000
    }

    override fun onInitializeClient() {
        ClientConfig.load()
        Blacklist.load()
        DataLoader.startup()

        registerPayloadReceivers()
        registerChatEvents()
        registerScreenEvents()
        registerCommands()

        ClientTickEvents.END_CLIENT_TICK.register { ChatsoundsPlayer.clientTick() }
    }

    private fun registerPayloadReceivers() {
        ClientPlayNetworking.registerGlobalReceiver(FabricChannels.REPO_CONFIG) { client, _, buf, _ ->
            val json = buf.readUtf(FabricChannels.MAX_STR)
            client.execute {
                Chatsounds.logger.info("Received server repo config!")
                IncomingChat.serverAuthoritative = true
                try {
                    DataLoader.repoConfig = RepoConfig.parse(json)
                    DataLoader.compileLists()
                } catch (e: Exception) {
                    Chatsounds.logger.error("Invalid server repo config: {}", e.message)
                }
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(FabricChannels.RELAY) { client, _, buf, _ ->
            val sender = buf.readUUID()
            val text = buf.readUtf(FabricChannels.MAX_STR)
            client.execute { IncomingChat.onRelay(sender, text) }
        }
    }

    private fun registerChatEvents() {
        // Vanilla chat caps at 256 chars; some sound keys are far longer. Route long
        // messages through the mod channel when the server has it (GMod saysound path),
        // otherwise fall back to the vanilla cap.
        ClientSendMessageEvents.ALLOW_CHAT.register { message ->
            if (message.length > VANILLA_CHAT_LIMIT && ClientPlayNetworking.canSend(FabricChannels.SAYSOUND)) {
                val buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create()
                buf.writeUtf(message, FabricChannels.MAX_STR)
                ClientPlayNetworking.send(FabricChannels.SAYSOUND, buf)
                false
            } else {
                true
            }
        }
        ClientSendMessageEvents.MODIFY_CHAT.register { message ->
            if (message.length > VANILLA_CHAT_LIMIT) message.take(VANILLA_CHAT_LIMIT) else message
        }

        ClientReceiveMessageEvents.ALLOW_CHAT.register { message, signedMessage, sender, _, _ ->
            val text = signedMessage?.signedContent() ?: message.string
            val senderId = sender?.id
            if (senderId == null) {
                true
            } else {
                !IncomingChat.onPlayerChat(text, senderId).hidden
            }
        }

        ClientReceiveMessageEvents.ALLOW_GAME.register { message, overlay ->
            if (overlay) {
                true
            } else {
                val raw = ChatFormatting.stripFormatting(message.string)
                val result = raw?.let { IncomingChat.onSystemChat(it) }
                result == null || !result.hidden
            }
        }
    }

    private fun registerScreenEvents() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is ChatScreen) return@register

            // Let long sound keys be typed/completed; the send path handles transport.
            (screen as ChatScreenAccessor).`chatsounds$getInput`().setMaxLength(LONG_MESSAGE_LIMIT)

            ScreenEvents.afterRender(screen).register { s, graphics, _, _, _ ->
                val chat = s as ChatScreen
                CompletionOverlay.pollInput((chat as ChatScreenAccessor).`chatsounds$getInput`().value)
                CompletionOverlay.render(graphics, chat.height)
            }

            ScreenKeyboardEvents.allowKeyPress(screen).register { s, key, _, modifiers ->
                if (key != GLFW.GLFW_KEY_TAB) return@register true
                val input = (s as ChatScreenAccessor).`chatsounds$getInput`()
                val reverse = (modifiers and GLFW.GLFW_MOD_SHIFT) != 0 || (modifiers and GLFW.GLFW_MOD_CONTROL) != 0
                val replacement = CompletionOverlay.onTab(input.value, reverse)
                if (replacement != null) {
                    input.value = replacement
                    false
                } else {
                    true
                }
            }
        }
    }

    private fun registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            fun feedback(source: FabricClientCommandSource, message: String) {
                source.sendFeedback(Component.literal("[chatsounds] $message"))
            }

            // No say/sh commands: typing triggers (and "sh") in chat IS the interface.
            dispatcher.register(
                ClientCommandManager.literal("chatsounds")
                    .then(ClientCommandManager.literal("toggle").executes { ctx ->
                        ClientConfig.update { it.copy(enabled = !it.enabled) }
                        feedback(ctx.source, if (ClientConfig.data.enabled) "enabled" else "disabled")
                        1
                    })
                    .then(
                        ClientCommandManager.literal("volume").then(
                            ClientCommandManager.argument("volume", DoubleArgumentType.doubleArg(0.0, 4.0)).executes { ctx ->
                                ClientConfig.update { it.copy(volume = DoubleArgumentType.getDouble(ctx, "volume")) }
                                feedback(ctx.source, "volume set to ${ClientConfig.data.volume}")
                                1
                            }
                        )
                    )
                    .then(ClientCommandManager.literal("hidetext").executes { ctx ->
                        ClientConfig.update { it.copy(hideText = !it.hideText) }
                        feedback(ctx.source, "hide-text ${if (ClientConfig.data.hideText) "on" else "off"}")
                        1
                    })
                    .then(ClientCommandManager.literal("invertprefix").executes { ctx ->
                        ClientConfig.update { it.copy(invertPrefix = !it.invertPrefix) }
                        feedback(
                            ctx.source,
                            if (ClientConfig.data.invertPrefix) "inverted: only ';'-prefixed messages play chatsounds"
                            else "normal: ';' prefix blocks chatsounds",
                        )
                        1
                    })
                    .then(
                        ClientCommandManager.literal("shmode").then(
                            ClientCommandManager.argument("mode", IntegerArgumentType.integer(0, 2)).executes { ctx ->
                                ClientConfig.update { it.copy(shMode = IntegerArgumentType.getInteger(ctx, "mode")) }
                                feedback(ctx.source, "sh mode ${ClientConfig.data.shMode}")
                                1
                            }
                        )
                    )
                    .then(
                        ClientCommandManager.literal("block").then(
                            ClientCommandManager.argument("type", StringArgumentType.word()).then(
                                ClientCommandManager.argument("args", StringArgumentType.greedyString()).executes { ctx ->
                                    blockCommand(ctx.source, StringArgumentType.getString(ctx, "type"), StringArgumentType.getString(ctx, "args"), block = true)
                                }
                            )
                        )
                    )
                    .then(
                        ClientCommandManager.literal("unblock").then(
                            ClientCommandManager.argument("type", StringArgumentType.word()).then(
                                ClientCommandManager.argument("args", StringArgumentType.greedyString()).executes { ctx ->
                                    blockCommand(ctx.source, StringArgumentType.getString(ctx, "type"), StringArgumentType.getString(ctx, "args"), block = false)
                                }
                            )
                        )
                    )
                    .then(ClientCommandManager.literal("reload").executes { DataLoader.recompileLists(full = false); 1 })
                    .then(ClientCommandManager.literal("reloadfull").executes { DataLoader.recompileLists(full = true); 1 })
                    .then(ClientCommandManager.literal("clearcache").executes { ctx ->
                        DataLoader.clearCache()
                        feedback(ctx.source, "cache cleared")
                        1
                    })
            )
        }
    }

    private fun blockCommand(source: FabricClientCommandSource, type: String, argsRaw: String, block: Boolean): Int {
        val args = argsRaw.split(" ")
        val error = Blacklist.update(block, type, args)
        val message = error ?: "${if (block) "blocked" else "unblocked"} $type ${args.joinToString(" ")}"
        source.sendFeedback(Component.literal("[chatsounds] $message"))
        return if (error == null) 1 else 0
    }
}
