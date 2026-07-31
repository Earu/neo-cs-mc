package gg.earu.chatsounds.neoforge

import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import gg.earu.chatsounds.ClientConfig
import gg.earu.chatsounds.client.CompletionOverlay
import gg.earu.chatsounds.client.IncomingChat
import gg.earu.chatsounds.data.Blacklist
import gg.earu.chatsounds.data.DataLoader
import gg.earu.chatsounds.net.ChatsoundsPayloads
import gg.earu.chatsounds.playback.ChatsoundsPlayer
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraftforge.client.event.ClientChatEvent
import net.minecraftforge.client.event.ClientChatReceivedEvent
import net.minecraftforge.client.event.RegisterClientCommandsEvent
import net.minecraftforge.client.event.ScreenEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.util.ObfuscationReflectionHelper
import org.lwjgl.glfw.GLFW

object ClientEvents {
    /**
     * ChatScreen#input via SRG-name reflection (f_95573_): 1.20.1 Forge mixins need SRG
     * refmaps, which is not worth the tooling for a single accessor. ORH remaps to the
     * runtime's names, so this works in mojmap dev and SRG production alike.
     */
    private fun chatInput(screen: ChatScreen): EditBox? = try {
        ObfuscationReflectionHelper.getPrivateValue<EditBox, ChatScreen>(ChatScreen::class.java, screen, "f_95573_")
    } catch (_: Throwable) {
        null
    }

    // ---- Incoming chat ----

