package com.liquidum.client.mixin;

import com.liquidum.client.shader.LiquidGlassRenderer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stage 4 "UI as glass": container panel textures are CANCELLED and replaced
 * by our frosted glass panel (uPanel channel).
 *
 * One generic hook instead of a mixin per screen class: every vanilla
 * container panel texture lives under textures/gui/container/, so filtering
 * blits by path prefix (while a container screen is open) covers chests,
 * furnaces, hoppers, the player inventory, creative tabs, most modded
 * containers. Non-matching textures pass through untouched (graceful
 * degradation). The panel rect is taken from the blit arguments themselves.
 */
@Mixin(GuiGraphicsExtractor.class)
public class GuiGraphicsExtractorMixin {

	@Inject(
		method = "blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void liquidum$filterContainerPanel(RenderPipeline pipeline, Identifier texture, int x, int y,
	                                            float u, float v, int width, int height,
	                                            int texW, int texH, CallbackInfo ci) {
		if (!LiquidGlassRenderer.filterContainerPanel(texture)) return;
		LiquidGlassRenderer.submitPanelRect(x, y, width, height);
		ci.cancel();
	}

	/**
	 * Creative mode tabs (blitSprite overload): tab sprites are cancelled and
	 * replaced by glass tiles at the same rect, matching the slot tiles.
	 */
	@Inject(
		method = "blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void liquidum$filterCreativeTabs(RenderPipeline pipeline, Identifier sprite,
	                                         int x, int y, int width, int height, CallbackInfo ci) {
		if (!LiquidGlassRenderer.filterCreativeTab(sprite)) return;
		LiquidGlassRenderer.submitTabTile(x, y, width, height);
		ci.cancel();
	}
}
