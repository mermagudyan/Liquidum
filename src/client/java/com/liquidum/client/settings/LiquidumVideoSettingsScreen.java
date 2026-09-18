package com.liquidum.client.settings;

import com.liquidum.client.shader.LiquidGlassRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import static com.liquidum.client.config.LiquidumLang.tr;

import java.util.ArrayList;
import java.util.List;

// Custom Video Settings: own Sodium-style layout over the same vanilla instances
public class LiquidumVideoSettingsScreen extends Screen {
	private static final int CARD_PAD = 10;
	private static final int CARD_GAP = 12;
	private static final int HEADER_H = 14;
	private static final int SECTION_GAP = 10;

	private final Screen parent;
	private final Options options;
	private final List<AbstractWidget> rows = new ArrayList<>();
	private final List<Integer> rowBaseY = new ArrayList<>();
	private final List<Section> sections = new ArrayList<>();
	private final List<Integer> sectionHeaders = new ArrayList<>();
	private final List<int[]> cards = new ArrayList<>();
	private Button doneButton;
	private SliderRow<?> anisotropyRow;
	private ResolutionRow resolutionRow;
	private boolean tooltipsHeld;
	private final PopupMenu popup = new PopupMenu();
	private float scrollPx;
	private int contentW;
	private int contentX0;
	private final com.mojang.blaze3d.platform.Window window;
	private final net.minecraft.client.renderer.GpuWarnlistManager warnlist;
	private final int oldMipmaps;
	private final int oldAnisotropyBit;
	private final net.minecraft.client.TextureFilteringMethod oldTextureFiltering;

	private record Section(String titleKey, List<SettingRow> widgets) {
	}

	public LiquidumVideoSettingsScreen(Screen parent) {
		super(Component.literal("Liquidum — " + tr("Видео")));
		this.parent = parent;
		Minecraft mc = Minecraft.getInstance();
		this.options = mc.options;
		this.window = mc.getWindow();
		this.warnlist = mc.getGpuWarnlistManager();
		this.oldMipmaps = options.mipmapLevels().get();
		this.oldAnisotropyBit = options.maxAnisotropyBit().get();
		this.oldTextureFiltering = options.textureFiltering().get();
		try {
			warnlist.resetWarnings();
		} catch (Exception ignored) {
		}
	}

	@Override
	protected void init() {
		clearWidgets();
		rows.clear();
		rowBaseY.clear();
		sections.clear();
		sectionHeaders.clear();
		cards.clear();
		anisotropyRow = null;
		resolutionRow = null;
		popup.close();
		tooltipsHeld = false;
		scrollPx = 0f;
		contentW = Math.min(360, width - 32);
		contentX0 = (width - contentW) / 2;
		LiquidGlassRenderer.setPendingGuiSize(width, height);
		int y = 40;
		y = buildSection(tr("Дисплей"), y,
			List.of(options.graphicsPreset(), options.framerateLimit(), options.enableVsync(),
				options.inactivityFpsLimit(), options.guiScale(), options.fullscreen(),
				options.exclusiveFullscreen(), options.gamma(), options.preferredGraphicsBackend()),
			true);
		y = buildSection(tr("Качество"), y,
			List.of(options.biomeBlendRadius(), options.renderDistance(), options.prioritizeChunkUpdates(),
				options.simulationDistance(), options.ambientOcclusion(), options.cloudStatus(),
				options.particles(), options.mipmapLevels(), options.entityShadows(),
				options.entityDistanceScaling(), options.menuBackgroundBlurriness(), options.cloudRange(),
				options.cutoutLeaves(), options.improvedTransparency(), options.textureFiltering(),
				options.maxAnisotropyBit(), options.weatherRadius()),
			false);
		y = buildSection(tr("Прочее"), y,
			List.of(options.showAutosaveIndicator(), options.vignette(), options.attackIndicator(),
				options.chunkSectionFadeInTime()),
			false);
		doneButton = Button.builder(Component.translatable("gui.done"), b -> onClose())
			.bounds(width / 2 - 100, height - 28, 200, 20).build();
		addRenderableWidget(doneButton);
		applyScroll();
	}

