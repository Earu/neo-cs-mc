package gg.earu.chatsounds.mixin;

import gg.earu.chatsounds.client.OutgoingChat;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sees the message before normalizeChatMessage trims it to 256, so the full text can ride
 * ahead on the mod channel. The vanilla send is never cancelled: the chat message goes out
 * signed and truncated as usual, and the server pairs the two up.
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {
    @Inject(method = "handleChatInput", at = @At("HEAD"))
    private void chatsounds$shipFullText(String text, boolean addToHistory, CallbackInfo ci) {
        OutgoingChat.INSTANCE.beforeChatSend(text);
    }
}
