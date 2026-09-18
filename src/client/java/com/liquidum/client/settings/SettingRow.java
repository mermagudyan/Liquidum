package com.liquidum.client.settings;

import com.liquidum.client.mixin.OptionInstanceAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

// One settings row bound to a vanilla option, custom iOS-style visuals
public abstract class SettingRow extends AbstractWidget {
	protected SettingRow(int x, int y, int w, int h, Component caption) {
		super(x, y, w, h, caption);
	}

	public static int withAlpha(int argb, float f) {
		if (f >= 0.999f) return argb;
		if (f <= 0.001f) return argb & 0xFFFFFF;
		int a = Math.round(((argb >>> 24) & 0xFF) * f);
		return (a << 24) | (argb & 0xFFFFFF);
	}

	protected void clickSound() {
		try {
			playDownSound(Minecraft.getInstance().getSoundManager());
		} catch (Exception ignored) {
		}
	}

	@Override
	protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}

	@SuppressWarnings("unchecked")
	protected static <T> Component captionOf(OptionInstance<T> o) {
		try {
			return ((OptionInstanceAccessor<T>) (Object) o).liquidum$getCaption();
		} catch (Exception e) {
			return Component.empty();
		}
	}

	@SuppressWarnings("unchecked")
	protected static <T> Component valueTextOf(OptionInstance<T> o) {
		T v;
		try {
			v = o.get();
		} catch (Exception e) {
			return Component.empty();
		}
		return valueTextFor(o, v);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	protected static Component valueTextForRaw(OptionInstance o, Object v) {
		try {
			Object r = ((OptionInstanceAccessor) (Object) o).liquidum$getValueText().apply(v);
			if (r instanceof Component c) return c;
			return Component.literal(String.valueOf(v));
		} catch (Exception e) {
			return Component.literal(String.valueOf(v));
		}
	}

	@SuppressWarnings("unchecked")
	protected static <T> Component valueTextFor(OptionInstance<T> o, T v) {
		try {
			return ((OptionInstanceAccessor<T>) (Object) o).liquidum$getValueText().apply(v);
		} catch (Exception e) {
			return Component.literal(String.valueOf(v));
		}
	}

	protected static <T> void refreshTooltip(AbstractWidget w, OptionInstance<T> o) {
		try {
			@SuppressWarnings("unchecked")
			Tooltip t = ((OptionInstanceAccessor<T>) (Object) o).liquidum$getTooltipSupplier().apply(o.get());
			if (t != null) w.setTooltip(t);
		} catch (Exception ignored) {
		}
	}

	protected static net.minecraft.client.gui.Font font() {
		return Minecraft.getInstance().font;
	}
}
