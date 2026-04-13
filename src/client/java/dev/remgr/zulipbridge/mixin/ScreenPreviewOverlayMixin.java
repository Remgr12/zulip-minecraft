package dev.remgr.zulipbridge.mixin;

import dev.remgr.zulipbridge.image.PreviewHud;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Screen.class)
public class ScreenPreviewOverlayMixin {

    @Inject(method = "keyPressed(Lnet/minecraft/client/input/KeyInput;)Z", at = @At("HEAD"), cancellable = true)
    private void zulipBridge$blockKeysWhilePreviewOpen(KeyInput keyInput, CallbackInfoReturnable<Boolean> cir) {
        if (!PreviewHud.isActive()) return;
        cir.setReturnValue(true);
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;IIF)V", at = @At("TAIL"))
    private void zulipBridge$renderPreviewOverlay(DrawContext context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        PreviewHud.renderOverlay(context);
    }
}
