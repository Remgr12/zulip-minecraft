package dev.remgr.zulipbridge.mixin;

import dev.remgr.zulipbridge.image.PreviewHud;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public class MousePreviewScrollMixin {

    @Inject(method = "onMouseScroll(JDD)V", at = @At("HEAD"), cancellable = true)
    private void zulipBridge$hudPreviewScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        // Only handle here when no screen is open — screens are covered by per-screen handlers
        if (client == null || client.currentScreen != null) return;
        if (PreviewHud.handleScroll(0, 0, vertical)) {
            ci.cancel();
        }
    }

}
