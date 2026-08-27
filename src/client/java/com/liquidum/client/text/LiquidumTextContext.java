package com.liquidum.client.text;

/**
 * Роль текста, передаваемая в типографику (§27).
 * Центральный рендерер выбирает параметры на основе style + role.
 */
public enum LiquidumTextContext {
	TITLE,      // заголовки экранов
	LABEL,      // подписи слотов, "Inventory", "Crafting"
	BODY,       // описания, рецепты
	HUD,        // hearts / hunger / xp
	ACTIVE,     // выбранный/hover
	DISABLED,
	TOOLTIP
}
