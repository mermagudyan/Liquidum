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
		if (LiquidGlassRenderer.filterRecipeBookPanel(texture)) {
			// 2px inset: book and inventory read as sibling panels, not a random overlap
			LiquidGlassRenderer.submitBookPanel(x + 2, y + 2, width - 4, height - 4);
			ci.cancel();
			return;
		}
		// Матовая подстилка удалена навсегда — ванильную панель 176x166/222 просто гасим, без submitPanelRect (мир просвечивает, остаются только Well-колодцы 18x18).
		var screen = net.minecraft.client.Minecraft.getInstance().gui.screen();
		if (com.liquidum.client.compat.LiquidumOptOut.isOptedOut(screen)) return;
		if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen) {
			if (!LiquidGlassRenderer.replaceSlotTiles()) return;
			if (texture.getPath().startsWith("textures/gui/container/") && width > 100 && height > 48) {
				ci.cancel();
				return;
			}
		}
		// Small container blits go sharp foreground, defer all under 40px
		String path = texture.getPath();
		if (path.startsWith("textures/gui/container/") && width < 40 && height < 40) {
			if (LiquidGlassRenderer.deferForeground()) {
				LiquidGlassRenderer.deferBlit(pipeline, texture, x, y, u, v, width, height, texW, texH);
				ci.cancel();
				return;
			}
		}
		if (!LiquidGlassRenderer.filterContainerPanel(texture)) return;
		ci.cancel();
	}

	/**
	 * Recipe Book selected overlay (recipe_book/overlay*) is drawn via this blit
	 * overload (not blitSprite), so it was slipping past filterUiSprite and
	 * showing a red square. Cancel the vanilla sprite and replace it with our
	 * subtle MAT_ACTIVE glass state (no red square) — P3.
	 */
	@Inject(
		method = "blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V",
		at = @At("HEAD"),
		cancellable = true,
		require = 0
	)
	private void liquidum$filterRecipeOverlay(RenderPipeline pipeline, Identifier texture, int x, int y,
	                                          float u, float v, int width, int height,
	                                          int texW, int texH, CallbackInfo ci) {
		if (texture.getPath().contains("recipe_book/overlay")) {
			LiquidGlassRenderer.submitSpriteTile(x, y, width, height, LiquidGlassRenderer.MAT_ACTIVE, 1.0f, 0.0f);
			ci.cancel();
		}
	}

	/**
	 * Creative mode tabs (blitSprite overload): tab sprites are cancelled and
	 * replaced by glass tiles at the same rect (full sprite, no cut —
	 * vanilla position preserved).
	 */
	@Inject(
		method = "blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void liquidum$filterCreativeTabs(RenderPipeline pipeline, Identifier sprite,
	                                         int x, int y, int width, int height, CallbackInfo ci) {
		// Large container panels cancelled here, backing comes from ContainerMixin
		String sp = sprite.getPath();
		if (sp.startsWith("container/") && width > 100 && height > 48) {
			var screen2 = net.minecraft.client.Minecraft.getInstance().gui.screen();
			if (com.liquidum.client.compat.LiquidumOptOut.isOptedOut(screen2)) return;
			if (screen2 instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen) {
				if (!LiquidGlassRenderer.replaceSlotTiles()) return;
				ci.cancel();
				return;
			}
		}
		// Matte backing only cancelled; small progress sprites defer sharp foreground
		if (sp.equals("container/slot")) {
			var screen3 = net.minecraft.client.Minecraft.getInstance().gui.screen();
			if (com.liquidum.client.compat.LiquidumOptOut.isOptedOut(screen3)) return;
			if (screen3 instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
				&& LiquidGlassRenderer.replaceSlotTiles()) {
				ci.cancel();
				return;
			}
		}
		if (sp.startsWith("container/") && !sp.contains("slot_highlight") && width < 40 && height < 40) {
			if (LiquidGlassRenderer.deferForeground()) {
				LiquidGlassRenderer.deferBlitSprite(pipeline, sprite, x, y, width, height);
				ci.cancel();
				return;
			}
		}
		// Rename field stays crisp above panel glass
		if (sp.contains("text_field") && LiquidGlassRenderer.deferForeground()) {
			LiquidGlassRenderer.deferBlitSprite(pipeline, sprite, x, y, width, height);
			ci.cancel();
			return;
		}
		// Map preview backing stays with the map above panel glass
		if (sp.startsWith("container/cartography_table/") && LiquidGlassRenderer.deferForeground()) {
			LiquidGlassRenderer.deferBlitSprite(pipeline, sprite, x, y, width, height);
			ci.cancel();
			return;
		}
		// Enchant clue rows keep their own glass body
		if (sp.startsWith("container/enchanting_table/enchantment_slot")) {
			LiquidGlassRenderer.submitSpriteTile(x, y, width, height, LiquidGlassRenderer.MAT_CONTROL, 1.0f, 2.0f + (y / 20));
			ci.cancel();
			return;
		}
		// Recipe Book button: icon-only (см. LiquidGlassRenderer.drawRecipeBookButton).
		if (sp.startsWith("recipe_book/button")) {
			ci.cancel();
			LiquidGlassRenderer.drawRecipeBookButton(this.liquidum$extractor(), pipeline,
				x, y, width, height, sprite.getPath().contains("highlighted"));
			return;
		}
		if (sp.contains("recipe_book/overlay")) {
			LiquidGlassRenderer.submitSpriteTile(x, y, width, height, LiquidGlassRenderer.MAT_ACTIVE, 1.0f, 0.0f);
			ci.cancel();
			return;
		}
		int mat = LiquidGlassRenderer.filterUiSprite(sprite);
		if (mat < 0) {
			if (!LiquidGlassRenderer.filterCreativeTab(sprite)) return;
			mat = LiquidGlassRenderer.MAT_CONTROL;
		}
		if (sp.startsWith("container/creative_inventory/tab_")) {
			boolean sel = sp.contains("selected");
			boolean isTop = sp.contains("_top_");
			int useMat = sel ? LiquidGlassRenderer.MAT_COMPANION : LiquidGlassRenderer.MAT_CONTROL;
			if (sel) {
				if (isTop) LiquidGlassRenderer.submitSpriteTile(x, y, width, height + 4, useMat, 0.0f, 0.0f);
				else LiquidGlassRenderer.submitSpriteTile(x, y - 4, width, height + 4, useMat, 0.0f, 0.0f);
			} else {
				LiquidGlassRenderer.submitSpriteTile(x, y, width, height, useMat, 0.0f, 0.0f);
			}
			ci.cancel();
			return;
		}
		if (sp.startsWith("recipe_book/tab")) {
			// Active tab joins the book surface (elev 0, group 1), idle tabs fuse alone
			boolean sel = sp.contains("selected");
			if (sel) LiquidGlassRenderer.submitSpriteTile(x, y, width + LiquidGlassRenderer.tabBridge(x, y, width, height), height, LiquidGlassRenderer.MAT_COMPANION, 0.0f, 1.0f);
			else LiquidGlassRenderer.submitSpriteTile(x, y, width, height, mat, 0.0f, 2.0f + (y / 20));
		} else if (sp.startsWith("widget/text_field")) {
			// Base plane until upper sheets get content replay, own group so it never fuses
			LiquidGlassRenderer.submitSpriteTile(x, y, width, height, mat, 0.0f, 20.0f);
		} else if (sp.startsWith("recipe_book/overlay")) {
			// In-book controls match book height, fuse alone
			LiquidGlassRenderer.submitSpriteTile(x, y, width, height, mat, 1.0f, 0.0f);
		} else {
			LiquidGlassRenderer.submitSpriteTile(x, y, width, height, mat);
		}
		ci.cancel();
	}

	/**
	 * Other blit overloads (11/12-arg with color/depth tail): vanilla draws some
	 * overlays through them — same treatment as the 10-arg hook.
	 */
	// Sliced progress icons share the sharp foreground path
	@Inject(
		method = "blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIIIIII)V",
		at = @At("HEAD"),
		cancellable = true,
		require = 0
	)
	private void liquidum$deferProgressNine(RenderPipeline pipeline, Identifier sprite,
	                                        int u0, int v0, int sw, int sh,
	                                        int x, int y, int w, int h, CallbackInfo ci) {
		if (!LiquidGlassRenderer.deferForeground()) return;
		if (LiquidGlassRenderer.isInForegroundReplay()) return;
		var screen = net.minecraft.client.Minecraft.getInstance().gui.screen();
		if (!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen)) return;
		String sp = sprite.getPath();
		if (sp.contains("slot_highlight")) return;
		if (w < 40 && h < 40 && sp.startsWith("container/")) {
			LiquidGlassRenderer.deferBlitSprite9(pipeline, sprite, u0, v0, sw, sh, x, y, w, h);
			ci.cancel();
		}
	}
	@Inject(
		method = "blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIII)V",
		at = @At("HEAD"),
		cancellable = true,
		require = 0
	)
	private void liquidum$filterBlit11(RenderPipeline pipeline, Identifier texture, int x, int y,
									  float u, float v, int width, int height,
									  int texW, int texH, int extra, CallbackInfo ci) {
		if (texture.getPath().contains("recipe_book/overlay")) {
			LiquidGlassRenderer.submitSpriteTile(x, y, width, height, LiquidGlassRenderer.MAT_ACTIVE, 1.0f, 0.0f);
			ci.cancel();
		}
	}

	@Inject(
		method = "blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIII)V",
		at = @At("HEAD"),
		cancellable = true,
		require = 0
	)
	private void liquidum$filterBlit12(RenderPipeline pipeline, Identifier texture, int x, int y,
									  float u, float v, int width, int height,
									  int texW, int texH, int extra1, int extra2, CallbackInfo ci) {
		if (texture.getPath().contains("recipe_book/overlay")) {
			LiquidGlassRenderer.submitSpriteTile(x, y, width, height, LiquidGlassRenderer.MAT_ACTIVE, 1.0f, 0.0f);
			ci.cancel();
		}
	}

	/** Tab icons (creative) — должны быть sharp foreground над tab glass.
	 *  Vanila рисует их в той же фазе, что и tab background, но glass composite
	 *  идёт между background и widget фазами — иконка должна быть после glass.
	 *  Верхние табы около topPos-19, нижние около topPos+imageHeight+3. */
	private boolean liquidum$isCreativeTabIcon(int x, int y) {
		try {
			var screen = net.minecraft.client.Minecraft.getInstance().gui.screen();
			if (!(screen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen cScreen)) return false;
			var acc = (AbstractContainerScreenAccessor) screen;
			int leftPos = acc.liquidum$getLeftPos();
			int topPos = acc.liquidum$getTopPos();
			int imageHeight = acc.liquidum$getImageHeight();
			boolean inTop = y >= topPos - 32 && y <= topPos + 4;
			boolean inBottom = y >= topPos + imageHeight - 12 && y <= topPos + imageHeight + 24;
			if (!inTop && !inBottom) return false;
			var menu = cScreen.getMenu();
			for (var slot : menu.slots) {
				if ((slot.x == x && slot.y == y) || (leftPos + slot.x == x && topPos + slot.y == y)) return false;
			}
			return true;
		} catch (Exception ignored) {
			return false;
		}
	}
	@Inject(method = "item(Lnet/minecraft/world/item/ItemStack;III)V", at = @At("HEAD"), cancellable = true)
	private void liquidum$deferTabIcon(net.minecraft.world.item.ItemStack stack, int x, int y, int seed, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (!LiquidGlassRenderer.deferForeground()) return;
		if (!liquidum$isCreativeTabIcon(x, y)) return;
		LiquidGlassRenderer.deferTabIcon(stack, x, y, seed);
		ci.cancel();
	}
	@Inject(method = "item(Lnet/minecraft/world/item/ItemStack;II)V", at = @At("HEAD"), cancellable = true)
	private void liquidum$deferTabIconNoSeed(net.minecraft.world.item.ItemStack stack, int x, int y, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (!LiquidGlassRenderer.deferForeground()) return;
		if (!liquidum$isCreativeTabIcon(x, y)) return;
		LiquidGlassRenderer.deferTabIcon(stack, x, y, 0);
		ci.cancel();
	}
	@Inject(method = "fakeItem(Lnet/minecraft/world/item/ItemStack;III)V", at = @At("HEAD"), cancellable = true)
	private void liquidum$deferTabFakeIconSeed(net.minecraft.world.item.ItemStack stack, int x, int y, int seed, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (!LiquidGlassRenderer.deferForeground()) return;
		if (!liquidum$isCreativeTabIcon(x, y)) return;
		LiquidGlassRenderer.deferTabIcon(stack, x, y, seed);
		ci.cancel();
	}
	@Inject(method = "fakeItem(Lnet/minecraft/world/item/ItemStack;II)V", at = @At("HEAD"), cancellable = true)
	private void liquidum$deferTabFakeIcon(net.minecraft.world.item.ItemStack stack, int x, int y, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (!LiquidGlassRenderer.deferForeground()) return;
		if (!liquidum$isCreativeTabIcon(x, y)) return;
		LiquidGlassRenderer.deferTabIcon(stack, x, y, 0);
		ci.cancel();
	}

	// Display items baked in background land above glass instead
	@Inject(method = "item(Lnet/minecraft/world/item/ItemStack;III)V", at = @At("HEAD"), cancellable = true)
	private void liquidum$deferBackgroundItem(net.minecraft.world.item.ItemStack stack, int x, int y, int seed, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (!LiquidGlassRenderer.deferForeground()) return;
		if (LiquidGlassRenderer.isBlurMarkerSet()) return;
		if (LiquidGlassRenderer.isInForegroundReplay()) return;
		var screen = net.minecraft.client.Minecraft.getInstance().gui.screen();
		if (!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen)) return;
		LiquidGlassRenderer.deferTabIcon(stack, x, y, seed);
		ci.cancel();
	}
	@Inject(method = "item(Lnet/minecraft/world/item/ItemStack;II)V", at = @At("HEAD"), cancellable = true)
	private void liquidum$deferBackgroundItemNoSeed(net.minecraft.world.item.ItemStack stack, int x, int y, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (!LiquidGlassRenderer.deferForeground()) return;
		if (LiquidGlassRenderer.isBlurMarkerSet()) return;
		if (LiquidGlassRenderer.isInForegroundReplay()) return;
		var screen = net.minecraft.client.Minecraft.getInstance().gui.screen();
		if (!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen)) return;
		LiquidGlassRenderer.deferTabIcon(stack, x, y, 0);
		ci.cancel();
	}
	@Inject(method = "fakeItem(Lnet/minecraft/world/item/ItemStack;III)V", at = @At("HEAD"), cancellable = true)
	private void liquidum$deferBackgroundFakeSeed(net.minecraft.world.item.ItemStack stack, int x, int y, int seed, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (!LiquidGlassRenderer.deferForeground()) return;
		if (LiquidGlassRenderer.isBlurMarkerSet()) return;
		if (LiquidGlassRenderer.isInForegroundReplay()) return;
		var screen = net.minecraft.client.Minecraft.getInstance().gui.screen();
		if (!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen)) return;
		LiquidGlassRenderer.deferTabIcon(stack, x, y, seed);
		ci.cancel();
	}
	@Inject(method = "fakeItem(Lnet/minecraft/world/item/ItemStack;II)V", at = @At("HEAD"), cancellable = true)
	private void liquidum$deferBackgroundFake(net.minecraft.world.item.ItemStack stack, int x, int y, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (!LiquidGlassRenderer.deferForeground()) return;
		if (LiquidGlassRenderer.isBlurMarkerSet()) return;
		if (LiquidGlassRenderer.isInForegroundReplay()) return;
		var screen = net.minecraft.client.Minecraft.getInstance().gui.screen();
		if (!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen)) return;
		LiquidGlassRenderer.deferTabIcon(stack, x, y, 0);
		ci.cancel();
	}

	/** P2: Anvil cost / Enchant clues — рисуются в extractBackground (до blur) → размывались.
	 *  Откладываем text из background-фазы в foreground (после стекла), как entity/book. */	@Inject(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V", at = @At("HEAD"), cancellable = true)
	private void liquidum$deferBackgroundText(net.minecraft.client.gui.Font font, net.minecraft.network.chat.Component text, int x, int y, int color, boolean shadow, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		// Defers only truly pre-blur container foreground, post-marker text stays
		if (!LiquidGlassRenderer.deferForeground()) return;
		if (LiquidGlassRenderer.isBlurMarkerSet()) return; // уже после blur — sharp и так
		if (LiquidGlassRenderer.isInForegroundReplay()) return;
		var screen = net.minecraft.client.Minecraft.getInstance().gui.screen();
		if (!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen)) {
			net.minecraft.util.FormattedCharSequence seq = net.minecraft.locale.Language.getInstance()
				.getVisualOrder(text);
			liquidum$stashPlaneText(font, seq, x, y, color, shadow, ci);
			return;
		}
		LiquidGlassRenderer.deferText(font, text, x, y, color, shadow);
		ci.cancel();
	}
	// Widget text replays above its own plane, covered text stays refracted backdrop
	private void liquidum$stashPlaneText(net.minecraft.client.gui.Font font, net.minecraft.util.FormattedCharSequence seq, int x, int y, int color, boolean shadow, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (!LiquidGlassRenderer.isEnabled() || !LiquidGlassRenderer.isBlurMarkerSet()) return;
		if (LiquidGlassRenderer.isInForegroundReplay()) return;
		float owner = LiquidGlassRenderer.ownerElevFor(x, y);
		org.joml.Matrix3x2f pose;
		try {
			pose = new org.joml.Matrix3x2f(this.liquidum$extractor().pose());
		} catch (Exception e) {
			return;
		}
		if (LiquidGlassRenderer.stashPlaneText(font, seq, pose, x, y, color, shadow, owner)) ci.cancel();
	}
	@Inject(method = "text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V", at = @At("HEAD"), cancellable = true)
	private void liquidum$deferBackgroundTextStr(net.minecraft.client.gui.Font font, String text, int x, int y, int color, boolean shadow, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (!LiquidGlassRenderer.deferForeground()) return;
		if (LiquidGlassRenderer.isBlurMarkerSet()) return;
		if (LiquidGlassRenderer.isInForegroundReplay()) return;
		var screen = net.minecraft.client.Minecraft.getInstance().gui.screen();
		if (!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen)) {
			net.minecraft.util.FormattedCharSequence seq = net.minecraft.locale.Language.getInstance()
				.getVisualOrder(net.minecraft.network.chat.FormattedText.of(text));
			liquidum$stashPlaneText(font, seq, x, y, color, shadow, ci);
			return;
		}
		LiquidGlassRenderer.deferText(font, net.minecraft.network.chat.Component.literal(text), x, y, color, shadow);
		ci.cancel();
	}
	@Inject(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V", at = @At("HEAD"), cancellable = true)
	private void liquidum$deferBackgroundTextSeq(net.minecraft.client.gui.Font font, net.minecraft.util.FormattedCharSequence text, int x, int y, int color, boolean shadow, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (!LiquidGlassRenderer.deferForeground()) return;
		if (LiquidGlassRenderer.isBlurMarkerSet()) return;
		if (LiquidGlassRenderer.isInForegroundReplay()) return;
		var screen = net.minecraft.client.Minecraft.getInstance().gui.screen();
		if (!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen)) {
			liquidum$stashPlaneText(font, text, x, y, color, shadow, ci);
			return;
		}
		LiquidGlassRenderer.deferSeqText(font, text, x, y, color, shadow);
		ci.cancel();
	}

	// Text always one layer above its base
	@Inject(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V", at = @At("HEAD"))
	private void liquidum$textAboveBase(net.minecraft.client.gui.Font font, net.minecraft.network.chat.Component text, int x, int y, int color, boolean shadow, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		liquidum$pushTextLayer();
	}

	@Inject(method = "text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V", at = @At("HEAD"))
	private void liquidum$textAboveBaseStr(net.minecraft.client.gui.Font font, String text, int x, int y, int color, boolean shadow, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		liquidum$pushTextLayer();
	}

	@Inject(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V", at = @At("HEAD"))
	private void liquidum$textAboveBaseSeq(net.minecraft.client.gui.Font font, net.minecraft.util.FormattedCharSequence text, int x, int y, int color, boolean shadow, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		liquidum$pushTextLayer();
	}

	private void liquidum$pushTextLayer() {
		if (!LiquidGlassRenderer.isEnabled()) return;
		if (!LiquidGlassRenderer.isBlurMarkerSet()) return;
		if (LiquidGlassRenderer.isInForegroundReplay()) return;
		var screen = net.minecraft.client.Minecraft.getInstance().gui.screen();
		if (screen == null) return;
		if (com.liquidum.client.compat.LiquidumOptOut.isOptedOut(screen)) return;
		com.liquidum.client.shader.LiquidumLayers.beginText(this.liquidum$extractor());
	}

	/** Caster для передачи extractor в renderer без статического контекста. */
	private net.minecraft.client.gui.GuiGraphicsExtractor liquidum$extractor() {
		return (net.minecraft.client.gui.GuiGraphicsExtractor) (Object) this;
	}

	// Deferred tooltip lands after popup rows but TAIL glass covers both
	@Inject(
		method = "tooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;Lnet/minecraft/resources/Identifier;)V",
		at = @At("TAIL")
	)
	private void liquidum$tooltipAbovePopup(net.minecraft.client.gui.Font font, java.util.List<net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent> lines,
	                                        int x, int y, net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner positioner,
	                                        net.minecraft.resources.Identifier sprite, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (!LiquidGlassRenderer.isEnabled() || !LiquidGlassRenderer.hasPopupTiles()) return;
		var e = liquidum$extractor();
		LiquidGlassRenderer.submitTooltipCutout(font, lines, x, y, positioner, e.guiWidth(), e.guiHeight());
	}
}
