package com.liquidum.client.text;

import com.liquidum.client.config.LiquidumConfig;

/**
 * Центральное состояние типографики Liquidum.
 * Не прописывается в каждом Screen — стиль + роль резолвятся здесь.
 * Resource packs/fonts не ломаются: treatment идёт поверх фактического glyph renderer.
 */
public final class LiquidumTypography {

	private static LiquidumTextStyle current = LiquidumTextStyle.VANILLA;

	private LiquidumTypography() {}

	public static void applyConfig(LiquidumConfig c) {
		current = LiquidumTextStyle.fromConfig(c.textStyle);
	}

	public static LiquidumTextStyle current() {
		return current;
	}

	/** Разрешить стиль для конкретного контекста (пока глобальный, архитектура готова к per-role ветвлению). */
	public static LiquidumTextStyle resolve(LiquidumTextContext ctx) {
		// HUD всегда VANILLA для читаемости, если стиль GLASS — можно переопределить позже
		return current;
	}

	/** Shadow-альфа для данного стиля (0..255). */
	public static int shadowAlpha(LiquidumTextStyle style) {
		return switch (style) {
			case GLASS -> 0x40;      // очень слабый, без glow
			case MONOLITH -> 0xFF;   // плотный, строгий
			default -> 0xFF;         // vanilla — делегируем движку
		};
	}

	/** Нужен ли кастомный shadow вместо vanilla boolean. */
	public static boolean needsCustomShadow(LiquidumTextStyle style) {
		return style == LiquidumTextStyle.GLASS || style == LiquidumTextStyle.MONOLITH;
	}
}