	private int buildSection(String title, int y, List<OptionInstance<?>> opts, boolean resolution) {
		y += SECTION_GAP;
		int headerY = y;
		y += HEADER_H + 4;
		int cardTop = y;
		List<SettingRow> made = new ArrayList<>();
		for (OptionInstance<?> o : opts) {
			SettingRow r = OptionRows.make(o, options);
			if (o == options.maxAnisotropyBit() && r instanceof SliderRow) anisotropyRow = (SliderRow<?>) r;
			if (r instanceof PopupRow) ((PopupRow<?>) r).opener = this::openPopup;
			made.add(r);
			y += r.getHeight();
		}
		if (resolution) {
			ResolutionRow rr = new ResolutionRow(0, 0, 1);
			rr.opener = this::openPopup;
			made.add(rr);
			resolutionRow = rr;
			y += rr.getHeight();
		}
		sections.add(new Section(title, made));
		sectionHeaders.add(headerY);
		int cardBottom = y;
		cards.add(new int[]{contentX0 - CARD_PAD, cardTop - CARD_PAD,
			contentW + CARD_PAD * 2, cardBottom - cardTop + CARD_PAD * 2});
		int x = contentX0;
		int ry = cardTop;
		for (SettingRow r : made) {
			r.setX(x);
			r.setY(ry);
			r.setWidth(contentW);
			addRenderableWidget(r);
			rows.add(r);
			rowBaseY.add(ry);
			ry += r.getHeight();
		}
		return cardBottom + CARD_GAP;
	}

	private int contentBottom() {
		int b = 40;
		for (int i = 0; i < rows.size(); i++) {
			b = Math.max(b, rowBaseY.get(i) + rows.get(i).getHeight());
		}
		return b + CARD_GAP;
	}

	private int viewTop() {
		return 36;
	}

	private int viewBottom() {
		return height - 36;
	}

	private float maxScroll() {
		return Math.max(0, contentBottom() - viewBottom());
	}

	private void applyScroll() {
		scrollPx = Math.max(0f, Math.min(scrollPx, maxScroll()));
		for (int i = 0; i < rows.size(); i++) {
			AbstractWidget w = rows.get(i);
			int liveY = rowBaseY.get(i) - (int) scrollPx;
			w.setY(liveY);
			w.visible = liveY >= 0 && liveY + w.getHeight() <= height;
		}
	}

	private int[] thumbRect() {
		float max = maxScroll();
		if (max <= 0) return null;
		int top = viewTop(), bottom = viewBottom();
		int th = Math.max(20, (bottom - top) * (bottom - top) / Math.max(1, (int) (contentBottom() - top)));
		int ty = top + (int) ((bottom - top - th) * (scrollPx / max));
		return new int[]{contentX0 + contentW + CARD_PAD + 2, ty, 4, th};
	}

	private boolean scrollDrag;
	private float scrollGrab;

	private void openPopup(PopupAnchor anchor) {
		for (AbstractWidget w : rows) w.setTooltipDelay(java.time.Duration.ofHours(1));
		tooltipsHeld = true;
		SettingRow row = (SettingRow) anchor;
		java.util.List<String> labels = anchor.popupLabels();
		int sel = anchor.popupSelected();
		int w = PopupRow.fitWidth(row.getWidth(), labels);
		int x = row.getX() + row.getWidth() - 8 - w;
		int[] vr = anchor.popupValueRect();
		popup.open(row, vr[0] + vr[2] / 2, vr[1] + vr[3] / 2, x, row.getY(), row.getY() + row.getHeight(), w, labels, sel, anchor::popupPick, viewTop(), viewBottom());
	}

