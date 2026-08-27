package com.liquidum.client.text;

/**
 * Централизованные стили текста Liquidum (§27).
 * Применяются поверх фактически используемого Minecraft Font (resource packs сохраняются).
 */
public enum LiquidumTextStyle {
	VANILLA,
	GLASS,
	MONOLITH;

	public static LiquidumTextStyle fromConfig(String s) {
		if (s == null) return VANILLA;
		return switch (s.toLowerCase()) {
			case "glass" -> GLASS;
			case "monolith" -> MONOLITH;
			default -> VANILLA;
		};
	}
}
