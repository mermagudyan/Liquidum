package com.liquidum.client.mixin;

import com.liquidum.client.shader.LiquidGlassRenderer;
import net.minecraft.client.gui.components.AbstractButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractButton.class)
public class AbstractButtonMixin {

	/**
	 * Skip vanilla's opaque button sprite so the Liquidum glass panel beneath
	 * becomes the button's visible body; contents (label) still extract above.
	 */
	@Inject(method = "extractDefaultSprite", at = @At("HEAD"), cancellable = true)
	private void liquidum$replaceBackgroundWithGlass(CallbackInfo ci) {
		if (!LiquidGlassRenderer.replaceVanillaButtonBackground()) return;
		ci.cancel();
	}
}
