package com.liquidum.client.mixin;

import com.liquidum.client.shader.LiquidGlassRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

	/**
	 * REPLACE vanilla's menu blur with the Liquidum glass chain.
	 * processBlurEffect is invoked at the stratum boundary inside
	 * GuiRenderer.draw: main holds the world + screen background, widgets
	 * are not drawn yet. Works on every screen (title has no level, so
	 * level-based hooks never fire there).
	 */
	@Inject(method = "processBlurEffect()V", at = @At("HEAD"), cancellable = true)
	private void liquidum$glassInsteadOfVanillaBlur(CallbackInfo ci) {
		ci.cancel(); // no vanilla fullscreen blur
		LiquidGlassRenderer.applyOncePerFrame();
	}

	/** Frame boundary: re-arm the once-per-frame guard. */
	@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("TAIL"))
	private void liquidum$onFrameEnd(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
		LiquidGlassRenderer.resetFrame();
	}
}
