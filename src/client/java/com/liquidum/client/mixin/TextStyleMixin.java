package com.liquidum.client.mixin;

import com.liquidum.client.text.LiquidumTextStyle;
import com.liquidum.client.text.LiquidumTypography;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Централизованные стили текста Liquidum (§24–27).
 * Не заменяет Minecraft Font — treatment идёт поверх фактического glyph renderer
 * (resource packs сохраняются).
 */
@Mixin(GuiGraphicsExtractor.class)
public class TextStyleMixin {

	@Unique
	private static boolean liquidum$inTextStyle = false;

	@Inject(
		method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void liquidum$applyTextStyle(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow, CallbackInfo ci) {
		if (liquidum$inTextStyle) return;
		if ((color & 0xFF000000) == 0) return;
		LiquidumTextStyle style = LiquidumTypography.current();
		if (!LiquidumTypography.needsCustomShadow(style)) return;
		// В каждом блоке текст как в Recipe Book Search — один цвет/обводка
		// (иначе Inventory/Crafting #404040 без тени vs Search #707070 с тенью).
		// Для GLASS/MONOLITH форсим слабую тень даже при shadow==false.
		boolean needShadow = shadow || style == LiquidumTextStyle.GLASS || style == LiquidumTextStyle.MONOLITH;
		if (!needShadow) return;
		// Унифицируем цвет меток контейнеров (Inventory/Crafting/Chest/Furnace) с Search
		int textColor = color;
		var screen = net.minecraft.client.Minecraft.getInstance().gui.screen();
		if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen) {
			// Тёмный vanilla #404040 → светлый как у Search для читаемости на стекле
			if ((color & 0x00FFFFFF) == 0x00404040 || color == 4210752) {
				textColor = 0xFFE8E8E8;
			}
		}
		ci.cancel();
		liquidum$inTextStyle = true;
		try {
			GuiGraphicsExtractor self = (GuiGraphicsExtractor) (Object) this;
			int alpha = LiquidumTypography.shadowAlpha(style);
			int shadowColor = (alpha << 24);
			self.text(font, text, x + 1, y + 1, shadowColor, false);
			self.text(font, text, x, y, textColor, false);
		} finally {
			liquidum$inTextStyle = false;
		}
	}
}