	@Override
	public java.util.Optional<net.minecraft.client.gui.components.events.GuiEventListener> getChildAt(double x, double y) {
		for (AbstractWidget w : rows) {
			if (w.visible && w instanceof SliderRow s && s.trackHit(x, y)) return java.util.Optional.of(s);
		}
		return super.getChildAt(x, y);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		if (popup.isOpen()) {
			popup.close();
			return true;
		}
		if (maxScroll() > 0 && y >= viewTop() && y <= viewBottom()) {
			scrollPx = Math.max(0f, Math.min(scrollPx - (float) (dy * 20.0), maxScroll()));
			applyScroll();
			return true;
		}
		return super.mouseScrolled(x, y, dx, dy);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		LiquidGlassRenderer.setPendingGuiSize(width, height);
		popup.update();
		if (tooltipsHeld && !popup.isOpen()) {
			for (AbstractWidget w : rows) w.setTooltipDelay(java.time.Duration.ZERO);
			tooltipsHeld = false;
		}
		applyScroll();
		g.enableScissor(contentX0 - CARD_PAD, viewTop(), contentX0 + contentW + CARD_PAD, viewBottom());
		for (AbstractWidget w : rows) {
			if (w.visible) w.extractRenderState(g, mouseX, mouseY, delta);
		}
		g.disableScissor();
		if (doneButton != null) doneButton.extractRenderState(g, mouseX, mouseY, delta);
		for (int i = 0; i < cards.size(); i++) {
			int[] c = cards.get(i);
			int cy0 = c[1] - (int) scrollPx;
			int ch = c[3];
			if (cy0 + ch < viewTop() || cy0 > viewBottom()) continue;
			LiquidGlassRenderer.submitSpriteTile(c[0], cy0, c[2], ch, LiquidGlassRenderer.MAT_CARD, 0f, 100f + i);
		}
		String screenTitle = getTitle().getString();
		g.centeredText(font, screenTitle, width / 2, 14, 0xFFFFFFFF);
		LiquidGlassRenderer.submitCenteredCutout(font, screenTitle, width / 2, 14);
		for (int i = 0; i < sections.size() && i < sectionHeaders.size(); i++) {
			int hy = sectionHeaders.get(i) - (int) scrollPx;
			if (hy >= viewTop() - 20 && hy <= viewBottom()) {
				String title = sections.get(i).titleKey();
				g.text(font, title, contentX0, hy, 0xFFBBBBBB, false);
				LiquidGlassRenderer.submitTextCutout(font, title, contentX0, hy);
			}
		}
		if (options.isRestartRequiredToApplyVideoSettings()) {
			String rs = tr("Нужен рестарт");
			g.centeredText(font, rs, width / 2, height - 48, 0xFFFF8080);
			LiquidGlassRenderer.submitCenteredCutout(font, rs, width / 2, height - 48);
		}
		paintScrollBar(g);
		popup.draw(g, SettingRow.font(), mouseX, mouseY);
	}

	private void paintScrollBar(GuiGraphicsExtractor g) {
		int[] th = thumbRect();
		if (th == null) return;
		int top = viewTop(), bottom = viewBottom();
		g.fill(net.minecraft.client.renderer.RenderPipelines.GUI, th[0], top, th[0] + th[2], bottom, 0x44000000);
		g.fill(net.minecraft.client.renderer.RenderPipelines.GUI, th[0], th[1], th[0] + th[2], th[1] + th[3], 0x88FFFFFF);
	}

