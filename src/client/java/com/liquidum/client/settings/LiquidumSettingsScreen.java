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

import java.util.ArrayList;
import java.util.List;

import static com.liquidum.client.config.LiquidumLang.tr;

// Settings hub: tabbed Video/Sound/Controls/Liquidum over the same instances
public class LiquidumSettingsScreen extends Screen {
	private enum Tab { VIDEO, SOUND, CONTROLS, LIQUIDUM }

	private static final int CARD_PAD = 10;
	private static final int CARD_GAP = 12;
	private static final int HEADER_H = 14;
	private static final int SECTION_GAP = 10;

	private final Screen parent;
	private final Options options;
	private Tab tab = Tab.VIDEO;
	private final List<AbstractWidget> rows = new ArrayList<>();
	private final List<Integer> rowBaseY = new ArrayList<>();
	private final List<String> sections = new ArrayList<>();
	private final List<Integer> sectionHeaders = new ArrayList<>();
	private final List<int[]> cards = new ArrayList<>();
	private final List<Button> tabButtons = new ArrayList<>();
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

	public LiquidumSettingsScreen(Screen parent) {
		super(Component.literal("Liquidum — " + tr("Настройки")));
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

	private static String tabName(Tab t) {
		return switch (t) {
			case VIDEO -> tr("Видео");
			case SOUND -> tr("Звук");
			case CONTROLS -> tr("Управление");
			case LIQUIDUM -> "Liquidum";
		};
	}

	@Override
	protected void init() {
		clearWidgets();
		rows.clear();
		rowBaseY.clear();
		sections.clear();
		sectionHeaders.clear();
		cards.clear();
		tabButtons.clear();
		anisotropyRow = null;
		resolutionRow = null;
		tooltipsHeld = false;
		popup.close();
		scrollPx = 0f;
		contentW = Math.min(360, width - 32);
		contentX0 = (width - contentW) / 2;
		LiquidGlassRenderer.setPendingGuiSize(width, height);
		Button done = Button.builder(Component.translatable("gui.done"), b -> onClose())
			.bounds(width - contentX0 - 100, 10, 100, 20).build();
		addRenderableWidget(done);
		Button lang = Button.builder(Component.literal(com.liquidum.client.config.LiquidumLang.langCode()), b -> {
			com.liquidum.client.config.LiquidumLang.openLanguageMenu(this);
		}).bounds(width - contentX0 - 174, 10, 70, 20).build();
		addRenderableWidget(lang);
		int bw = (contentW - 3 * 4) / 4;
		int x = contentX0;
		for (Tab t : Tab.values()) {
			Tab tt = t;
			Button b = Button.builder(Component.literal((tab == tt ? "> " : "") + tabName(tt)), btn -> {
				tab = tt;
				scrollPx = 0f;
				init();
			}).bounds(x, 36, bw, 20).build();
			b.active = tab != tt;
			addRenderableWidget(b);
			tabButtons.add(b);
			x += bw + 4;
		}
		int y = 64;
		switch (tab) {
			case VIDEO -> y = buildVideo(y);
			case SOUND -> y = buildSound(y);
			case CONTROLS -> y = buildControls(y);
			case LIQUIDUM -> y = buildLiquidum(y);
		}
		applyScroll();
	}

	private int buildVideo(int y) {
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
		return buildSection(tr("Прочее"), y,
			List.of(options.showAutosaveIndicator(), options.vignette(), options.attackIndicator(),
				options.chunkSectionFadeInTime()),
			false);
	}

	private int buildSound(int y) {
		List<OptionInstance<?>> volumes = new ArrayList<>();
		volumes.add(options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER));
		for (net.minecraft.sounds.SoundSource s : net.minecraft.sounds.SoundSource.values()) {
			if (s == net.minecraft.sounds.SoundSource.MASTER) continue;
			try {
				volumes.add(options.getSoundSourceOptionInstance(s));
			} catch (Exception ignored) {
			}
		}
		y = buildSection(tr("Громкость"), y, volumes, false);
		return buildSection(tr("Прочее"), y,
			List.of(options.soundDevice(), options.showSubtitles(), options.directionalAudio(),
				options.musicFrequency(), options.musicToast()),
			false);
	}

	private int buildControls(int y) {
		y += SECTION_GAP;
		Button open = Button.builder(Component.literal(tr("Управление") + " >"), b -> {
			Minecraft.getInstance().gui.setScreen(
				new net.minecraft.client.gui.screens.options.controls.ControlsScreen(this, options));
		}).bounds(contentX0, y, contentW, 20).build();
		addRenderableWidget(open);
		rows.add(open);
		rowBaseY.add(y);
		return y + 20 + CARD_GAP;
	}

