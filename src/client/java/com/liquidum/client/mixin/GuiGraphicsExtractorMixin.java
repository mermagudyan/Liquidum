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
			// 2px inset: книга и инвентарь читаются как две РОДСТВЕННЫЕ панели
			// с намеренным зазором, а не как две случайно наложившиеся
			// поверхности (стык без скруглённого «вспухания»).
			LiquidGlassRenderer.submitLightPanel(x + 2, y + 2, width - 4, height - 4);
			ci.cancel();
			return;
		}
		// Большая панель теперь — полная подстилка на всю высоту контейнера
		// через ContainerMixin (как у Recipe Book 147x166, MAT_COMPANION).
		// Любую container/* панель гасим без uPanel. Порог по высоте 48, а не 100:
		// ContainerScreen (сундук) рисует двумя блитами 176x71 (верх) + 176x96 (низ),
		// оба <100 и просачивались серой текстурой. Shulker/Hopper/Furnace — одним
		// блитом 176x133..167 и гасились корректно, поэтому серая панель была только в сундуках.
		var screen = net.minecraft.client.Minecraft.getInstance().gui.screen();
		if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen) {
			if (texture.getPath().startsWith("textures/gui/container/") && width > 100 && height > 48) {
				ci.cancel();
				return;
			}
		}
		// Маленькие блиты печи/точилки/зачарования/крафта — sharp foreground
		// (иначе огонь/стрелка ниже стекла, как в печи). Деферим все маленькие
		// container/* <40px, независимо от имени файла.
		String path = texture.getPath();
		if (path.startsWith("textures/gui/container/") && width < 40 && height < 40) {
			if (LiquidGlassRenderer.deferForeground()) {
				LiquidGlassRenderer.deferBlit(pipeline, texture, x, y, u, v, width, height, texW, texH);
				ci.cancel();
				return;
			}
		}
		if (!LiquidGlassRenderer.filterContainerPanel(texture)) return;
		LiquidGlassRenderer.submitPanelRect(x, y, width, height);
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
			LiquidGlassRenderer.submitSpriteTile(x, y, width, height, LiquidGlassRenderer.MAT_ACTIVE);
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
		// Большая матовая панель контейнера через blitSprite (container/background 176×166/222
		// или generic_54 части) — убираем везде, остаётся полная подстилка
		// из ContainerMixin (как у Recipe Book 147x166, на всю высоту imageHeight).
		// Порог 48 для совместимости с двух-блитовым сундуком.
		String sp = sprite.getPath();
		if (sp.startsWith("container/") && width > 100 && height > 48) {
			var screen2 = net.minecraft.client.Minecraft.getInstance().gui.screen();
			if (screen2 instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen) {
				ci.cancel();
				return;
			}
		}
		// Progress icons furnace/grindstone/enchanting etc. — должны быть sharp foreground,
		// Все маленькие container-спрайты прогрессов/стрелок/иконок кликабельных блоков
		// (печь, точило, зачарование и т.д.) — должны быть sharp foreground, а не
		// частью blurred panel (иначе огонь/стрелка ниже стекла). Деферим все
		// container/* <40px, кроме slot_highlight (который отменяется Wall-ом).
		if (sp.startsWith("container/") && !sp.contains("slot_highlight") && width < 40 && height < 40) {
			if (LiquidGlassRenderer.deferForeground()) {
				LiquidGlassRenderer.deferBlitSprite(pipeline, sprite, x, y, width, height);
				ci.cancel();
				return;
			}
		}
		// Recipe Book button: icon-only (см. LiquidGlassRenderer.drawRecipeBookButton).
		if (sp.startsWith("recipe_book/button")) {
			ci.cancel();
			LiquidGlassRenderer.drawRecipeBookButton(this.liquidum$extractor(), pipeline,
				x, y, width, height, sprite.getPath().contains("highlighted"));
			return;
		}
		int mat = LiquidGlassRenderer.filterUiSprite(sprite);
		if (mat < 0) {
			if (!LiquidGlassRenderer.filterCreativeTab(sprite)) return;
			mat = LiquidGlassRenderer.MAT_CONTROL;
		}
		LiquidGlassRenderer.submitSpriteTile(x, y, width, height, mat);
		ci.cancel();
	}

	/** Tab icons (creative) — должны быть sharp foreground над tab glass.
	 *  Vanila рисует их в той же фазе, что и tab background, но glass composite
	 *  идёт между background и widget фазами — иконка должна быть после glass.
	 *  Перехватываем item вызовы для вкладок и откладываем до replay. */
	@Inject(method = "item(Lnet/minecraft/world/item/ItemStack;III)V", at = @At("HEAD"), cancellable = true)
	private void liquidum$deferTabIcon(net.minecraft.world.item.ItemStack stack, int x, int y, int seed, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (!LiquidGlassRenderer.deferForeground()) return;
		var screen = net.minecraft.client.Minecraft.getInstance().gui.screen();
		if (!(screen instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen)) return;
		try {
			var acc = (AbstractContainerScreenAccessor) screen;
			int topPos = acc.liquidum$getTopPos();
			// Tab bar is  -28..0 above panel (y < topPos)
			if (y < topPos && y > topPos - 40) {
				LiquidGlassRenderer.deferTabIcon(stack, x, y, seed);
				ci.cancel();
			}
		} catch (Exception ignored) {}
	}

	/** Caster для передачи extractor в renderer без статического контекста. */
	private net.minecraft.client.gui.GuiGraphicsExtractor liquidum$extractor() {
		return (net.minecraft.client.gui.GuiGraphicsExtractor) (Object) this;
	}
}
