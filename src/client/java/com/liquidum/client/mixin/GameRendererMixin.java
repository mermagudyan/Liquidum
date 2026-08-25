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
	 * THE glass trigger point. The engine calls processBlurEffect()
	 * UNCONDITIONALLY inside GuiRenderer.draw(), exactly between the two
	 * executeDrawRange phases: background strata first (world backdrop,
	 * title-screen overlays — panorama itself renders directly before that),
	 * then this boundary, then widget strata. Running the chain here means
	 * glass composites over the finished background and under the widgets on
	 * every screen, in-game and menu alike.
	 *
	 * We cancel vanilla's own gaussian blur (its blurred backdrop quad is
	 * suppressed separately in ScreenMixin) and run the Liquidum chain instead.
	 */
	@Inject(method = "processBlurEffect()V", at = @At("HEAD"), cancellable = true)
	private void liquidum$glassInsteadOfVanillaBlur(CallbackInfo ci) {
		if (!LiquidGlassRenderer.isEnabled()) return;
		ci.cancel();
		LiquidGlassRenderer.applyOncePerFrame();
	}

	/** Frame boundary: re-arm the once-per-frame guard. */
	@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("TAIL"))
	private void liquidum$onFrameEnd(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
		LiquidGlassRenderer.resetFrame();
	}
}
