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
		// Panel: полная подстилка на всю высоту контейнера, как у Recipe Book
		// (147x166, MAT_COMPANION). Раньше была только 86px под инвентарём игрока,
		// верх оставался только wells — теперь весь фон, как у книги рецептов.
		LiquidGlassRenderer.submitLightPanel(leftPos, topPos, imageWidth, imageHeight);
		if (LiquidGlassRenderer.DEBUG && LiquidGlassRenderer.diagCount() % 120 == 0) {
			com.liquidum.LiquidumMod.LOGGER.info("[glass] container pos: leftPos={} topPos={} imgW={} imgH={} slots={}", leftPos, topPos, imageWidth, imageHeight, menu.slots.size());
		}
		List<Slot> slots = menu.slots;
		liquidum$submitWells(slots);
	}

	/**
	 * Well-система (визуальный примитив GridWell):
	 *
	 * 1. Детектор регулярных сеток: горизонтальные серии с шагом ровно 18,
	 *    одинаковые соседние ряды (y+18, тот же start/длина) сливаются в
	 *    блок cols×rows → ОДИН дескриптор GridWell (процедурная геометрия
	 *    в шейдере, без per-slot прямоугольников).
	 *    Player inventory = 9×3 well + hotbar 9×1 well (compound из двух).
	 * 2. Одиночные/свободные слоты (anvil inputs, furnace, enchanting,
	 *    результат крафта) — минимальные FreeformWell (MAT_SLOT tile).
	 * 3. Семантика НЕ угадывается геометрией: для известной печки —
	 *    адаптер с реальными значениями AbstractFurnaceMenu.
	 */
	private void liquidum$submitWells(List<Slot> slots) {
		int n = slots.size();
		if (n == 0) return;

		java.util.Map<Integer, List<Integer>> rows = new java.util.HashMap<>();
		for (int i = 0; i < n; i++) {
			if (!slots.get(i).isActive()) continue;
			rows.computeIfAbsent(slots.get(i).y, k -> new java.util.ArrayList<>()).add(i);
		}
		List<Integer> ys = new java.util.ArrayList<>(rows.keySet());
		java.util.Collections.sort(ys);

		// Блоки: {x0, y0, cols, rows, List<slotIndex>}
		List<Object[]> blocks = new java.util.ArrayList<>();
		// Вертикальные 1×N (броня 1×4) — шаг 18 по y, len==1 но rows>=2 → один GridWell
		java.util.Map<Integer, List<Integer>> colsMap = new java.util.HashMap<>();
		for (int y : ys) {
			List<Integer> r = rows.get(y);
			if (r.size() == 1) colsMap.computeIfAbsent(slots.get(r.get(0)).x, k -> new java.util.ArrayList<>()).add(r.get(0));
		}
		for (List<Integer> col : colsMap.values()) {
			col.sort((a,b)-> slots.get(a).y - slots.get(b).y);
			int k=0;
			while(k<col.size()){
				int s=k;
				while(k+1<col.size() && slots.get(col.get(k+1)).y - slots.get(col.get(k)).y == 18) k++;
				int len=k-s+1;
				if(len>=2){
					int sx=slots.get(col.get(s)).x, sy=slots.get(col.get(s)).y;
					blocks.add(new Object[]{sx,sy,1,len, new java.util.ArrayList<>(col.subList(s,k+1))});
					for(int t=s;t<=k;t++) rows.get(slots.get(col.get(t)).y).remove((Integer)col.get(t));
				}
				k++;
			}
		}
		// Пересобрать ys после удаления вертикальных блоков
		ys = new java.util.ArrayList<>(rows.keySet());
		ys.removeIf(y -> rows.get(y).isEmpty());
		java.util.Collections.sort(ys);
		java.util.Map<String, Object[]> prevRow = new java.util.HashMap<>();

		for (int y : ys) {
			List<Integer> row = rows.get(y);
			row.sort((a, b) -> slots.get(a).x - slots.get(b).x);
			java.util.Map<String, Object[]> curRow = new java.util.HashMap<>();
			int k = 0;
			while (k < row.size()) {
				int runStart = k;
				while (k + 1 < row.size()
						&& slots.get(row.get(k + 1)).x - slots.get(row.get(k)).x == 18) {
					k++;
				}
				int len = k - runStart + 1;
				if (len >= 2) {
					int sx = slots.get(row.get(runStart)).x;
					String key = sx + ":" + len;
					Object[] prev = prevRow.get(key);
					if (prev != null && (Integer) prev[1] + 18 * (Integer) prev[3] == y) {
						prev[3] = (Integer) prev[3] + 1;                       // rows++
						((List<Integer>) prev[4]).addAll(row.subList(runStart, k + 1));
						curRow.put(key, prev);
					} else {
						Object[] blk = new Object[] { sx, y, len, 1,
							new java.util.ArrayList<>(row.subList(runStart, k + 1)) };
						blocks.add(blk);
						curRow.put(key, blk);
					}
				}
				k++;
			}
			prevRow = curRow;
		}

		// Hovered slot → плоский индекс внутри блока.
		Slot hovered = ((AbstractContainerScreenAccessor) (Object) this).liquidum$getHoveredSlot();

		for (Object[] blk : blocks) {
			List<Integer> idxs = (List<Integer>) blk[4];
			int hover = -1;
			for (int j = 0; j < idxs.size(); j++) {
				if (slots.get(idxs.get(j)) == hovered) { hover = j; break; }
			}
			LiquidGlassRenderer.submitGridWell(
				leftPos + (Integer) blk[0] - 1, topPos + (Integer) blk[1] - 1,
				18, 18, 18, 18, (Integer) blk[2], (Integer) blk[3], hover);
		}
		// Помечаем слоты, попавшие в wells (freeform не нужны).
		boolean[] used = new boolean[n];
		for (Object[] blk : blocks) {
			for (int idx : (List<Integer>) blk[4]) used[idx] = true;
		}
		for (int i = 0; i < n; i++) {
			if (!slots.get(i).isActive() || used[i]) continue;
			LiquidGlassRenderer.submitSlotWell(
				leftPos + slots.get(i).x - 1, topPos + slots.get(i).y - 1);
		}

		// ── Semantic adapter: Furnace (реальные имена 26.x Mojmap) ──
		// AbstractFurnaceMenu.getLitProgress()  → высота огня (flame spill)
		// AbstractFurnaceMenu.getBurnProgress() → заполнение ProcessChannel
		// INGREDIENT_SLOT / FUEL_SLOT / RESULT_SLOT → координаты FX.
		if (menu instanceof net.minecraft.world.inventory.AbstractFurnaceMenu fm) {
			try {
				Slot ing = fm.slots.get(net.minecraft.world.inventory.AbstractFurnaceMenu.INGREDIENT_SLOT);
				Slot fuel = fm.slots.get(net.minecraft.world.inventory.AbstractFurnaceMenu.FUEL_SLOT);
				Slot res = fm.slots.get(net.minecraft.world.inventory.AbstractFurnaceMenu.RESULT_SLOT);
				float flameX = leftPos + ing.x + 8f;
				float flameY = topPos + ing.y + 16f + Math.max(0, fuel.y - ing.y - 16) * 0.5f;
				float chX0 = leftPos + fuel.x + 16f + 6f;
				float chX1 = leftPos + res.x - 6f;
				float chY = topPos + res.y + 8f;
				LiquidGlassRenderer.setFurnaceFx(flameX, flameY, fm.getLitProgress(),
					chX0, chY, Math.max(8f, chX1 - chX0), fm.getBurnProgress());
			} catch (RuntimeException ignored) {
				// Нестандартный подкласс меню — FX просто не рисуется.
			}
		}

		// ── P0: selection/hover centres from REAL slot geometry ──
		// Drawn as a sharp foreground ring (replaces the cancelled vanilla
		// highlight) so it stays aligned with the replayed-sharp item, and as
		// red crosses for the geometry debug overlay.
		int hcx = -1, hcy = -1, scx = -1, scy = -1;
		if (hovered != null) {
			hcx = leftPos + hovered.x + 9;
			hcy = topPos + hovered.y + 9;
			// The "selected" highlight in containers tracks the hovered slot
			// (the slot under the cursor / being interacted with).
			scx = hcx; scy = hcy;
		}
		LiquidGlassRenderer.setSelectionHighlight(hcx, hcy, scx, scy);

		int[] centres = new int[slots.size() * 2];
		for (int i = 0; i < slots.size(); i++) {
			Slot s = slots.get(i);
			centres[i * 2] = leftPos + s.x + 9;
			centres[i * 2 + 1] = topPos + s.y + 9;
		}
		LiquidGlassRenderer.setDebugSlotCentres(centres);
	}

	/** Cancel vanilla 24×24 hover highlight sprites (§28-29): they are a second
	 *  square geometry (x-4,y-4,24×24) drawn at slot.x-4,y-4 with sprites
	 *  container/slot_highlight_back/front. Liquidum's Well hover is the same
	 *  Well becoming optically stronger — no second square. */
	@Inject(method = "extractSlotHighlightBack", at = @At("HEAD"), cancellable = true)
	private void liquidum$cancelHighlightBack(GuiGraphicsExtractor guiGraphics, CallbackInfo ci) {
		if (LiquidGlassRenderer.isEnabled() && LiquidGlassRenderer.replaceSlotTiles()) ci.cancel();
	}
	@Inject(method = "extractSlotHighlightFront", at = @At("HEAD"), cancellable = true)
	private void liquidum$cancelHighlightFront(GuiGraphicsExtractor guiGraphics, CallbackInfo ci) {
		if (LiquidGlassRenderer.isEnabled() && LiquidGlassRenderer.replaceSlotTiles()) ci.cancel();
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
		// Tab Stack: items DON'T move — the captured old frame slides away
		// above them (shader side). Only the cursor parallax applies here.
		// Smooth scroll: items still glide with the spring.
		int fy = y;
		float[] off = LiquidGlassRenderer.itemParallax(leftPos + x, topPos + y);
		if (off == null) {
			if (fy == y) {
				original.call(instance, stack, x, y, seed);
				return;
			}
			original.call(instance, stack, x, fy, seed);
			return;
		}
		original.call(instance, stack,
			x + Math.round(off[0]),
			fy + Math.round(off[1]), seed);
	}

	/**
	 * P0: the vanilla slot highlight is drawn in the BLURRED background stratum,
	 * so our glass refraction pushes it off the (sharp, replayed) item. Cancel
	 * it and let the sharp foreground ring (real slot geometry) take over.
	 */
}
