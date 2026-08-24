package com.liquidum.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractSliderButton.class)
public class AbstractSliderButtonMixin {

	/**
	 * Skip the slider track sprite (first blit in extractWidgetRenderState) so
	 * the Liquidum glass tile becomes the track. The handle (second blit) stays
	 * — it is the draggable thumb.
	 */
	@WrapOperation(
		method = "extractWidgetRenderState",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V",
			ordinal = 0
		)
	)
	private void liquidum$skipTrackSprite(GuiGraphicsExtractor instance, RenderPipeline pipeline, Identifier sprite,
	                                      int x, int y, int width, int height, int color,
	                                      Operation<Void> original) {
		// no-op: glass tile replaces the track
	}
}
