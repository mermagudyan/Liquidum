package com.liquidum.client.mixin;

import com.liquidum.client.shader.LiquidGlassRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import java.util.List;

/**
 * Stage 4: glass tiles for container/inventory slots.
 *
 * Vanilla slot cells are baked into the container panel texture (drawn in the
 * before-blur phase, under our composite), and slot items are extracted after
 * the boundary (above the glass) — so submitting one dense 18x18 tile per
 * active slot replaces the vanilla cell with glass while items stay crisp.
 * Tiles are fusion-exempt: adjacent slots keep hard edges (iOS widget grid).
 */
@Mixin(AbstractContainerScreen.class)
public class ContainerMixin {

	@Shadow
	protected int leftPos;
	@Shadow
	protected int topPos;
	@Shadow
	protected int imageWidth;
	@Shadow
	protected int imageHeight;
	@Shadow
	private AbstractContainerMenu menu;

	@Inject(method = "extractSlots", at = @At("HEAD"))
	private void liquidum$submitSlotTiles(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, CallbackInfo ci) {
		if (!LiquidGlassRenderer.replaceSlotTiles()) return;
		// Panel: dual-source (blit filter + this exact rect) — keeps the frosted
		// base guaranteed even if the texture path filter misses a screen.
		// Submitted at HEAD so the tiles exist BEFORE items are extracted
		// (the item-parallax wrapper matches items against tile rects).
		LiquidGlassRenderer.submitPanelRect(leftPos, topPos, imageWidth, imageHeight);
		List<Slot> slots = menu.slots;
		for (Slot slot : slots) {
			if (!slot.isActive()) continue;
			LiquidGlassRenderer.submitSlotTile(leftPos + slot.x - 1, topPos + slot.y - 1);
		}
	}

	/**
	 * PARALLAX layer 2: item icons drift TOWARD the smoothed cursor (the world
	 * through the glass drifts away — shader side). Opposite motion = depth.
	 * The call site uses the (stack, x, y, seed) overload — the 4th int passes
	 * through untouched.
	 */
	@WrapOperation(
		method = "extractSlot",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;item(Lnet/minecraft/world/item/ItemStack;III)V"
		)
	)
	private void liquidum$itemParallax(
		net.minecraft.client.gui.GuiGraphicsExtractor instance,
		net.minecraft.world.item.ItemStack stack, int x, int y, int seed,
		Operation<Void> original) {
		float[] off = LiquidGlassRenderer.itemParallax(leftPos + x, topPos + y);
		if (off == null) {
			original.call(instance, stack, x, y, seed);
			return;
		}
		original.call(instance, stack, x + Math.round(off[0]), y + Math.round(off[1]), seed);
	}
}
