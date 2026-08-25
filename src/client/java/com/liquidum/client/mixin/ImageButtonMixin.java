package com.liquidum.client.mixin;

import com.liquidum.client.shader.LiquidGlassRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ImageButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ImageButton (recipe book toggle etc.) draws its sprite directly in
 * extractContents via blitSprite — bypassing extractDefaultSprite, so
 * AbstractButtonMixin never sees it. Cancel the whole method when glass
 * replaces button backgrounds: the method is nothing but the sprite blit,
 * and a glass tile is already submitted for the button by ScreenMixin.
 */
@Mixin(ImageButton.class)
public class ImageButtonMixin {

	@Inject(method = "extractContents", at = @At("HEAD"), cancellable = true)
	private void liquidum$skipSprite(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY,
	                                 float partialTick, CallbackInfo ci) {
		if (!LiquidGlassRenderer.replaceVanillaButtonBackground()) return;
		ci.cancel();
	}
}