	@Override
	public void tick() {
		try {
			if (anisotropyRow != null) {
				anisotropyRow.active = options.textureFiltering().get()
					== net.minecraft.client.TextureFilteringMethod.ANISOTROPIC;
			}
		} catch (Exception ignored) {
		}
		try {
			if (resolutionRow != null) {
				resolutionRow.active = options.fullscreen().get();
			}
		} catch (Exception ignored) {
		}
		try {
			if (warnlist.isShowingWarning()) openWarning();
		} catch (Exception ignored) {
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (popup.click(event.x(), event.y())) return true;
		int[] th = thumbRect();
		if (th != null && event.x() >= th[0] && event.x() <= th[0] + th[2]
			&& event.y() >= th[1] && event.y() <= th[1] + th[3]) {
			scrollDrag = true;
			scrollGrab = (float) event.y() - th[1];
			return true;
		}
		boolean handled = super.mouseClicked(event, doubleClick);
		if (handled) {
			try {
				if (warnlist.isShowingWarning()) openWarning();
			} catch (Exception ignored) {
			}
		}
		return handled;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (scrollDrag) {
			int[] th = thumbRect();
			if (th != null) {
				float max = maxScroll();
				int span = viewBottom() - viewTop() - th[3];
				if (span > 0) {
					scrollPx = Math.max(0f, Math.min(((float) event.y() - scrollGrab - viewTop()) / span * max, max));
					applyScroll();
				}
			}
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		scrollDrag = false;
		return super.mouseReleased(event);
	}

	private void openWarning() {
		try {
			var msgs = new java.util.ArrayList<Component>();
			msgs.add(Component.translatable("options.graphics.warning.message"));
			msgs.add(CommonComponents.NEW_LINE);
			String r = warnlist.getRendererWarnings();
			if (r != null) {
				msgs.add(CommonComponents.NEW_LINE);
				msgs.add(Component.translatable("options.graphics.warning.renderer", r));
			}
			String v = warnlist.getVendorWarnings();
			if (v != null) {
				msgs.add(CommonComponents.NEW_LINE);
				msgs.add(Component.translatable("options.graphics.warning.vendor", v));
			}
			String ver = warnlist.getVersionWarnings();
			if (ver != null) {
				msgs.add(CommonComponents.NEW_LINE);
				msgs.add(Component.translatable("options.graphics.warning.version", ver));
			}
			var accept = new net.minecraft.client.gui.screens.options.UnsupportedGraphicsWarningScreen.ButtonOption(
				Component.translatable("options.graphics.warning.accept"), b -> {
					options.improvedTransparency().set(true);
					try {
						Minecraft.getInstance().levelExtractor.allChanged();
					} catch (Exception ignored) {
					}
					try {
						warnlist.dismissWarning();
					} catch (Exception ignored) {
					}
					Minecraft.getInstance().gui.setScreen(this);
				});
			var cancel = new net.minecraft.client.gui.screens.options.UnsupportedGraphicsWarningScreen.ButtonOption(
				Component.translatable("options.graphics.warning.cancel"), b -> {
					try {
						warnlist.dismissWarning();
					} catch (Exception ignored) {
					}
					options.improvedTransparency().set(false);
					Minecraft.getInstance().gui.setScreen(this);
				});
			Minecraft.getInstance().gui.setScreen(
				com.liquidum.client.mixin.WarningScreenInvoker.liquidum$create(
					Component.translatable("options.graphics.warning.title"), msgs,
					com.google.common.collect.ImmutableList.of(accept, cancel)));
		} catch (Exception ignored) {
		}
	}

	@Override
	public void onClose() {
		try {
			window.changeFullscreenVideoMode();
		} catch (Exception ignored) {
		}
		try {
			options.save();
		} catch (Exception ignored) {
		}
		if (minecraft != null) minecraft.gui.setScreen(parent);
	}

	@Override
	public void removed() {
		try {
			if (options.mipmapLevels().get() != oldMipmaps
				|| options.maxAnisotropyBit().get() != oldAnisotropyBit
				|| options.textureFiltering().get() != oldTextureFiltering) {
				Minecraft.getInstance().updateMaxMipLevel(options.mipmapLevels().get());
				Minecraft.getInstance().delayTextureReload();
			}
		} catch (Exception ignored) {
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
