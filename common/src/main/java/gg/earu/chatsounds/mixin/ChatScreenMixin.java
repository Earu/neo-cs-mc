package gg.earu.chatsounds.mixin;

import gg.earu.chatsounds.client.OutgoingChat;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Catches long messages before normalizeChatMessage trims them to 256. Both loaders' send
 * hooks sit further down in ClientPacketListener#sendChat, where the text has already lost
 * everything past the vanilla cap.
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {
    @Inject(method = "handleChatInput", at = @At("HEAD"), cancellable = true)
    private void chatsounds$routeLongMessage(String text, boolean addToHistory, CallbackInfo ci) {
        if (OutgoingChat.INSTANCE.intercept(text, addToHistory)) {
            ci.cancel();
        }
    }
}
