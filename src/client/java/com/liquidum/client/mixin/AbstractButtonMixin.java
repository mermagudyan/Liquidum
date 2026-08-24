package com.liquidum.client.mixin;

import com.liquidum.client.debug.LiquidumDebugScreen;
import com.liquidum.client.shader.LiquidGlassRenderer;
import net.minecraft.client.Minecraft;
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
	 * The Lab (F7) screen's own buttons are excluded so the debug UI stays usable.
	 */
	@Inject(method = "extractDefaultSprite", at = @At("HEAD"), cancellable = true)
	private void liquidum$replaceBackgroundWithGlass(CallbackInfo ci) {
		if (!LiquidGlassRenderer.replaceVanillaButtonBackground()) return;
		if (Minecraft.getInstance().gui.screen() instanceof LiquidumDebugScreen) return;
		ci.cancel();
	}
}
