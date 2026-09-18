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
 * ABOVE the glass composite вЂ” the world behind stays clean for refraction and
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
		// Open screen owns the blur marker so its background lands under the glass
		if (net.minecraft.client.Minecraft.getInstance().gui.screen() != null) return;
		if (!LiquidGlassRenderer.isBlurMarkerSet()) {
			try {
				guiGraphics.blurBeforeThisStratum();
			} catch (IllegalStateException foreignMark) {
				com.liquidum.LiquidumMod.LOGGER.warn("[glass] foreign blur marker adopted: {}", foreignMark.toString());
			} finally {
				LiquidGlassRenderer.setBlurMarkerSeen();
			}
		}
	}

	// Glass HUD in game and containers, Home strip in pause and settings
	private static boolean liquidum$useGlassHud() {
		var s = net.minecraft.client.Minecraft.getInstance().gui.screen();
		if (s == null) return true;
		if (s instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen) return true;
		if (s instanceof net.minecraft.client.gui.screens.PauseScreen) return true;
		if (s instanceof net.minecraft.client.gui.screens.ChatScreen) return true;
		if (s instanceof net.minecraft.client.gui.screens.options.OptionsScreen) return true;
		if (s instanceof net.minecraft.client.gui.screens.options.OptionsSubScreen) return true;
		return s instanceof net.minecraft.client.gui.screens.debug.GameModeSwitcherScreen;
	}

	// Icon halos only over undimmed HUD, menus keep vanilla icons
	private static boolean liquidum$hudIconsOn() {
		var s = net.minecraft.client.Minecraft.getInstance().gui.screen();
		return s == null || s instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
	}

	@Inject(method = "extractItemHotbar", at = @At("HEAD"))
	private void liquidum$hotbarBaseLayer(GuiGraphicsExtractor guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (!liquidum$useGlassHud()) return;
		com.liquidum.client.shader.LiquidumLayers.beginHotbarBase(guiGraphics);
	}

	@Inject(method = "extractItemHotbar", at = @At("TAIL"))
	private void liquidum$submitHotbarTile(GuiGraphicsExtractor guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (!liquidum$useGlassHud()) return;
		if (com.liquidum.client.compat.LiquidumOptOut.isOptedOut(net.minecraft.client.Minecraft.getInstance().gui.screen())) return;
		var player = net.minecraft.client.Minecraft.getInstance().player;
		int sel = player != null ? player.getInventory().getSelectedSlot() : -1;
		boolean offhand = player != null && !player.getOffhandItem().isEmpty();
		LiquidGlassRenderer.submitHotbar(guiGraphics.guiWidth(), guiGraphics.guiHeight(), sel, offhand);
		if (net.minecraft.client.Minecraft.getInstance().gui.screen() == null) {
			LiquidGlassRenderer.drawHotbarSelection(guiGraphics);
		}
	}

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void liquidum$submitDock(GuiGraphicsExtractor guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (!LiquidGlassRenderer.isEnabled()) return;
		if (net.minecraft.client.Minecraft.getInstance().gui.screen() != null) return;
		LiquidGlassRenderer.submitLuminanceDock(guiGraphics.guiWidth(), guiGraphics.guiHeight());
	}

	// Debug watermark: a stuck solo/mask view once faked a fullscreen blur bug, never again вЂ” the active view name stays on screen until reset
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void liquidum$soloWatermark(GuiGraphicsExtractor guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (!LiquidGlassRenderer.isEnabled()) return;
		boolean dirty = com.liquidum.client.debug.LiquidumDebugState.soloStage != 0
			|| com.liquidum.client.debug.LiquidumDebugState.mode != 2;
		String view = com.liquidum.client.debug.LiquidumDebugState.soloStage != 0
			? com.liquidum.client.debug.LiquidumDebugState.soloName()
			: com.liquidum.client.debug.LiquidumDebugState.modeName();
		String mark = "LIQ " + LiquidGlassRenderer.BUILD_ID + " " + view + " t" + LiquidGlassRenderer.pendingTiles()
			+ (com.liquidum.client.debug.LiquidumDebugState.fusion ? " fuse" + Math.round(com.liquidum.client.debug.LiquidumDebugState.fusionRadius) : " nofuse");
		try {
			net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
			guiGraphics.text(font, net.minecraft.network.chat.Component.literal(mark), 8, 8, dirty ? 0xFFFF55 : 0x555555, true);
		} catch (Exception ignored) {}
	}

	// Hotbar items on ITEMS layer, deferred sharp when a screen is open
	@WrapOperation(
		method = "extractSlot",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;item(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;III)V"
		)
	)
	private void liquidum$hotbarItemLayer(
		GuiGraphicsExtractor instance,
		net.minecraft.world.entity.LivingEntity entity,
		net.minecraft.world.item.ItemStack stack, int x, int y, int seed,
		Operation<Void> original) {
		if (LiquidGlassRenderer.isInForegroundReplay()) {
			original.call(instance, entity, stack, x, y, seed);
			return;
		}
		var screen = net.minecraft.client.Minecraft.getInstance().gui.screen();
		// Defer HUD items on open screens, options keep them under the glass
		if (screen != null && LiquidGlassRenderer.isEnabled() && !com.liquidum.client.compat.LiquidumOptOut.isOptedOut(screen)
			&& LiquidGlassRenderer.shouldRenderHotbarEdge() && !LiquidGlassRenderer.keepHudItemsBackground()) {
			LiquidGlassRenderer.deferHudItem(entity, stack, x, y, seed);
			return;
		}
		if (!com.liquidum.client.compat.LiquidumOptOut.isOptedOut(screen)) {
			com.liquidum.client.shader.LiquidumLayers.beginItems(instance);
		}
		original.call(instance, entity, stack, x, y, seed);
	}

	@WrapOperation(
		method = "extractSlot",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;itemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;II)V"
		)
	)
	private void liquidum$hotbarDecorLayer(
		GuiGraphicsExtractor instance,
		net.minecraft.client.gui.Font font,
		net.minecraft.world.item.ItemStack stack, int x, int y,
		Operation<Void> original) {
		if (LiquidGlassRenderer.isInForegroundReplay()) {
			original.call(instance, font, stack, x, y);
			return;
		}
		var screen = net.minecraft.client.Minecraft.getInstance().gui.screen();
		// Same for counts and durability, options keep them under the glass too
		if (screen != null && LiquidGlassRenderer.isEnabled() && !com.liquidum.client.compat.LiquidumOptOut.isOptedOut(screen)
			&& LiquidGlassRenderer.shouldRenderHotbarEdge() && !LiquidGlassRenderer.keepHudItemsBackground()) {
			LiquidGlassRenderer.deferHudDecor(font, stack, x, y);
			return;
		}
		if (!com.liquidum.client.compat.LiquidumOptOut.isOptedOut(screen)) {
			com.liquidum.client.shader.LiquidumLayers.beginText(instance);
		}
		original.call(instance, font, stack, x, y);
	}

	// Icon halos follow live sprite coords, shape picks the halo SDF
	@WrapOperation(
		method = "extractHeart",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"
		)
	)
	private void liquidum$heartIconTile(GuiGraphicsExtractor instance, RenderPipeline pipeline, Identifier sprite,
	                                     int x, int y, int w, int h, Operation<Void> original) {
		original.call(instance, pipeline, sprite, x, y, w, h);
		if (!LiquidGlassRenderer.isInForegroundReplay() && liquidum$hudIconsOn()
			&& !com.liquidum.client.compat.LiquidumOptOut.isOptedOut(net.minecraft.client.Minecraft.getInstance().gui.screen())) {
			LiquidGlassRenderer.submitIconTile(x, y, w, h, LiquidGlassRenderer.SHAPE_HEART);
		}
	}

	@WrapOperation(
		method = "extractArmor",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"
		)
	)
	private static void liquidum$armorIconTile(GuiGraphicsExtractor instance, RenderPipeline pipeline, Identifier sprite,
	                                     int x, int y, int w, int h, Operation<Void> original) {
		original.call(instance, pipeline, sprite, x, y, w, h);
		if (!LiquidGlassRenderer.isInForegroundReplay() && liquidum$hudIconsOn()
			&& !com.liquidum.client.compat.LiquidumOptOut.isOptedOut(net.minecraft.client.Minecraft.getInstance().gui.screen())) {
			LiquidGlassRenderer.submitIconTile(x, y, w, h, 0);
		}
	}

	@WrapOperation(
		method = "extractFood",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"
		)
	)
	private void liquidum$foodIconTile(GuiGraphicsExtractor instance, RenderPipeline pipeline, Identifier sprite,
	                                     int x, int y, int w, int h, Operation<Void> original) {
		original.call(instance, pipeline, sprite, x, y, w, h);
		if (!LiquidGlassRenderer.isInForegroundReplay() && liquidum$hudIconsOn()
			&& !com.liquidum.client.compat.LiquidumOptOut.isOptedOut(net.minecraft.client.Minecraft.getInstance().gui.screen())) {
			LiquidGlassRenderer.submitIconTile(x, y, w, h, LiquidGlassRenderer.SHAPE_MEAT);
		}
	}

	@WrapOperation(
		method = "extractAirBubbles",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"
		)
	)
	private void liquidum$airIconTile(GuiGraphicsExtractor instance, RenderPipeline pipeline, Identifier sprite,
	                                     int x, int y, int w, int h, Operation<Void> original) {
		original.call(instance, pipeline, sprite, x, y, w, h);
		if (!LiquidGlassRenderer.isInForegroundReplay() && liquidum$hudIconsOn()
			&& !com.liquidum.client.compat.LiquidumOptOut.isOptedOut(net.minecraft.client.Minecraft.getInstance().gui.screen())) {
			LiquidGlassRenderer.submitIconTile(x, y, w, h, 2);
		}
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
		if (!LiquidGlassRenderer.replaceHotbarBackground() || !liquidum$useGlassHud()) {
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
		if (!LiquidGlassRenderer.replaceHotbarBackground() || !liquidum$useGlassHud()) {
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
		if (!LiquidGlassRenderer.replaceHotbarBackground() || !liquidum$useGlassHud()) {
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
		if (!LiquidGlassRenderer.replaceHotbarBackground() || !liquidum$useGlassHud()) {
			original.call(instance, pipeline, sprite, x, y, width, height);
		}
	}
}
