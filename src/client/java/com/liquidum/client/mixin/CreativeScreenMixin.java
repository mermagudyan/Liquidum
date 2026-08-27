package com.liquidum.client.mixin;

import com.liquidum.client.creative.TabHistory;
import com.liquidum.client.shader.LiquidGlassRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.item.CreativeModeTab;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Creative screen: Tab Stack trigger + SMOOTH SCROLL (smoothscroll-style).
 *
 * The wheel accumulates a fractional TARGET instead of jumping scrollOffs;
 * the per-frame animator glides scrollOffs toward it — items slide row-by-row
 * through the grid via vanilla's own float-based scrollTo, so tiles and items
 * always stay in sync without pixel-offset hacks.
 */
@Mixin(CreativeModeInventoryScreen.class)
public class CreativeScreenMixin {

	@Shadow
	private float scrollOffs;

	@Shadow
	private boolean scrolling;

	@Shadow
	private boolean canScroll() {
		throw new AssertionError();
	}

	private float liquidum$scrollTarget = Float.NaN;

	private net.minecraft.world.inventory.AbstractContainerMenu liquidum$menu() {
		return ((AbstractContainerScreenAccessor) (Object) this).liquidum$getMenu();
	}

	/**
	 * SMOOTH SCROLL: accumulate the wheel into a fractional target instead of
	 * letting vanilla jump scrollOffs by a whole notch.
	 */
	@Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
	private void liquidum$smoothScroll(double mouseX, double mouseY, double xAmount, double yAmount,
	                                   CallbackInfoReturnable<Boolean> cir) {
		if (!LiquidGlassRenderer.replaceSlotTiles()) return;
		if (!canScroll()) return;
		if (Float.isNaN(liquidum$scrollTarget)) liquidum$scrollTarget = scrollOffs;
		liquidum$scrollTarget = ((ItemPickerMenuAccessor) liquidum$menu())
			.liquidum$subtractInputFromScroll(liquidum$scrollTarget, yAmount);
		cir.setReturnValue(true);
	}

	/**
	 * Per-frame animator (extractRenderState runs every frame before the grid
	 * extracts): glide scrollOffs toward the target, then vanilla's own
	 * scrollTo positions the items smoothly.
	 */
	@Inject(method = "extractRenderState", at = @At("HEAD"))
	private void liquidum$animateScroll(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY,
	                                    float partialTick, CallbackInfo ci) {
		if (Float.isNaN(liquidum$scrollTarget)) {
			liquidum$scrollTarget = scrollOffs;
			return;
		}
		// Scrollbar DRAG: vanilla writes scrollOffs directly — follow it 1:1,
		// no animation (animating against the drag = tug-of-war jerkiness).
		if (scrolling) {
			liquidum$scrollTarget = scrollOffs;
			return;
		}
		// External jump (tab switch / search reset): glide from the new position.
		if (Math.abs(scrollOffs - liquidum$scrollTarget) > 0.5f) {
			liquidum$scrollTarget = scrollOffs;
		}
		float dt = 1.0f / 60.0f;
		float k = 1f - (float) Math.exp(-8.0 * dt);
		scrollOffs += (liquidum$scrollTarget - scrollOffs) * k;
		if (Math.abs(liquidum$scrollTarget - scrollOffs) < 0.0005f) scrollOffs = liquidum$scrollTarget;
		((CreativeModeInventoryScreen.ItemPickerMenu) liquidum$menu()).scrollTo(scrollOffs);
	}

	@Inject(method = "selectTab", at = @At("HEAD"))
	private void liquidum$onSelectTab(CreativeModeTab tab, CallbackInfo ci) {
		TabHistory.Transition t = TabHistory.transitionTo(tab);
		if (t != TabHistory.Transition.NONE && LiquidGlassRenderer.isTabTransitionEnabled()) {
			LiquidGlassRenderer.startTabTransition();
		}
	}
}
