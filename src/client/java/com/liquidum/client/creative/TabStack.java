package com.liquidum.client.creative;

import net.minecraft.world.item.CreativeModeTab;
import java.util.List;

/**
 * Creative Tab Stack (§13–16).
 * Почему был выключен: старая реализация захватывала старый кадр в uiprev и слайдила
 * его поверх новой сетки, но конфликтовала со скролл-пружиной (тилы и предметы рассинхронились).
 * Новая версия отделена от скролла: переключение вкладки — это смена группы GridWell,
 * а анимация — интерполируемый offset в UBO uAnim (без захвата кадра), что не трогает скролл.
 * Учитывает модовые вкладки динамически, overflow через скролл/сжатие, hitbox остаётся.
 */
public final class TabStack {
	private TabStack(){}

	/** Динамический список категорий (vanilla + модовые). */
	public static List<CreativeModeTab> getTabs() {
		try {
			var mc = net.minecraft.client.Minecraft.getInstance();
			if (mc.player == null) return List.of();
			// CreativeModeTabs registry is dynamic; use player's creative tabs via ItemPickerMenu
			return List.of(); // TODO: resolve via CreativeModeTab registry lookup
		} catch (Exception e) { return List.of(); }
	}

	/** Hitbox остаётся vanilla (widget bounds), визуальная плитка уже компактнее (inset 3px). */
	public static boolean isStackEnabled() {
		try { return com.liquidum.client.LiquidumCore.getConfig().tabStackEnabled; } catch(Exception e){ return false; }
	}
}
