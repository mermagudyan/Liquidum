package com.liquidum.client.mixin;

import com.liquidum.client.shader.LiquidGlassRenderer;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderer.class)
public class GuiRendererMixin {

	/**
	 * PROVEN-WORKING point: start of GUI strata drawing. The chain reads main
	 * (clean world, pre-GUI) and writes glassout; the GUI layer then blits
	 * glassout at the strata point (ScreenMixin) - above the screen background,
	 * below the widgets.
	 */
	@Inject(method = "draw", at = @At("HEAD"))
	private void liquidum$applyGlassBeforeStrata(CallbackInfo ci) {
		LiquidGlassRenderer.applyOncePerFrame();
	}
}