    @SubscribeEvent(priority = EventPriority.LOW)
    fun onPlayerChat(event: ClientChatReceivedEvent.Player) {
        // signedContent is the raw typed message, before any server/client decoration.
        val result = IncomingChat.onPlayerChat(event.playerChatMessage.signedContent(), event.sender)
        if (result.hidden) event.isCanceled = true
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    fun onSystemChat(event: ClientChatReceivedEvent.System) {
        if (event.isOverlay) return
        val raw = ChatFormatting.stripFormatting(event.message.string) ?: return
        val result = IncomingChat.onSystemChat(raw) ?: return
        if (result.hidden) event.isCanceled = true
    }

    // ---- Outgoing chat: long sound keys ----

    /** Vanilla chat caps at 256 chars; some sound keys are far longer (GMod nets up to 60000). */
    private const val VANILLA_CHAT_LIMIT = 256
    private const val LONG_MESSAGE_LIMIT = 60_000

    @SubscribeEvent
    fun onScreenInit(event: ScreenEvent.Init.Post) {
        val screen = event.screen as? ChatScreen ?: return
        // Let long sound keys be typed/completed; the send path below handles transport.
        chatInput(screen)?.setMaxLength(LONG_MESSAGE_LIMIT)
    }

    @SubscribeEvent
    fun onOutgoingChat(event: ClientChatEvent) {
        val message = event.message
        if (message.length <= VANILLA_CHAT_LIMIT) return
        val connection = Minecraft.getInstance().connection ?: return
        if (Payloads.channel.isRemotePresent(connection.connection)) {
            // GMod saysound path: too long for vanilla chat, relay through the mod channel.
            event.isCanceled = true
            Payloads.channel.sendToServer(ChatsoundsPayloads.SaySoundPayload(message))
        } else {
            // Vanilla server: the protocol physically cannot carry it; fall back to the cap.
            event.message = message.take(VANILLA_CHAT_LIMIT)
        }
    }

    // ---- Tick ----

    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase == TickEvent.Phase.END) {
            ChatsoundsPlayer.clientTick()
        }
    }

    // ---- Completion UI ----

    @SubscribeEvent
    fun onScreenRender(event: ScreenEvent.Render.Post) {
        val screen = event.screen as? ChatScreen ?: return
        chatInput(screen)?.let { CompletionOverlay.pollInput(it.value) }
        CompletionOverlay.render(event.guiGraphics, screen.height)
    }

    @SubscribeEvent
    fun onScreenKeyPressed(event: ScreenEvent.KeyPressed.Pre) {
        val screen = event.screen as? ChatScreen ?: return
        if (event.keyCode != GLFW.GLFW_KEY_TAB) return

        val input = chatInput(screen) ?: return
        val window = Minecraft.getInstance().window.window
        val reverse = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS ||
            GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS ||
            GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS

        CompletionOverlay.onTab(input.value, reverse)?.let { replacement ->
            input.value = replacement
            event.isCanceled = true
        }
    }

    // ---- Commands ----

    @SubscribeEvent
    fun onRegisterClientCommands(event: RegisterClientCommandsEvent) {
        fun feedback(ctx: CommandContext<CommandSourceStack>, message: String) {
            ctx.source.sendSystemMessage(Component.literal("[chatsounds] $message"))
        }

        // No say/sh commands: typing triggers (and "sh") in chat IS the interface.
        event.dispatcher.register(
            Commands.literal("chatsounds")
                .then(Commands.literal("toggle").executes { ctx ->
                    ClientConfig.update { it.copy(enabled = !it.enabled) }
                    feedback(ctx, if (ClientConfig.data.enabled) "enabled" else "disabled")
                    1
                })
                .then(
                    Commands.literal("volume").then(
                        Commands.argument("volume", DoubleArgumentType.doubleArg(0.0, 4.0)).executes { ctx ->
                            ClientConfig.update { it.copy(volume = DoubleArgumentType.getDouble(ctx, "volume")) }
                            feedback(ctx, "volume set to ${ClientConfig.data.volume}")
                            1
                        }
                    )
                )
                .then(Commands.literal("hidetext").executes { ctx ->
                    ClientConfig.update { it.copy(hideText = !it.hideText) }
                    feedback(ctx, "hide-text ${if (ClientConfig.data.hideText) "on" else "off"}")
                    1
                })
                .then(Commands.literal("invertprefix").executes { ctx ->
                    ClientConfig.update { it.copy(invertPrefix = !it.invertPrefix) }
                    feedback(
                        ctx,
                        if (ClientConfig.data.invertPrefix) "inverted: only ';'-prefixed messages play chatsounds"
                        else "normal: ';' prefix blocks chatsounds",
                    )
                    1
                })
                .then(
                    Commands.literal("shmode").then(
                        Commands.argument("mode", IntegerArgumentType.integer(0, 2)).executes { ctx ->
                            ClientConfig.update { it.copy(shMode = IntegerArgumentType.getInteger(ctx, "mode")) }
                            feedback(ctx, "sh mode ${ClientConfig.data.shMode}")
                            1
                        }
                    )
                )
                .then(
                    Commands.literal("block").then(
                        Commands.argument("type", StringArgumentType.word()).then(
                            Commands.argument("args", StringArgumentType.greedyString()).executes { ctx ->
                                blockCommand(ctx, block = true)
                            }
                        )
                    )
                )
                .then(
                    Commands.literal("unblock").then(
                        Commands.argument("type", StringArgumentType.word()).then(
                            Commands.argument("args", StringArgumentType.greedyString()).executes { ctx ->
                                blockCommand(ctx, block = false)
                            }
                        )
                    )
                )
                .then(Commands.literal("reload").executes { DataLoader.recompileLists(full = false); 1 })
                .then(Commands.literal("reloadfull").executes { DataLoader.recompileLists(full = true); 1 })
                .then(Commands.literal("clearcache").executes { ctx ->
                    DataLoader.clearCache()
                    feedback(ctx, "cache cleared")
                    1
                })
        )
    }

    private fun blockCommand(ctx: CommandContext<CommandSourceStack>, block: Boolean): Int {
        val type = StringArgumentType.getString(ctx, "type")
        val args = StringArgumentType.getString(ctx, "args").split(" ")
        val error = Blacklist.update(block, type, args)
        val message = error ?: "${if (block) "blocked" else "unblocked"} $type ${args.joinToString(" ")}"
        ctx.source.sendSystemMessage(Component.literal("[chatsounds] $message"))
        return if (error == null) 1 else 0
    }
}
