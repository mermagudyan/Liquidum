package com.liquidum.client.settings;

import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;

// Shared row dispatch: every vanilla value set maps to a custom row or worse
public final class OptionRows {
	private OptionRows() {
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	public static SettingRow make(OptionInstance<?> o, Options options) {
		try {
			Object v = o.get();
			if (v instanceof Boolean && o.values() instanceof OptionInstance.Enum) {
				return new ToggleRow(0, 0, 1, (OptionInstance<Boolean>) o);
			}
			if (o.values() instanceof OptionInstance.Enum e) {
				return new PopupRow(0, 0, 1, (OptionInstance) o, new ArrayList(e.values()));
			}
			// GUI scale always drops down, however wide its range is
			if (o == options.guiScale() && !(o.get() instanceof Boolean) && !(o.values() instanceof OptionInstance.Enum)
				&& o.values() instanceof OptionInstance.IntRangeBase gr) {
				java.util.List<Integer> vals = new ArrayList<>();
				for (int n = gr.minInclusive(); n <= gr.maxInclusive(); n++) vals.add(n);
				return new PopupRow<>(0, 0, 1, (OptionInstance<Integer>) (OptionInstance<?>) o, vals);
			}
			if ((o == options.guiScale() || o == options.inactivityFpsLimit() || o == options.musicFrequency())
				&& !(o.get() instanceof Boolean) && !(o.values() instanceof OptionInstance.Enum)
				&& o.values() instanceof OptionInstance.IntRange r && r.maxInclusive() - r.minInclusive() <= 16) {
				java.util.List<Integer> vals = new ArrayList<>();
				for (int n = r.minInclusive(); n <= r.maxInclusive(); n++) vals.add(n);
				return new PopupRow<>(0, 0, 1, (OptionInstance<Integer>) (OptionInstance<?>) o, vals);
			}
			if (o.values() instanceof OptionInstance.IntRange
				|| o.values() instanceof OptionInstance.SliderableValueSet) {
				return new SliderRow<>(0, 0, 1, (OptionInstance) o);
			}
		} catch (Exception ignored) {
		}
		return new VanillaRow(o, options);
	}

	// Fallback that cannot lose functionality: the vanilla widget itself
	public static final class VanillaRow extends SettingRow {
		private final AbstractWidget inner;

		VanillaRow(OptionInstance<?> o, Options options) {
			super(0, 0, 1, 20, captionOf(o));
			this.inner = o.createButton(options, 0, 0, 1);
			refreshTooltip(this, o);
		}

		@Override
		public void setX(int x) {
			super.setX(x);
			inner.setX(x);
		}

		@Override
		public void setY(int y) {
			super.setY(y);
			inner.setY(y);
		}

		@Override
		public void setWidth(int w) {
			super.setWidth(w);
			inner.setWidth(w);
		}

		@Override
		public void setFocused(boolean f) {
			super.setFocused(f);
			inner.setFocused(f);
		}

		@Override
		public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
			return inner.mouseClicked(event, doubleClick);
		}

		@Override
		public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) {
			return inner.mouseDragged(event, dx, dy);
		}

		@Override
		public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
			return inner.mouseReleased(event);
		}

		@Override
		protected void extractWidgetRenderState(net.minecraft.client.gui.GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
			inner.extractRenderState(g, mouseX, mouseY, delta);
		}
	}
}
