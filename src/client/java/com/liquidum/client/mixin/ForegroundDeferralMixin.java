package com.liquidum.client.mixin;

import com.liquidum.client.shader.LiquidGlassRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.object.banner.BannerFlagModel;
import net.minecraft.client.model.object.book.BookModel;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import org.joml.Quaternionfc;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * P0: vanilla извлекает динамические модели GUI (игрок в инвентаре,
 * анимированная книга зачарований, флаг в ткацком станке) из
 * extractBackground — это ДО-blur фаза, поэтому они попадают В ВИД стекла
 * и превращаются в размытые пятна. Здесь вызов отменяется и запоминается;
 * ScreenMixin переигрывает его после glass composite (widget-фаза) —
 * модель рендерится ПОВЕРХ стекла, абсолютно резкой (L4 FOREGROUND).
 */
@Mixin(GuiGraphicsExtractor.class)
public class ForegroundDeferralMixin {

	@Inject(
		method = "entity(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;FLorg/joml/Vector3fc;Lorg/joml/Quaternionfc;Lorg/joml/Quaternionfc;IIII)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void liquidum$deferEntity(EntityRenderState state, float scale, Vector3fc pivot,
	                                  Quaternionfc rot, Quaternionfc anim,
	                                  int x, int y, int w, int h, CallbackInfo ci) {
		if (!LiquidGlassRenderer.deferForeground()) return;
		LiquidGlassRenderer.captureEntity(state, scale, pivot, rot, anim, x, y, w, h);
		ci.cancel();
	}

	@Inject(
		method = "book(Lnet/minecraft/client/model/object/book/BookModel;Lnet/minecraft/resources/Identifier;FFFIIII)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void liquidum$deferBook(BookModel model, Identifier texture,
	                                float f1, float f2, float f3,
	                                int x, int y, int w, int h, CallbackInfo ci) {
		if (!LiquidGlassRenderer.deferForeground()) return;
		LiquidGlassRenderer.captureBook(model, texture, f1, f2, f3, x, y, w, h);
		ci.cancel();
	}

	@Inject(
		method = "bannerPattern(Lnet/minecraft/client/model/object/banner/BannerFlagModel;Lnet/minecraft/world/item/DyeColor;Lnet/minecraft/world/level/block/entity/BannerPatternLayers;IIII)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void liquidum$deferBanner(BannerFlagModel model, DyeColor dye, BannerPatternLayers patterns,
	                                  int x, int y, int w, int h, CallbackInfo ci) {
		if (!LiquidGlassRenderer.deferForeground()) return;
		LiquidGlassRenderer.captureBanner(model, dye, patterns, x, y, w, h);
		ci.cancel();
	}
}