	private int buildLiquidum(int y) {
		var cfg = com.liquidum.client.LiquidumCore.getConfig();
		y += SECTION_GAP;
		int headerY = y;
		y += HEADER_H + 4;
		int cardTop = y;
		List<SettingRow> made = new ArrayList<>();
		made.add(new ConfigToggleRow(0, 0, 1, tr("Мод включён"), () -> cfg.enabled, v -> cfg.enabled = v, this::saveModConfig));
		made.add(new ConfigToggleRow(0, 0, 1, tr("Стекло на кнопках"), () -> cfg.buttonsGlass, v -> cfg.buttonsGlass = v, this::saveModConfig));
		made.add(new ConfigToggleRow(0, 0, 1, tr("Стекло на хотбаре"), () -> cfg.hotbarGlass, v -> cfg.hotbarGlass = v, this::saveModConfig));
		made.add(new ConfigToggleRow(0, 0, 1, tr("Стекло на слотах"), () -> cfg.containerGlass, v -> cfg.containerGlass = v, this::saveModConfig));
		made.add(new ConfigToggleRow(0, 0, 1, tr("Умный док"), () -> cfg.dockAdaptive, v -> cfg.dockAdaptive = v, this::saveModConfig));
		made.add(new MenuRow(0, 0, 1, tr("Язык"),
			com.liquidum.client.config.LiquidumLang::langCode,
			() -> com.liquidum.client.config.LiquidumLang.openLanguageMenu(this)));
		sections.add(tr("Покрытие"));
		sectionHeaders.add(headerY);
		int cardBottom = placeRows(made, cardTop);
		cards.add(new int[]{contentX0 - CARD_PAD, cardTop - CARD_PAD,
			contentW + CARD_PAD * 2, cardBottom - cardTop + CARD_PAD * 2});
		return cardBottom + CARD_GAP;
	}

	private void saveModConfig() {
		var cfg = com.liquidum.client.LiquidumCore.getConfig();
		cfg.save();
		LiquidGlassRenderer.applyConfig(cfg);
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
		sections.add(title);
		sectionHeaders.add(headerY);
		int cardBottom = y;
		cards.add(new int[]{contentX0 - CARD_PAD, cardTop - CARD_PAD,
			contentW + CARD_PAD * 2, cardBottom - cardTop + CARD_PAD * 2});
		return placeRows(made, cardTop) + CARD_GAP;
	}

	private int placeRows(List<? extends AbstractWidget> made, int cardTop) {
		int ry = cardTop;
		for (AbstractWidget r : made) {
			r.setX(contentX0);
			r.setY(ry);
			r.setWidth(contentW);
			addRenderableWidget(r);
			rows.add(r);
			rowBaseY.add(ry);
			ry += r.getHeight();
		}
		return ry;
	}

	private int contentBottom() {
		int b = 64;
		for (int i = 0; i < rows.size(); i++) {
			b = Math.max(b, rowBaseY.get(i) + rows.get(i).getHeight());
		}
		return b + CARD_GAP;
	}

	private int viewTop() {
		return 64;
	}

	private int viewBottom() {
		return height - 16;
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
		for (net.minecraft.client.gui.components.events.GuiEventListener w : children()) {
			if (w instanceof AbstractWidget aw && !rows.contains(aw)) aw.extractRenderState(g, mouseX, mouseY, delta);
		}
		for (int i = 0; i < cards.size(); i++) {
			int[] c = cards.get(i);
			int cy0 = c[1] - (int) scrollPx;
			if (cy0 + c[3] < viewTop() || cy0 > viewBottom()) continue;
			LiquidGlassRenderer.submitSpriteTile(c[0], cy0, c[2], c[3], LiquidGlassRenderer.MAT_CARD, 0f, 100f + i);
		}
		String screenTitle = getTitle().getString();
		g.centeredText(font, screenTitle, contentX0, 14, 0xFFFFFFFF);
		LiquidGlassRenderer.submitCenteredCutout(font, screenTitle, contentX0, 14);
		for (int i = 0; i < sections.size() && i < sectionHeaders.size(); i++) {
			int hy = sectionHeaders.get(i) - (int) scrollPx;
			if (hy >= viewTop() - 20 && hy <= viewBottom()) {
				String title = sections.get(i);
				g.text(font, title, contentX0, hy, 0xFFBBBBBB, false);
				LiquidGlassRenderer.submitTextCutout(font, title, contentX0, hy);
			}
		}
		if (tab == Tab.VIDEO && options.isRestartRequiredToApplyVideoSettings()) {
			String needRestart = tr("Нужен рестарт");
			g.centeredText(font, needRestart, width / 2, height - 14, 0xFFFF8080);
			LiquidGlassRenderer.submitCenteredCutout(font, needRestart, width / 2, height - 14);
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
	public java.util.Optional<net.minecraft.client.gui.components.events.GuiEventListener> getChildAt(double x, double y) {
		for (AbstractWidget w : rows) {
			if (w.visible && w instanceof SliderRow s && s.trackHit(x, y)) return java.util.Optional.of(s);
		}
		return super.getChildAt(x, y);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (popup.click(event.x(), event.y())) return true;		int[] th = thumbRect();
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
