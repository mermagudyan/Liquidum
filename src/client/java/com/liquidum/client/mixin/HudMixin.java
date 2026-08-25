package com.liquidum.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.liquidum.client.shader.LiquidGlassRenderer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * HUD glass (roadmap Stage 4, hotbar first).
 *
 * extractRenderState HEAD requests the blur-stratum marker (when no other
 * extractor did): ALL HUD elements then land in the after-blur phase, i.e.
 * ABOVE the glass composite — the world behind stays clean for refraction and
 * icons stay crisp per the roadmap rule.
 *
 * The hotbar's opaque background sprite is skipped so the glass panel becomes
 * the bar body; item icons and the selection highlight still draw above it.
 */
@Mixin(Hud.class)
public class HudMixin {

	@Inject(method = "extractRenderState", at = @At("HEAD"))
	private void liquidum$hudBlurMarker(GuiGraphicsExtractor guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (!LiquidGlassRenderer.isEnabled()) return;
		// When a screen is open the SCREEN must own the marker (its background
		// has to land in the before-blur phase, under the glass). If the HUD
		// claimed it first, the container panel would be drawn after the
		// boundary — over our glass tiles (inventory looked fully vanilla).
		if (net.minecraft.client.Minecraft.getInstance().gui.screen() != null) return;
		if (!LiquidGlassRenderer.isBlurMarkerSeen()) {
			guiGraphics.blurBeforeThisStratum();
			LiquidGlassRenderer.setBlurMarkerSeen();
		}
	}

	@Inject(method = "extractItemHotbar", at = @At("TAIL"))
	private void liquidum$submitHotbarTile(GuiGraphicsExtractor guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		var player = net.minecraft.client.Minecraft.getInstance().player;
		int sel = player != null ? player.getInventory().getSelectedSlot() : -1;
		boolean offhand = player != null && !player.getOffhandItem().isEmpty();
		LiquidGlassRenderer.submitHotbar(guiGraphics.guiWidth(), guiGraphics.guiHeight(), sel, offhand);
	}

	/**
	 * Skip vanilla hotbar sprites so glass replaces them:
	 * ordinal 0 = bar background, 1 = selection frame (our animated ring
	 * replaces it), 2/3 = offhand boxes (our own tile replaces them).
	 */
	@WrapOperation(
		method = "extractItemHotbar",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V",
			ordinal = 0
		)
	)
	private void liquidum$skipHotbarSprite(GuiGraphicsExtractor instance, RenderPipeline pipeline, Identifier sprite,
	                                       int x, int y, int width, int height, Operation<Void> original) {
		if (!LiquidGlassRenderer.replaceHotbarBackground()) {
			original.call(instance, pipeline, sprite, x, y, width, height);
		}
	}

	@WrapOperation(
		method = "extractItemHotbar",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V",
			ordinal = 1
		)
	)
	private void liquidum$skipSelectionSprite(GuiGraphicsExtractor instance, RenderPipeline pipeline, Identifier sprite,
	                                          int x, int y, int width, int height, Operation<Void> original) {
		if (!LiquidGlassRenderer.replaceHotbarBackground()) {
			original.call(instance, pipeline, sprite, x, y, width, height);
		}
	}

	@WrapOperation(
		method = "extractItemHotbar",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V",
			ordinal = 2
		)
	)
	private void liquidum$skipOffhandLeftSprite(GuiGraphicsExtractor instance, RenderPipeline pipeline, Identifier sprite,
	                                            int x, int y, int width, int height, Operation<Void> original) {
		if (!LiquidGlassRenderer.replaceHotbarBackground()) {
			original.call(instance, pipeline, sprite, x, y, width, height);
		}
	}

	@WrapOperation(
		method = "extractItemHotbar",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V",
			ordinal = 3
		)
	)
	private void liquidum$skipOffhandRightSprite(GuiGraphicsExtractor instance, RenderPipeline pipeline, Identifier sprite,
	                                             int x, int y, int width, int height, Operation<Void> original) {
		if (!LiquidGlassRenderer.replaceHotbarBackground()) {
			original.call(instance, pipeline, sprite, x, y, width, height);
		}
	}
}
