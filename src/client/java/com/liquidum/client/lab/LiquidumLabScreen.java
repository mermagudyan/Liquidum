package com.liquidum.client.lab;

import com.liquidum.client.LiquidumCore;
import com.liquidum.client.compat.LiquidumOptOut;
import com.liquidum.client.config.LiquidumLang;
import com.liquidum.client.config.LiquidumProfiles;
import com.liquidum.client.debug.LiquidumDebugState;
import com.liquidum.client.shader.LiquidGlassRenderer;
import com.liquidum.client.config.LiquidumPaths;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import static com.liquidum.client.config.LiquidumLang.tr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

// Unified Lab: settings tabs on the docked side, live PreviewViewport beside
public class LiquidumLabScreen extends Screen {
	private enum Tab { DEMO, FX, MAT, PROF, ADV, DEBUG }

	private static final int ROW_H = 22;
	private static final String[] SCENES = {"Стопка", "Вразброс", "Вложенность", "Пузыри"};
	private static final String[] BACKDROP_NAMES = {"трава", "снег", "ад", "край", "решётка", "ночь", "песок", "мята", "слива", "свой", "фото"};
	private static final int[] BACKDROP_COLORS = {0xFF5B7C4A, 0xFFECECEC, 0xFF2A0A0A, 0xFF1A1A2A, 0xFF4A5A44, 0xFF101018, 0xFFD8C49A, 0xFF9AD8B4, 0xFF5A2A5A};
	private static final int GRID_INDEX = 4;
	private static final int CUSTOM_INDEX = 9;
	private static final int PHOTO_INDEX = 10;
	private static final float SIZE_MIN = 16f;
	private static final float SIZE_MAX = 512f;
	private static final String[] MAT_NAMES = {"Авто", "Панель", "Кнопка", "Слот", "Светлая", "Плотная"};
	private static final int[] MAT_VALUES = {-1, 0, 3, 1, 4, 6};

	private final Screen parent;
	private final List<DemoElement> elements = new ArrayList<>();
	private DemoElement selected;
	private final java.util.Set<DemoElement> multi = new java.util.LinkedHashSet<>();
	private DemoElement dragging;
	private float dragOffX;
	private float dragOffY;
	private LabLayout.Mode mode = LabLayout.Mode.PREVIEW;
	private float transition = 1f;
	private int backdrop = 0;
	private String customHex = "5B7C4A";
	private boolean flatBackdrop = false;
	private boolean linkedCorner = true;
	private int photoFit;
	private String photoName;
	private final java.util.List<Integer> colorHistory = new java.util.ArrayList<>();
	private net.minecraft.client.gui.components.EditBox textField;
	private static final String[] FIT_NAMES = {"Cover", "Fit", "Stretch", "1:1"};
	private static final java.util.List<String> FIG_NAMES = java.util.Arrays.asList(
		"Панель", "Кнопка", "Ячейка", "Аватар", "Квадрат", "Резкий", "Треугольник", "Круг", "Прямоугольник");
	private LabSceneStore.Snap snapA = new LabSceneStore.Snap();
	private LabSceneStore.Snap snapB = new LabSceneStore.Snap();
	private boolean dockRight = false;
	private int scenePreset = 0;
	private int addType = 0;
	private Tab tab = Tab.DEMO;
	private final List<AbstractWidget> contentWidgets = new ArrayList<>();
	private final List<Integer> contentDX = new ArrayList<>();
	private final List<Integer> contentDY = new ArrayList<>();
	private final List<AbstractWidget> tabWidgets = new ArrayList<>();
	private final List<Integer> tabDX = new ArrayList<>();
	private final List<AbstractWidget> barWidgets = new ArrayList<>();
	private int contentX = 12;
	private static final int HEADER_BOTTOM = 32;
	private float scrollPx;
	private boolean scrollDrag;
	private float scrollGrab;
	private int lastCulledLog = -1;
	private long lastScrollNanos;
	private boolean dirty;
	private DropListWidget openDrop;	private final java.util.Deque<SceneSnapshot> undoStack = new java.util.ArrayDeque<>();
	private final java.util.Deque<SceneSnapshot> redoStack = new java.util.ArrayDeque<>();
	private static final int UNDO_CAP = 50;
	private SceneSnapshot dragSnapshot;
	private float dragStartX;
	private float dragStartY;
	private long lastTextUndoNanos;

	public LiquidumLabScreen(Screen parent) {
		super(Component.literal("Liquidum Lab"));
		this.parent = parent;
		// Lab buttons stay vanilla, only demo objects go through glass
		LiquidumOptOut.optOut(LiquidumLabScreen.class);
		restoreScene();
	}

	private void restoreScene() {
		LabSceneStore.Data d = LabSceneStore.load();
		backdrop = Math.max(0, Math.min(BACKDROP_NAMES.length - 1, d.backdrop));
		customHex = d.customHex == null || d.customHex.isEmpty() ? "5B7C4A" : d.customHex;
		flatBackdrop = d.flatBackdrop;
		linkedCorner = d.linkedCorner;
		photoFit = d.photoFit >= 0 && d.photoFit < FIT_NAMES.length ? d.photoFit : 0;
		photoName = d.photoName;
		if (d.snapA != null) {
			snapA = d.snapA;
		}
		if (d.snapB != null) {
			snapB = d.snapB;
		}
		colorHistory.clear();
		if (d.history != null) {
			for (String hs : d.history) {
				try {
					colorHistory.add(0xFF000000 | (int) Long.parseLong(hs, 16));
				} catch (Exception ignored) {
				}
				if (colorHistory.size() >= 5) {
					break;
				}
			}
		}
		dockRight = d.dockRight;
		scenePreset = Math.max(0, Math.min(SCENES.length - 1, d.scenePreset));
		for (LabSceneStore.Elem e : d.elements) {
			DemoElement el = new DemoElement(LabSceneStore.parseType(e.type), e.x, e.y,
				Math.max(18, e.w), Math.max(18, e.h), e.zOrder, e.elevation);
			el.text = e.text == null ? "" : e.text;
			el.mat = e.mat;
			el.corner = e.corner;
			el.fuseGroup = e.fuseGroup;
			el.merge = e.merge;
			el.placed = true;
			elements.add(el);
		}
		if (elements.isEmpty()) {
			scenePreset = 1;
			elements.add(new DemoElement(DemoElement.Type.PANEL, 0, 0, 220, 120, 0, 1f));
			elements.add(new DemoElement(DemoElement.Type.PILL, 0, 0, 140, 28, 1, 2f));
			elements.add(new DemoElement(DemoElement.Type.ROUND, 0, 0, 44, 44, 2, 3f));
			elements.get(1).text = "Liquid glass preview";
		}
	}

	@Override
	protected void init() {
		clearWidgets();
		textField = null;
		contentWidgets.clear();
		contentDX.clear();
		contentDY.clear();
		tabWidgets.clear();
		tabDX.clear();
		barWidgets.clear();
		LabLayout layout = new LabLayout(width, height, mode, transition, dockRight);
		int lx = layout.leftX();
		int lw = layout.leftW();
		int y = 12;

		y = buildTabs(lx, y, lw);
		int tabSplit = children().size();
		y += 14;
		switch (tab) {
			case DEMO -> y = buildDemoTab(lx, y, lw);
			case FX -> y = buildFxTab(lx, y, lw);
			case MAT -> y = buildMatTab(lx, y, lw);
			case PROF -> y = buildProfTab(lx, y, lw);
			case ADV -> y = buildAdvTab(lx, y, lw);
			case DEBUG -> y = buildDebugTab(lx, y, lw);
		}
		int split = children().size();
		buildBottomBar();
		int barSplit = children().size();
		for (int i = 0; i < tabSplit && i < children().size(); i++) {
			if (children().get(i) instanceof AbstractWidget w) {
				tabWidgets.add(w);
				tabDX.add(w.getX() - lx);
			}
		}
		for (int i = tabSplit; i < split && i < children().size(); i++) {
			if (children().get(i) instanceof AbstractWidget w) {
				contentWidgets.add(w);
				contentDX.add(w.getX() - lx);
				contentDY.add(w.getY());
			}
		}
		// Bottom bar renders manually above the mask, so it leaves auto render
		for (int i = split; i < barSplit && i < children().size(); i++) {
			if (children().get(i) instanceof AbstractWidget w) {
				barWidgets.add(w);
			}
		}
		for (AbstractWidget w : barWidgets) {
			removeWidget(w);
			addWidget(w);
		}
		contentX = lx;
		applyScroll();
	}

	private int buildTabs(int lx, int y, int lw) {
		var tabs = visibleTabs();
		int bw = (lw - (tabs.size() - 1) * 2) / tabs.size();
		int x = lx;
		for (Tab tt : tabs) {
			Button b = Button.builder(Component.literal(tabName(tt)), btn -> {
				tab = tt;
				scrollPx = 0f;
				lastScrollNanos = System.nanoTime();
				init();
			}).bounds(x, y, bw, 20).build();
			b.active = tab != tt;
			addWidget(b);
			x += bw + 2;
		}
		return y + ROW_H;
	}

	// Debug tab needs the JVM flag, regular players only see design tabs
	private static java.util.List<Tab> visibleTabs() {
		var out = new java.util.ArrayList<Tab>();
		for (Tab t : Tab.values()) {
			if (t == Tab.DEBUG && !LiquidumDebugState.DEBUG_BUILD) {
				continue;
			}
			out.add(t);
		}
		return out;
	}

	private static String tabName(Tab t) {
		return switch (t) {
			case DEMO -> tr("Демо");
			case FX -> tr("Эффект");
			case MAT -> tr("Стекло");
			case PROF -> tr("Стиль");
			case ADV -> tr("Ещё");
			case DEBUG -> tr("Отладка");
		};
	}

	private static String tabHint(Tab t) {
		return switch (t) {
			case DEMO -> tr("Тягай стекло мышью прямо в окне");
			case FX -> tr("Слои стекла: что включено");
			case MAT -> tr("Кнопки - / + меняют стекло сразу");
			case PROF -> tr("Готовые характеры стекла");
			case ADV -> tr("Что в игре покрывать стеклом");
			case DEBUG -> tr("Инженерный режим: каналы, слои, статусы");
		};
	}

	private int buildDemoTab(int lx, int y, int lw) {
		addRenderableWidget(Button.builder(Component.literal(tr("Просмотр") + ": " + (mode == LabLayout.Mode.PREVIEW ? tr("показан") : tr("скрыт"))), b -> {
			mode = mode == LabLayout.Mode.PREVIEW ? LabLayout.Mode.NORMAL : LabLayout.Mode.PREVIEW;
			init();
		}).bounds(lx, y, lw, 20).build());
		y += ROW_H;
		addRenderableWidget(Button.builder(Component.literal(tr("Панель") + ": " + (dockRight ? tr("справа") : tr("слева"))), b -> {
			dockRight = !dockRight;
			init();
		}).bounds(lx, y, lw, 20).build());
		y += ROW_H;
		y = addDropRow(lx, y, lw, tr("Фон"), LiquidumLang.trList(BACKDROP_NAMES), backdrop, idx -> {
			backdrop = idx;
			if (backdrop == PHOTO_INDEX) {
				LabPhoto.reload(photoName);
				photoName = LabPhoto.currentName();
			}
		}, true);
		addRenderableWidget(Button.builder(Component.literal(tr("Фактура") + ": " + (flatBackdrop ? tr("плоско") : tr("сетка"))), b -> {
			pushUndo();
			flatBackdrop = !flatBackdrop;
			b.setMessage(Component.literal(tr("Фактура") + ": " + (flatBackdrop ? tr("плоско") : tr("сетка"))));
		}).bounds(lx, y, lw, 20).build());
		y += ROW_H;
		if (isCustomBackdrop()) {
			HexWheelWidget wheel = new HexWheelWidget(lx, y, customBackdropColor(), font, colorHistory, c -> {
				customHex = String.format(java.util.Locale.ROOT, "%06X", c & 0xFFFFFF);
				dirty = true;
			});
			addRenderableWidget(wheel);
			y += HexWheelWidget.HEIGHT + 4;
			addRenderableWidget(Button.builder(Component.literal(tr("Копия HEX")), b -> {
				if (minecraft != null) {
					minecraft.keyboardHandler.setClipboard("#" + String.format(java.util.Locale.ROOT, "%06X", customBackdropColor() & 0xFFFFFF));
				}
				b.setMessage(Component.literal(tr("Скопировано")));
			}).bounds(lx, y, lw, 20).build());
			y += ROW_H;
		}
		if (backdrop == PHOTO_INDEX) {
			var photoFiles = LabPhoto.list();
			if (!photoFiles.isEmpty()) {
				int cur = Math.max(0, photoFiles.indexOf(photoName));
				y = addDropRow(lx, y, lw, tr("Файл"), photoFiles, cur, idx -> {
					photoName = photoFiles.get(idx);
					LabPhoto.reload(photoName);
				}, true);
			}
			y = addDropRow(lx, y, lw, tr("Заполнение"), java.util.Arrays.asList(FIT_NAMES), photoFit, idx -> {
				photoFit = idx;
			}, true);
			addRenderableWidget(Button.builder(Component.literal(tr("Обновить фото")), b -> {
				LabPhoto.reload(photoName);
				photoName = LabPhoto.currentName();
				init();
			}).bounds(lx, y, lw, 20).build());
			y += ROW_H;
			addRenderableWidget(Button.builder(Component.literal(tr("Открыть папку")), b -> {
				openPhotosDir();
			}).bounds(lx, y, lw, 20).build());
			y += ROW_H;
			addRenderableWidget(new LabLabel(lx, y, lw, LabPhoto.status(), font, 0xFFDDDDDD));
			y += ROW_H;
		}
		int perRow = 3;
		for (int r = 0; r * perRow < SCENES.length; r++) {
			int n = Math.min(perRow, SCENES.length - r * perRow);
			int bw3 = (lw - (n - 1) * 2) / n;
			for (int i = 0; i < n; i++) {
				int idx = r * perRow + i;
				Button sb = Button.builder(Component.literal(tr(SCENES[idx])), b -> {
					pushUndo();
					scenePreset = idx;
					applyScenePreset();
					init();
				}).bounds(lx + i * (bw3 + 2), y, bw3, 20).build();
				sb.active = scenePreset != idx;
				addRenderableWidget(sb);
			}
			y += ROW_H;
		}
		if (selected == null) {
			addRenderableWidget(new LabLabel(lx, y, lw, tr("Кликни по стеклу, чтобы выбрать"), font, 0xFFDDDDDD));
			y += ROW_H;
		} else {
			boolean over = selected.mat >= 0 || selected.corner >= 0f
				|| selected.fuseGroup >= 0 || !selected.merge;
			addRenderableWidget(new LabLabel(lx, y, lw, typeName(selected) + (over ? " ●" : "") + " · " + Math.round(selected.w) + "x" + Math.round(selected.h), font, 0xFFFFFFFF));
			y += ROW_H;
			y = addDropRow(lx, y, lw, tr("Фигура"), LiquidumLang.trList(FIG_NAMES), figIndex(selected), idx -> {
				applyFigurePreset(selected, idx);
			}, true);
			addRenderableWidget(Button.builder(Component.literal(tr("Скругление") + ": " + (linkedCorner ? tr("из темы") : tr("своё"))), b -> {
				pushUndo();
				linkedCorner = !linkedCorner;
				b.setMessage(Component.literal(tr("Скругление") + ": " + (linkedCorner ? tr("из темы") : tr("своё"))));
				init();
			}).bounds(lx, y, lw, 20).build());
			y += ROW_H;
			if (!linkedCorner) {
				y = addCornerRow(lx, y, lw);
				String[] presetNames = {tr("Остро"), tr("Мягко"), tr("Кругло"), tr("Круг")};
				float[] presetVals = {0f, 0.35f, 0.65f, 1f};
				int pw = (lw - 6) / 4;
				for (int i = 0; i < 4; i++) {
					float cv = presetVals[i];
					addRenderableWidget(Button.builder(Component.literal(presetNames[i]), b -> {
						pushUndo();
						selected.corner = cv;
						init();
					}).bounds(lx + i * (pw + 2), y, pw, 20).build());
				}
				y += ROW_H;
			}
			y = addNumericRow(lx, y, lw, tr("Ширина"), () -> selected.w, v -> {
				pushUndo();
				selected.w = v;
				clampToViewport(selected);
			}, SIZE_MIN, SIZE_MAX, 8f, 140f);
			y = addNumericRow(lx, y, lw, tr("Высота"), () -> selected.h, v -> {
				pushUndo();
				selected.h = v;
				clampToViewport(selected);
			}, SIZE_MIN, SIZE_MAX, 8f, 60f);
			addRenderableWidget(Button.builder(Component.literal(tr("Квадрат 1:1")), b -> {
				pushUndo();
				float side = clamp(selected.w, SIZE_MIN, SIZE_MAX);
				selected.w = side;
				selected.h = side;
				clampToViewport(selected);
				init();
			}).bounds(lx, y, lw, 20).build());
			y += ROW_H;
			y = addDropRow(lx, y, lw, tr("Материал"), LiquidumLang.trList(MAT_NAMES), matIndex(selected.mat), idx -> {
				selected.mat = MAT_VALUES[idx];
			}, true);
			if (selected.mat >= 0 || selected.corner >= 0f || selected.fuseGroup >= 0 || !selected.merge) {
				addRenderableWidget(Button.builder(Component.literal(tr("Сбросить переопределения")), b -> {
					pushUndo();
					selected.mat = -1;
					selected.corner = -1;
					selected.fuseGroup = -1;
					selected.merge = true;
					init();
				}).bounds(lx, y, lw, 20).build());
				y += ROW_H;
			}
			textField = new net.minecraft.client.gui.components.EditBox(font, lx, y, lw, 20, Component.literal(tr("Текст")));
			textField.setMaxLength(64);
			textField.setHint(Component.literal(tr("Текст фигуры")));
			textField.setValue(selected.text == null ? "" : selected.text);
			textField.setResponder(v -> {
				if (!v.equals(selected.text)) {
					long now = System.nanoTime();
					if (now - lastTextUndoNanos > 600_000_000L) {
						pushUndo();
						lastTextUndoNanos = now;
					}
					selected.text = v;
					dirty = true;
				}
			});
			addRenderableWidget(textField);
			y += ROW_H;
			y = addToggleRow(lx, y, lw, tr("Видна"), () -> selected.visible, v -> {
				pushUndo();
				selected.visible = v;
			});
			addRenderableWidget(Button.builder(Component.literal(tr("Высота") + " - " + fmt(selected.elevation)), b -> {
				pushUndo();
				selected.elevation = Math.max(0f, selected.elevation - 0.5f);
				b.setMessage(Component.literal(tr("Высота") + " - " + fmt(selected.elevation)));
			}).bounds(lx, y, lw / 2 - 2, 20).build());
			addRenderableWidget(Button.builder(Component.literal(tr("Высота") + " + " + fmt(selected.elevation)), b -> {
				pushUndo();
				selected.elevation = Math.min(6f, selected.elevation + 0.5f);
				init();
			}).bounds(lx + lw / 2 + 2, y, lw - lw / 2 - 2, 20).build());
			y += ROW_H;
			addRenderableWidget(Button.builder(Component.literal(tr("Слой") + " - " + selected.zOrder), b -> {
				pushUndo();
				selected.zOrder = Math.max(0, selected.zOrder - 1);
				init();
			}).bounds(lx, y, lw / 2 - 2, 20).build());
			addRenderableWidget(Button.builder(Component.literal(tr("Слой") + " + " + selected.zOrder), b -> {
				pushUndo();
				selected.zOrder = Math.min(99, selected.zOrder + 1);
				init();
			}).bounds(lx + lw / 2 + 2, y, lw - lw / 2 - 2, 20).build());
			y += ROW_H;
			y = addDropRow(lx, y, lw, tr("Группа"), java.util.Arrays.asList(tr("Авто"), "0", "1", "2", "3"),
				selected.fuseGroup + 1, idx -> {
					selected.fuseGroup = idx - 1;
				}, true);
			y = addToggleRow(lx, y, lw, tr("Объединять"), () -> selected.merge, v -> {
				pushUndo();
				selected.merge = v;
			});
		}
		return y + ROW_H;
	}

	private int buildDebugTab(int lx, int y, int lw) {
		var channels = new java.util.ArrayList<String>();
		for (int i = 0; i < 12; i++) {
			channels.add(LiquidumDebugState.soloNameOf(i));
		}
		y = addDropRow(lx, y, lw, tr("Вид"), channels, LiquidumDebugState.soloStage, idx -> {
			LiquidumDebugState.soloStage = idx;
		}, false);
		addRenderableWidget(Button.builder(Component.literal(tr("Выбрать следующий")), b -> {
			selectNext();
			init();
		}).bounds(lx, y, lw, 20).build());
		y += ROW_H;
		addRenderableWidget(Button.builder(Component.literal(tr("Старый дебаг")), b -> {
			if (minecraft != null) {
				minecraft.gui.setScreen(new com.liquidum.client.debug.LiquidumDebugScreen());
			}
		}).bounds(lx, y, lw, 20).build());
		y += ROW_H;
		addRenderableWidget(Button.builder(Component.literal(tr("Стекло") + ": " + LiquidGlassRenderer.labStatus()), b -> {
			init();
		}).bounds(lx, y, lw, 20).build());
		y += ROW_H;
		addRenderableWidget(Button.builder(Component.literal(tr("Диагностика в лог")), b -> {
			LiquidumDebugState.dump();
			LiquidGlassRenderer.dumpDiagnostics();
		}).bounds(lx, y, lw, 20).build());
		y += ROW_H;
		y = addToggleRow(lx, y, lw, tr("Падение при ошибке"), () -> LiquidumDebugState.crashOnError, v -> LiquidumDebugState.crashOnError = v);
		y = addToggleRow(lx, y, lw, tr("Геометрия слотов"), () -> LiquidumDebugState.debugGeometry, v -> LiquidumDebugState.debugGeometry = v);
		y = addSectionRow(lx, y, lw, tr("Эксперимент"));
		y = addToggleRow(lx, y, lw, tr("Слипание плиток"), () -> LiquidumDebugState.fusion, v -> LiquidumDebugState.fusion = v);
		y = addNumericRow(lx, y, lw, tr("Радиус слипания"), () -> LiquidumDebugState.fusionRadius, v -> LiquidumDebugState.fusionRadius = v, 0f, 30f, 1f, 12f, () -> LiquidumDebugState.fusion);
		y = addNumericRow(lx, y, lw, tr("Купол (игнор)"), () -> LiquidumDebugState.domeHeight, v -> LiquidumDebugState.domeHeight = v, 0f, 1.5f, 0.1f, 1f);
		y = addNumericRow(lx, y, lw, tr("Солнечный блик"), () -> LiquidumDebugState.sunSpec, v -> LiquidumDebugState.sunSpec = v, 0f, 2f, 0.1f, 1f);
		var cfg = LiquidumCore.getConfig();
		y = addToggleRow(lx, y, lw, tr("Ореол: сердца"), () -> cfg.healthGlass, v -> {
			cfg.healthGlass = v;
			cfg.save();
			LiquidGlassRenderer.applyConfig(cfg);
		});
		y = addToggleRow(lx, y, lw, tr("Ореол: голод"), () -> cfg.hungerGlass, v -> {
			cfg.hungerGlass = v;
			cfg.save();
			LiquidGlassRenderer.applyConfig(cfg);
		});
		y = addToggleRow(lx, y, lw, tr("Ореол: броня"), () -> cfg.armorGlass, v -> {
			cfg.armorGlass = v;
			cfg.save();
			LiquidGlassRenderer.applyConfig(cfg);
		});
		y = addToggleRow(lx, y, lw, tr("Ореол: воздух"), () -> cfg.airGlass, v -> {
			cfg.airGlass = v;
			cfg.save();
			LiquidGlassRenderer.applyConfig(cfg);
		});
		y = addToggleRow(lx, y, lw, tr("Ореол: XP-полоса"), () -> cfg.xpBarGlass, v -> {
			cfg.xpBarGlass = v;
			cfg.save();
			LiquidGlassRenderer.applyConfig(cfg);
		});
		y = addToggleRow(lx, y, lw, tr("Плавный скролл"), () -> cfg.smoothScroll, v -> {
			cfg.smoothScroll = v;
			cfg.save();
		});
		return y;
	}

	private int buildFxTab(int lx, int y, int lw) {
		y = addSectionRow(lx, y, lw, tr("Оптика"));
		y = addToggleRow(lx, y, lw, tr("Матовость"), () -> LiquidumDebugState.frost, v -> LiquidumDebugState.frost = v);
		y = addToggleRow(lx, y, lw, tr("Свечение кромки"), () -> LiquidumDebugState.rim, v -> LiquidumDebugState.rim = v);
		y = addToggleRow(lx, y, lw, tr("Радуга на краях"), () -> LiquidumDebugState.aberration, v -> LiquidumDebugState.aberration = v);
		y = addSectionRow(lx, y, lw, tr("Поведение"));
		y = addToggleRow(lx, y, lw, tr("Блик за курсором"), () -> LiquidumDebugState.hover, v -> LiquidumDebugState.hover = v);
		y = addToggleRow(lx, y, lw, tr("Параллакс"), () -> LiquidumDebugState.parallax, v -> LiquidumDebugState.parallax = v);
		y = addToggleRow(lx, y, lw, tr("Анимация открытия"), () -> LiquidumDebugState.animOpen, v -> LiquidumDebugState.animOpen = v);
		y = addSectionRow(lx, y, lw, tr("Свет"));
		addRenderableWidget(Button.builder(Component.literal(tr("Источник") + ": " + (LiquidumDebugState.lightManual ? tr("ручной") : tr("из мира"))), b -> {
			LiquidumDebugState.lightManual = !LiquidumDebugState.lightManual;
			LiquidumProfiles.save();
			b.setMessage(Component.literal(tr("Источник") + ": " + (LiquidumDebugState.lightManual ? tr("ручной") : tr("из мира"))));
			init();
		}).bounds(lx, y, lw, 20).build());
		y += ROW_H;
		if (LiquidumDebugState.lightManual) {
			y = addNumericRow(lx, y, lw, tr("Угол света"), () -> LiquidumDebugState.lightAngle, v -> LiquidumDebugState.lightAngle = v, 0f, 6.28f, 0.1f, 2.16f);
			y = addNumericRow(lx, y, lw, tr("Сила света"), () -> LiquidumDebugState.lightLevel, v -> LiquidumDebugState.lightLevel = v, 0f, 1f, 0.05f, 0.8f);
		}
		return y;
	}

	private int addSectionRow(int x, int y, int w, String name) {
		addRenderableWidget(new LabLabel(x, y, w, name, font, 0xFFBBBBBB));
		return y + ROW_H;
	}

	private int buildMatTab(int lx, int y, int lw) {
		y = addNumericRow(lx, y, lw, tr("Скругление"), () -> LiquidumDebugState.cornerRadiusFraction, v -> LiquidumDebugState.cornerRadiusFraction = v, 0f, 1f, 0.05f, 0.18f);
		y = addNumericRow(lx, y, lw, tr("Преломление"), () -> LiquidumDebugState.refraction, v -> LiquidumDebugState.refraction = v, 0f, 60f, 1f, 9f);
		y = addNumericRow(lx, y, lw, tr("Кромка"), () -> LiquidumDebugState.fresnel, v -> LiquidumDebugState.fresnel = v, 0f, 2f, 0.05f, 0.65f, () -> LiquidumDebugState.rim);
		y = addNumericRow(lx, y, lw, tr("Чёткость"), () -> LiquidumDebugState.sharpnessMix, v -> LiquidumDebugState.sharpnessMix = v, 0f, 1f, 0.02f, 0.18f);
		y = addNumericRow(lx, y, lw, tr("Радиус мата"), () -> LiquidumDebugState.frostRadius, v -> LiquidumDebugState.frostRadius = v, 0f, 20f, 0.5f, 10.0f, () -> LiquidumDebugState.frost);
		y = addNumericRow(lx, y, lw, tr("Цвет тела"), () -> LiquidumDebugState.bodyBleed, v -> LiquidumDebugState.bodyBleed = v, 0f, 0.3f, 0.02f, 0.10f);
		y = addNumericRow(lx, y, lw, tr("Цвет края"), () -> LiquidumDebugState.edgeBleed, v -> LiquidumDebugState.edgeBleed = v, 0f, 1.5f, 0.05f, 0.65f);
		y = addNumericRow(lx, y, lw, tr("Хрома"), () -> LiquidumDebugState.chroma, v -> LiquidumDebugState.chroma = v, 0f, 1.5f, 0.05f, 0.45f, () -> LiquidumDebugState.aberration);
		y = addNumericRow(lx, y, lw, tr("Оттенок"), () -> LiquidumCore.getConfig().tintStrength, v -> {
			var cfg = LiquidumCore.getConfig();
			cfg.tintStrength = v;
			cfg.save();
			LiquidGlassRenderer.applyConfig(cfg);
			LiquidumProfiles.save();
		}, 0f, 1f, 0.05f, 0f);
		addRenderableWidget(Button.builder(Component.literal(tr("Тон") + ": " + tr(tintName())), b -> {
			cycleTint();
			b.setMessage(Component.literal(tr("Тон") + ": " + tr(tintName())));
		}).bounds(lx, y, lw, 20).build());
		y += ROW_H;
		int snapBw = (lw - 2) / 2;
		y = addSectionRow(lx, y, lw, tr("Эффекты: сохранить"));
		addRenderableWidget(Button.builder(Component.literal(snapA.taken ? tr("Сохранить А ✓") : tr("Сохранить А")), b -> {
			snapA = captureMatSnap();
			init();
		}).bounds(lx, y, snapBw, 20).build());
		addRenderableWidget(Button.builder(Component.literal(snapB.taken ? tr("Сохранить Б ✓") : tr("Сохранить Б")), b -> {
			snapB = captureMatSnap();
			init();
		}).bounds(lx + snapBw + 2, y, lw - snapBw - 2, 20).build());
		y += ROW_H;
		Button fromA = Button.builder(Component.literal(tr("Применить А")), b -> {
			applyMatSnap(snapA);
		}).bounds(lx, y, snapBw, 20).build();
		fromA.active = snapA.taken;
		addRenderableWidget(fromA);
		Button fromB = Button.builder(Component.literal(tr("Применить Б")), b -> {
			applyMatSnap(snapB);
		}).bounds(lx + snapBw + 2, y, lw - snapBw - 2, 20).build();
		fromB.active = snapB.taken;
		addRenderableWidget(fromB);
		y += ROW_H;
		return y;
	}

	// Material snapshot: every MAT/FX value plus tint, view state excluded
	private static LabSceneStore.Snap captureMatSnap() {
		var s = new LabSceneStore.Snap();
		s.taken = true;
		s.hover = LiquidumDebugState.hover;
		s.aberration = LiquidumDebugState.aberration;
		s.rim = LiquidumDebugState.rim;
		s.frost = LiquidumDebugState.frost;
		s.frostRadius = LiquidumDebugState.frostRadius;
		s.fusion = LiquidumDebugState.fusion;
		s.fusionRadius = LiquidumDebugState.fusionRadius;
		s.parallax = LiquidumDebugState.parallax;
		s.lightManual = LiquidumDebugState.lightManual;
		s.lightAngle = LiquidumDebugState.lightAngle;
		s.lightLevel = LiquidumDebugState.lightLevel;
		s.animOpen = LiquidumDebugState.animOpen;
		s.cornerRadiusFraction = LiquidumDebugState.cornerRadiusFraction;
		s.refraction = LiquidumDebugState.refraction;
		s.fresnel = LiquidumDebugState.fresnel;
		s.sharpnessMix = LiquidumDebugState.sharpnessMix;
		s.bodyBleed = LiquidumDebugState.bodyBleed;
		s.edgeBleed = LiquidumDebugState.edgeBleed;
		s.chroma = LiquidumDebugState.chroma;
		s.domeHeight = LiquidumDebugState.domeHeight;
		s.sunSpec = LiquidumDebugState.sunSpec;
		var cfg = LiquidumCore.getConfig();
		s.tintStrength = cfg.tintStrength;
		s.tintRed = cfg.tintRed;
		s.tintGreen = cfg.tintGreen;
		s.tintBlue = cfg.tintBlue;
		return s;
	}

	private void applyMatSnap(LabSceneStore.Snap s) {
		if (s == null || !s.taken) {
			return;
		}
		LiquidumDebugState.hover = s.hover;
		LiquidumDebugState.aberration = s.aberration;
		LiquidumDebugState.rim = s.rim;
		LiquidumDebugState.frost = s.frost;
		LiquidumDebugState.frostRadius = s.frostRadius;
		LiquidumDebugState.fusion = s.fusion;
		LiquidumDebugState.fusionRadius = s.fusionRadius;
		LiquidumDebugState.parallax = s.parallax;
		LiquidumDebugState.lightManual = s.lightManual;
		LiquidumDebugState.lightAngle = s.lightAngle;
		LiquidumDebugState.lightLevel = s.lightLevel;
		LiquidumDebugState.animOpen = s.animOpen;
		LiquidumDebugState.cornerRadiusFraction = s.cornerRadiusFraction;
		LiquidumDebugState.refraction = s.refraction;
		LiquidumDebugState.fresnel = s.fresnel;
		LiquidumDebugState.sharpnessMix = s.sharpnessMix;
		LiquidumDebugState.bodyBleed = s.bodyBleed;
		LiquidumDebugState.edgeBleed = s.edgeBleed;
		LiquidumDebugState.chroma = s.chroma;
		LiquidumDebugState.domeHeight = s.domeHeight;
		LiquidumDebugState.sunSpec = s.sunSpec;
		var cfg = LiquidumCore.getConfig();
		cfg.tintStrength = s.tintStrength;
		cfg.tintRed = s.tintRed;
		cfg.tintGreen = s.tintGreen;
		cfg.tintBlue = s.tintBlue;
		cfg.save();
		LiquidGlassRenderer.applyConfig(cfg);
		LiquidumProfiles.save();
		init();
	}

	private int buildProfTab(int lx, int y, int lw) {
		addRenderableWidget(Button.builder(Component.literal(tr("Сброс") + ": " + LiquidumProfiles.ETALON.displayName()), b -> {
			LiquidumProfiles.ETALON.apply();
			init();
		}).bounds(lx, y, lw, 20).build());
		y += ROW_H;
		addRenderableWidget(new LabLabel(lx, y, lw, tr("Сейчас") + ": " + LiquidumProfiles.currentName(), font, 0xFFDDDDDD));
		return y + ROW_H;
	}

	private int buildAdvTab(int lx, int y, int lw) {
		var cfg = LiquidumCore.getConfig();
		y = addToggleRow(lx, y, lw, tr("Мод включён"), () -> cfg.enabled, v -> {
			cfg.enabled = v;
			cfg.save();
			LiquidGlassRenderer.applyConfig(cfg);
		});
		y = addToggleRow(lx, y, lw, tr("Стекло на кнопках"), () -> cfg.buttonsGlass, v -> {
			cfg.buttonsGlass = v;
			cfg.save();
			LiquidGlassRenderer.applyConfig(cfg);
		});
		y = addToggleRow(lx, y, lw, tr("Стекло на хотбаре"), () -> cfg.hotbarGlass, v -> {
			cfg.hotbarGlass = v;
			cfg.save();
			LiquidGlassRenderer.applyConfig(cfg);
		});
		y = addToggleRow(lx, y, lw, tr("Стекло на слотах"), () -> cfg.containerGlass, v -> {
			cfg.containerGlass = v;
			cfg.save();
			LiquidGlassRenderer.applyConfig(cfg);
		});
		y = addToggleRow(lx, y, lw, tr("Умный док"), () -> cfg.dockAdaptive, v -> {
			cfg.dockAdaptive = v;
			cfg.save();
			LiquidGlassRenderer.applyConfig(cfg);
		});
		addRenderableWidget(Button.builder(Component.literal(tr("Язык") + ": " + langModeName()), b -> {
			var c = LiquidumCore.getConfig();
			c.language = switch (c.language == null ? "auto" : c.language) {
				case "ru" -> "en";
				case "en" -> "auto";
				default -> "ru";
			};
			c.save();
			b.setMessage(Component.literal(tr("Язык") + ": " + langModeName()));
			init();
		}).bounds(lx, y, lw, 20).build());
		y += ROW_H;
		return y;
	}

	// Language override label, auto follows the MC client language
	private static String langModeName() {
		String m = LiquidumCore.getConfig().language;
		if (m == null) m = "auto";
		return switch (m) {
			case "ru" -> tr("Русский");
			case "en" -> "English";
			default -> tr("Авто");
		};
	}

	private void buildBottomBar() {
		int bw = 104;
		int gap = 8;
		int x0 = width / 2 - (bw * 3 + gap * 2) / 2;
		int by = height - 28;
		if (selected == null) {
			addRenderableWidget(Button.builder(Component.literal(dirty ? tr("Сохранить ●") : tr("Сохранено ✓")), b -> {
				saveAll();
				dirty = false;
				init();
			}).bounds(x0, by, bw, 20).build());
			addRenderableWidget(Button.builder(Component.literal(tr("Тест")), b -> {
				testScene();
			}).bounds(x0 + bw + gap, by, bw, 20).build());
			addRenderableWidget(Button.builder(Component.literal(tr("Добавить") + ": " + tr(addTypeName())), b -> {
				pushUndo();
				DemoElement.Type t = DemoElement.Type.values()[addType % DemoElement.Type.values().length];
				float w = switch (t) {
					case PANEL -> 200f;
					case PILL -> 140f;
					case ROUND -> 44f;
					case AVATAR -> 64f;
					case SQUARE -> 96f;
					case SHARP -> 96f;
					case TRIANGLE -> 100f;
					case CIRCLE -> 72f;
				};
				float h = switch (t) {
					case PANEL -> 110f;
					case PILL -> 28f;
					case ROUND -> 44f;
					case AVATAR -> 64f;
					case SQUARE -> 96f;
					case SHARP -> 96f;
					case TRIANGLE -> 90f;
					case CIRCLE -> 72f;
				};
				float e = switch (t) {
					case PANEL -> 1f;
					case PILL -> 2f;
					case ROUND -> 3f;
					case AVATAR -> 2f;
					case SQUARE -> 1f;
					case SHARP -> 2f;
					case TRIANGLE -> 2f;
					case CIRCLE -> 2f;
				};
				float corner = switch (t) {
					case SQUARE -> 0.35f;
					case SHARP -> 0f;
					case CIRCLE -> 1f;
					default -> -1f;
				};
				DemoElement el = new DemoElement(t, 0, 0, w, h, elements.size(), e);
				el.corner = corner;
				elements.add(el);
				selected = el;
				if (corner >= 0f) {
					linkedCorner = false;
				}
				addType = (addType + 1) % DemoElement.Type.values().length;
				init();
			}).bounds(x0 + (bw + gap) * 2, by, bw, 20).build());
		} else if (!multi.isEmpty()) {
			addRenderableWidget(Button.builder(Component.literal(tr("Готово")), b -> {
				multi.clear();
				dragging = null;
				init();
			}).bounds(x0, by, bw, 20).build());
			addRenderableWidget(Button.builder(Component.literal(tr("Копия") + " " + multi.size()), b -> {
				pushUndo();
				var copies = new java.util.ArrayList<DemoElement>();
				int z = elements.size();
				for (DemoElement e : multi) {
					DemoElement c = new DemoElement(e.type, e.x + 12, e.y + 12,
						e.w, e.h, z++, e.elevation);
					c.text = e.text;
					c.corner = e.corner;
					c.placed = true;
					copies.add(c);
				}
				elements.addAll(copies);
				multi.clear();
				multi.addAll(copies);
				init();
			}).bounds(x0 + bw + gap, by, bw, 20).build());
			addRenderableWidget(Button.builder(Component.literal(tr("Удалить") + " " + multi.size()), b -> {
				pushUndo();
				elements.removeAll(multi);
				multi.clear();
				dragging = null;
				init();
			}).bounds(x0 + (bw + gap) * 2, by, bw, 20).build());
		} else {
			addRenderableWidget(Button.builder(Component.literal(tr("Готово")), b -> {
				selected = null;
				dragging = null;
				init();
			}).bounds(x0, by, bw, 20).build());
			addRenderableWidget(Button.builder(Component.literal(tr("Копия")), b -> {
				pushUndo();
				DemoElement c = new DemoElement(selected.type, selected.x + 12, selected.y + 12,
					selected.w, selected.h, elements.size(), selected.elevation);
				c.text = selected.text;
				c.corner = selected.corner;
				c.fuseGroup = selected.fuseGroup;
				c.merge = selected.merge;
				c.placed = true;
				elements.add(c);
				selected = c;
				init();
			}).bounds(x0 + bw + gap, by, bw, 20).build());
			addRenderableWidget(Button.builder(Component.literal(tr("Удалить")), b -> {
				pushUndo();
				elements.remove(selected);
				selected = null;
				dragging = null;
				init();
			}).bounds(x0 + (bw + gap) * 2, by, bw, 20).build());
		}
	}

	// Per-figure corner 0..100%, middle button resets to auto
	private int addCornerRow(int x, int y, int w) {
		String label = selected.corner < 0f
			? tr("Скругление") + ": " + tr("авто")
			: tr("Скругление") + ": " + Math.round(selected.corner * 100f) + "%";
		Button value = Button.builder(Component.literal(label), b -> {
			pushUndo();
			selected.corner = -1f;
			b.setMessage(Component.literal(tr("Скругление") + ": " + tr("авто")));
		}).bounds(x + 22, y, w - 44, 20).build();
		Button minus = Button.builder(Component.literal("-"), b -> {
			pushUndo();
			float base = selected.corner < 0f ? 0.5f : selected.corner;
			selected.corner = clamp(base - 0.05f, 0f, 1f);
			value.setMessage(Component.literal(tr("Скругление") + ": " + Math.round(selected.corner * 100f) + "%"));
		}).bounds(x, y, 20, 20).build();
		Button plus = Button.builder(Component.literal("+"), b -> {
			pushUndo();
			float base = selected.corner < 0f ? 0.5f : selected.corner;
			selected.corner = clamp(base + 0.05f, 0f, 1f);
			value.setMessage(Component.literal(tr("Скругление") + ": " + Math.round(selected.corner * 100f) + "%"));
		}).bounds(x + w - 20, y, 20, 20).build();
		addRenderableWidget(minus);
		addRenderableWidget(value);
		addRenderableWidget(plus);
		return y + ROW_H;
	}

	private int addDropRow(int x, int y, int w, String title, java.util.List<String> options,
		int current, Consumer<Integer> pick, boolean trackUndo) {
		DropListWidget drop = new DropListWidget(x, y, w, title, options, current, font, idx -> {
			if (trackUndo) {
				pushUndo();
			}
			pick.accept(idx);
			init();
		}, d -> openDrop = d);
		addRenderableWidget(drop);
		return y + ROW_H;
	}

	private static int matIndex(int mat) {
		for (int i = 0; i < MAT_VALUES.length; i++) {
			if (MAT_VALUES[i] == mat) {
				return i;
			}
		}
		return 0;
	}

	// Figure preset index for the picker, rectangle with 0.2 corner reads as Прямоугольник
	private static int figIndex(DemoElement e) {
		return switch (e.type) {
			case PANEL -> e.corner == 0.2f ? 8 : 0;
			case PILL -> 1;
			case ROUND -> 2;
			case AVATAR -> 3;
			case SQUARE -> 4;
			case SHARP -> 5;
			case TRIANGLE -> 6;
			case CIRCLE -> 7;
		};
	}

	// Type switch keeps position, square kinds also square the size
	private void applyFigurePreset(DemoElement e, int idx) {
		DemoElement.Type t = switch (idx) {
			case 1 -> DemoElement.Type.PILL;
			case 2 -> DemoElement.Type.ROUND;
			case 3 -> DemoElement.Type.AVATAR;
			case 4 -> DemoElement.Type.SQUARE;
			case 5 -> DemoElement.Type.SHARP;
			case 6 -> DemoElement.Type.TRIANGLE;
			case 7 -> DemoElement.Type.CIRCLE;
			case 8 -> DemoElement.Type.PANEL;
			default -> DemoElement.Type.PANEL;
		};
		float corner = switch (idx) {
			case 4 -> 0.35f;
			case 5 -> 0f;
			case 7 -> 1f;
			case 8 -> 0.2f;
			default -> -1f;
		};
		e.type = t;
		e.corner = corner;
		if (idx == 4 || idx == 5 || idx == 7) {
			float side = Math.round(clamp((e.w + e.h) / 2f, SIZE_MIN, SIZE_MAX));
			e.w = side;
			e.h = side;
			clampToViewport(e);
		}
		if (corner >= 0f) {
			linkedCorner = false;
		}
	}

	private int addToggleRow(int x, int y, int w, String name, BooleanSupplier get, Consumer<Boolean> set) {		Button b = Button.builder(Component.literal(name + ": " + (get.getAsBoolean() ? tr("вкл") : tr("выкл"))), btn -> {
			set.accept(!get.getAsBoolean());
			LiquidumProfiles.save();
			btn.setMessage(Component.literal(name + ": " + (get.getAsBoolean() ? tr("вкл") : tr("выкл"))));
		}).bounds(x, y, w, 20).build();
		addRenderableWidget(b);
		return y + ROW_H;
	}

	private int addNumericRow(int x, int y, int w, String name, Supplier<Float> get, Consumer<Float> set,
		float min, float max, float step, float def) {
		return addNumericRow(x, y, w, name, get, set, min, max, step, def, () -> true);
	}

	private int addNumericRow(int x, int y, int w, String name, Supplier<Float> get, Consumer<Float> set,
		float min, float max, float step, float def, BooleanSupplier enabled) {
		boolean on = enabled.getAsBoolean();
		String label = name + ": " + fmt(get.get()) + (on ? "" : tr(" (выкл)"));
		Button value = Button.builder(Component.literal(label), b -> {
			if (!enabled.getAsBoolean()) {
				return;
			}
			set.accept(def);
			LiquidumProfiles.save();
			b.setMessage(Component.literal(name + ": " + fmt(def)));
		}).bounds(x + 22, y, w - 44, 20).build();
		Button minus = Button.builder(Component.literal("-"), b -> {
			if (!enabled.getAsBoolean()) {
				return;
			}
			set.accept(clamp(get.get() - step, min, max));
			LiquidumProfiles.save();
			value.setMessage(Component.literal(name + ": " + fmt(get.get())));
		}).bounds(x, y, 20, 20).build();
		Button plus = Button.builder(Component.literal("+"), b -> {
			if (!enabled.getAsBoolean()) {
				return;
			}
			set.accept(clamp(get.get() + step, min, max));
			LiquidumProfiles.save();
			value.setMessage(Component.literal(name + ": " + fmt(get.get())));
		}).bounds(x + w - 20, y, 20, 20).build();
		minus.active = on;
		value.active = on;
		plus.active = on;
		addRenderableWidget(minus);
		addRenderableWidget(value);
		addRenderableWidget(plus);
		return y + ROW_H;
	}

	private static final float[][] TINTS = {{0.62f, 0.78f, 1.0f}, {1.0f, 0.8f, 0.6f}, {1.0f, 0.6f, 0.7f}, {0.6f, 1.0f, 0.8f}};
	private static final String[] TINT_NAMES = {"Холод", "Тёпл", "Роза", "Мята"};

	private static int tintIndex() {
		var cfg = LiquidumCore.getConfig();
		int best = 0;
		float bd = Float.MAX_VALUE;
		for (int i = 0; i < TINTS.length; i++) {
			float d = Math.abs(TINTS[i][0] - cfg.tintRed) + Math.abs(TINTS[i][1] - cfg.tintGreen) + Math.abs(TINTS[i][2] - cfg.tintBlue);
			if (d < bd) {
				bd = d;
				best = i;
			}
		}
		return best;
	}

	private static String tintName() {
		return tr(TINT_NAMES[tintIndex()]);
	}

	private static void cycleTint() {
		int next = (tintIndex() + 1) % TINTS.length;
		var cfg = LiquidumCore.getConfig();
		cfg.tintRed = TINTS[next][0];
		cfg.tintGreen = TINTS[next][1];
		cfg.tintBlue = TINTS[next][2];
		cfg.save();
		LiquidGlassRenderer.applyConfig(cfg);
		LiquidumProfiles.save();
	}

	// Fixed torture scene, identical every run for before-after compare
	private void testScene() {
		pushUndo();
		elements.clear();
		backdrop = GRID_INDEX;
		flatBackdrop = false;
		int[] vp = new LabLayout(width, height, mode, 1f, dockRight).viewport();
		int cx = vp[0] + Math.max(200, vp[2]) / 2;
		int cy = vp[1] + Math.max(160, vp[3]) / 2;
		DemoElement panel = new DemoElement(DemoElement.Type.PANEL, cx - 110, cy - 60, 220, 120, 0, 1f);
		panel.placed = true;
		elements.add(panel);
		DemoElement pill = new DemoElement(DemoElement.Type.PILL, cx - 70, cy - 42, 140, 28, 1, 2f);
		pill.text = "Liquid glass";
		pill.placed = true;
		elements.add(pill);
		for (int i = 0; i < 3; i++) {
			DemoElement s = new DemoElement(DemoElement.Type.ROUND, cx - 70 + i * 46, cy + 8, 42, 42, 2, 3f);
			s.placed = true;
			elements.add(s);
		}
		DemoElement lone = new DemoElement(DemoElement.Type.ROUND, cx + 80, cy + 8, 42, 42, 3, 3f);
		lone.merge = false;
		lone.placed = true;
		elements.add(lone);
		DemoElement low = new DemoElement(DemoElement.Type.CIRCLE, cx - 120, cy + 40, 64, 64, 4, 0f);
		low.corner = 1f;
		low.placed = true;
		elements.add(low);
		DemoElement high = new DemoElement(DemoElement.Type.CIRCLE, cx + 60, cy - 100, 64, 64, 5, 3f);
		high.corner = 1f;
		high.text = "Hi";
		high.placed = true;
		elements.add(high);
		linkedCorner = false;
		for (DemoElement e : elements) {
			clampToViewport(e);
		}
		selected = null;
		dragging = null;
		init();
	}

	private void applyScenePreset() {
		List<DemoElement> sorted = new ArrayList<>(elements);
		sorted.sort(Comparator.comparingInt(e -> e.zOrder));
		int[] vp = new LabLayout(width, height, mode, 1f, dockRight).viewport();
		int vx = vp[0];
		int vy = vp[1];
		int vw = Math.max(200, vp[2]);
		int vh = Math.max(160, vp[3]);
		int cx = vx + vw / 2;
		int cy = vy + vh / 2;
		if (scenePreset == 3) {
			applyBubblePreset(sorted, vx, vy, vw, vh, cx, cy);
		}
		for (int i = 0; i < sorted.size(); i++) {
			DemoElement e = sorted.get(i);
			if (scenePreset == 1) {
				e.x = vx + vw * (i + 1) / (sorted.size() + 1) - e.w / 2;
				e.y = cy - e.h / 2 + (i % 2 == 0 ? -40 : 40);
			} else if (scenePreset == 2 && i > 0) {
				DemoElement base = sorted.get(0);
				e.x = base.x + 20 + (i - 1) * 30;
				e.y = base.y + 20 + (i - 1) * 24;
			} else if (scenePreset != 3) {
				e.x = cx - e.w / 2 + (i - 1) * 18;
				e.y = cy - e.h / 2 + (i - 1) * 26;
			}
			e.placed = true;
			clampToViewport(e);
		}
		selected = null;
		dragging = null;
	}

	// Concept bubbles: three layered circles on flat night, small left and low
	private void applyBubblePreset(List<DemoElement> sorted, int vx, int vy, int vw, int vh, int cx, int cy) {
		while (elements.size() < 3) {
			DemoElement c = new DemoElement(DemoElement.Type.CIRCLE, 0, 0, 70, 70, elements.size(), 2f);
			c.corner = 1f;
			elements.add(c);
			sorted.add(c);
		}
		sorted.sort(Comparator.comparingInt(e -> e.zOrder));
		float m = Math.min(vw, vh);
		float[] d = {m * 0.17f, m * 0.27f, m * 0.36f};
		float[] ox = {-0.26f, -0.08f, 0.13f};
		float[] oy = {0.05f, -0.09f, 0.10f};
		for (int i = 0; i < 3; i++) {
			DemoElement e = sorted.get(i);
			e.type = DemoElement.Type.CIRCLE;
			e.corner = 1f;
			e.elevation = 2f;
			e.w = d[i];
			e.h = d[i];
			e.x = cx + ox[i] * vw - d[i] / 2f;
			e.y = cy + oy[i] * vh - d[i] / 2f;
			e.placed = true;
			clampToViewport(e);
		}
		linkedCorner = false;
		backdrop = 5;
		flatBackdrop = true;
	}

	private void saveAll() {
		LabSceneStore.Data d = new LabSceneStore.Data();
		d.backdrop = backdrop;
		d.customHex = customHex;
		d.linkedCorner = linkedCorner;
		d.flatBackdrop = flatBackdrop;
		d.photoFit = photoFit;
		d.photoName = photoName;
		d.snapA = snapA;
		d.snapB = snapB;
		for (int c : colorHistory) {
			d.history.add(String.format(java.util.Locale.ROOT, "%06X", c & 0xFFFFFF));
		}
		d.dockRight = dockRight;
		d.scenePreset = scenePreset;
		for (DemoElement e : elements) {
			LabSceneStore.Elem se = new LabSceneStore.Elem();
			se.type = e.type.name();
			se.x = e.x;
			se.y = e.y;
			se.w = e.w;
			se.h = e.h;
			se.zOrder = e.zOrder;
			se.elevation = e.elevation;
			se.text = e.text;
			se.mat = e.mat;
			se.corner = e.corner;
			se.fuseGroup = e.fuseGroup;
			se.merge = e.merge;
			d.elements.add(se);
		}
		LabSceneStore.save(d);
		LiquidumProfiles.save();
		LiquidumCore.getConfig().save();
	}

	private DemoElement pickTop(float px, float py) {
		List<DemoElement> sorted = new ArrayList<>(elements);
		sorted.sort((a, b) -> Integer.compare(b.zOrder, a.zOrder));
		for (DemoElement e : sorted) {
			if (e.visible && e.contains(px, py)) {
				return e;
			}
		}
		return null;
	}

	private void clampToViewport(DemoElement e) {
		int[] vp = new LabLayout(width, height, mode, Math.max(transition, 0.5f), dockRight).viewport();
		float x0 = vp[0] + 4;
		float y0 = vp[1] + 4;
		float x1 = vp[0] + Math.max(60, vp[2]) - 4;
		float y1 = vp[1] + Math.max(60, vp[3]) - 4;
		e.x = Math.max(x0, Math.min(e.x, Math.max(x0, x1 - e.w)));
		e.y = Math.max(y0, Math.min(e.y, Math.max(y0, y1 - e.h)));
	}

	@Override
	public boolean mouseScrolled(double x, double y, double dx, double dy) {
		if (openDrop != null && openDrop.popupHit((float) x, (float) y, popupMaxH())) {
			openDrop.scrollPopup((float) dy, popupMaxH());
			lastScrollNanos = System.nanoTime();
			return true;
		}
		if (x >= contentX && x <= contentX + LabLayout.LEFT_W && maxScroll() > 0) {
			scrollPx = Math.max(0f, Math.min(scrollPx - (float) (dy * 20.0), maxScroll()));
			applyScroll();
			lastScrollNanos = System.nanoTime();
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
		if (openDrop != null && !children().contains(openDrop)) {
			openDrop = null;
		}
		if (openDrop != null) {
			if (openDrop.thumbHit((float) event.x(), (float) event.y(), popupMaxH())) {
				openDrop.beginPopupDrag((float) event.y(), popupMaxH());
				lastScrollNanos = System.nanoTime();
				return true;
			}
			openDrop.handlePopupClick((float) event.x(), (float) event.y(), popupMaxH());
			openDrop = null;
			init();
			return true;
		}
		int[] th = scrollThumb();
		if (th != null && event.x() >= th[0] && event.x() <= th[0] + th[2]
			&& event.y() >= th[1] && event.y() <= th[1] + th[3]) {
			scrollDrag = true;
			scrollGrab = (float) event.y() - th[1];
			lastScrollNanos = System.nanoTime();
			return true;
		}
		if (event.y() < HEADER_BOTTOM && event.x() >= contentX && event.x() <= contentX + LabLayout.LEFT_W) {
			boolean onChrome = false;
			for (AbstractWidget t : tabWidgets) {
				if (t.isMouseOver(event.x(), event.y())) {
					onChrome = true;
					break;
				}
			}
			if (!onChrome) {
				return true;
			}
		}
		if (event.y() > viewBottom() && event.x() >= contentX && event.x() <= contentX + LabLayout.LEFT_W) {
			boolean onChrome = false;
			for (AbstractWidget t : barWidgets) {
				if (t.isMouseOver(event.x(), event.y())) {
					onChrome = true;
					break;
				}
			}
			if (!onChrome) {
				return true;
			}
		}
		boolean handled = super.mouseClicked(event, bl);
		if (handled) {
			return true;
		}
		if (mode == LabLayout.Mode.NORMAL || transition < 0.5f) {
			return false;
		}
		DemoElement hit = pickTop((float) event.x(), (float) event.y());
		selected = hit;
		multi.clear();
		dragging = null;
		dragSnapshot = null;
		if (hit != null && hit.movable) {
			dragging = hit;
			dragSnapshot = takeSnapshot();
			dragStartX = hit.x;
			dragStartY = hit.y;
			dragOffX = (float) event.x() - hit.x;
			dragOffY = (float) event.y() - hit.y;
		}
		init();
		return hit != null;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (openDrop != null && openDrop.isPopupDrag()) {
			openDrop.dragPopupTo((float) event.y(), popupMaxH());
			lastScrollNanos = System.nanoTime();
			return true;
		}
		if (scrollDrag) {
			int[] th = scrollThumb();
			if (th != null) {
				float max = maxScroll();
				scrollPx = Math.max(0f, Math.min(((float) event.y() - scrollGrab - viewTop()) / (viewBottom() - viewTop() - th[3]) * max, max));
				applyScroll();
				lastScrollNanos = System.nanoTime();
			}
			return true;
		}
		if (dragging != null) {
			float nx = (float) event.x() - dragOffX;
			float ny = (float) event.y() - dragOffY;
			if (multi.contains(dragging)) {
				float mdx = nx - dragging.x;
				float mdy = ny - dragging.y;
				for (DemoElement e : multi) {
					e.x += mdx;
					e.y += mdy;
					clampToViewport(e);
				}
			} else {
				dragging.x = nx;
				dragging.y = ny;
				clampToViewport(dragging);
			}
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (textField != null && children().contains(textField) && textField.isFocused()) {
			return super.keyPressed(event);
		}
		if (event.hasControlDown() && event.key() == GLFW.GLFW_KEY_Z) {
			if (event.hasShiftDown()) {
				redo();
			} else {
				undo();
			}
			return true;
		}
		if (event.hasControlDown() && event.key() == GLFW.GLFW_KEY_Y) {
			redo();
			return true;
		}
		if (event.hasControlDown() && event.key() == GLFW.GLFW_KEY_A) {
			multi.clear();
			multi.addAll(elements);
			selected = null;
			init();
			return true;
		}
		if (event.key() == GLFW.GLFW_KEY_DELETE || event.key() == GLFW.GLFW_KEY_BACKSPACE) {
			if (selected != null) {
				pushUndo();
				elements.remove(selected);
				selected = null;
				dragging = null;
				init();
				return true;
			}
			if (!multi.isEmpty()) {
				pushUndo();
				elements.removeAll(multi);
				multi.clear();
				dragging = null;
				init();
				return true;
			}
			return super.keyPressed(event);
		}
		if (selected != null) {
			int step = event.hasShiftDown() ? 10 : 1;
			int key = event.key();
			if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT
				|| key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
				pushUndo();
				if (key == GLFW.GLFW_KEY_LEFT) {
					selected.x -= step;
				} else if (key == GLFW.GLFW_KEY_RIGHT) {
					selected.x += step;
				} else if (key == GLFW.GLFW_KEY_UP) {
					selected.y -= step;
				} else {
					selected.y += step;
				}
				clampToViewport(selected);
				return true;
			}
		} else if (!multi.isEmpty()) {
			int step = event.hasShiftDown() ? 10 : 1;
			int key = event.key();
			if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT
				|| key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
				pushUndo();
				for (DemoElement e : multi) {
					if (key == GLFW.GLFW_KEY_LEFT) {
						e.x -= step;
					} else if (key == GLFW.GLFW_KEY_RIGHT) {
						e.x += step;
					} else if (key == GLFW.GLFW_KEY_UP) {
						e.y -= step;
					} else {
						e.y += step;
					}
					clampToViewport(e);
				}
				return true;
			}
		}
		if (event.key() == GLFW.GLFW_KEY_TAB) {
			selectNext();
			init();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragging != null && dragSnapshot != null
			&& (dragging.x != dragStartX || dragging.y != dragStartY)) {
			undoStack.addLast(dragSnapshot);
			while (undoStack.size() > UNDO_CAP) {
				undoStack.removeFirst();
			}
			redoStack.clear();
			dirty = true;
		}
		dragging = null;
		dragSnapshot = null;
		scrollDrag = false;
		if (openDrop != null) {
			openDrop.endPopupDrag();
		}
		if (isCustomBackdrop() && pushHistory(customBackdropColor())) {
			dirty = true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		// Backdrop lives in the background stratum, the chain composites over it
		paintBackdrop(g);
		super.extractBackground(g, mouseX, mouseY, partialTick);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		float target = mode == LabLayout.Mode.PREVIEW ? 1f : 0f;
		transition = LabLayout.approach(transition, target, delta, 3f);
		LabLayout layout = new LabLayout(width, height, mode, transition, dockRight);
		int[] vp = layout.viewport();
		glideContent(layout);
		if (vp[2] > 0 && vp[3] > 0) {
			placeUnplaced(vp);
			paintViewportFrame(g, vp);
		}
		// Header mask: rows slide under the fixed tabs, tabs and bar render after the pop
		boolean masked = contentX + LabLayout.LEFT_W > contentX && viewBottom() > HEADER_BOTTOM;
		if (masked) {
			g.enableScissor(contentX, HEADER_BOTTOM, contentX + LabLayout.LEFT_W, viewBottom());
		}
		super.extractRenderState(g, mouseX, mouseY, delta);
		if (masked) {
			g.disableScissor();
		}
		for (AbstractWidget t : tabWidgets) {
			t.extractRenderState(g, mouseX, mouseY, delta);
		}
		for (AbstractWidget t : barWidgets) {
			t.extractRenderState(g, mouseX, mouseY, delta);
		}
		if (vp[2] > 0 && vp[3] > 0) {
			submitElements();
			paintTexts(g);
			paintInfo(g, vp);
		}
		paintHint(g, layout.leftX(), layout.leftW());
		paintScrollBar(g);
		if (openDrop != null) {
			if (!children().contains(openDrop)) {
				openDrop = null;
			} else {
				openDrop.drawPopup(g, mouseX, mouseY, popupMaxH(), scrollFade());
			}
		}
	}

	private void placeUnplaced(int[] vp) {
		int total = Math.max(1, elements.size());
		for (int i = 0; i < elements.size(); i++) {
			DemoElement e = elements.get(i);
			if (!e.placed) {
				e.x = vp[0] + vp[2] * (i + 1) / (total + 1) - e.w / 2;
				e.y = vp[1] + vp[3] / 2 - e.h / 2 + (i % 2 == 0 ? -40 : 40);
				e.placed = true;
			}
			clampToViewport(e);
		}
	}

	private void submitElements() {
		List<DemoElement> sorted = new ArrayList<>(elements);
		sorted.sort(Comparator.comparingInt(e -> e.zOrder));
		for (int idx = 0; idx < sorted.size(); idx++) {
			DemoElement e = sorted.get(idx);
			if (!e.visible) {
				continue;
			}
			// Shape id rides uMats w, explicit corner only in local mode
			float shapeW = (float) e.shapeId();
			if (!linkedCorner && e.corner >= 0f) {
				shapeW += 0.5f + Math.min(1f, e.corner) * 0.499f;
			}
			// Default lanes start at 10, game legacy groups 0-1 stay clear
			float grp;
			if (!e.merge) {
				grp = 1000f + idx;
			} else if (e.fuseGroup >= 0) {
				grp = (float) e.fuseGroup;
			} else {
				grp = 10f + (float) e.zOrder;
			}
			LiquidGlassRenderer.submitSpriteTile(Math.round(e.x), Math.round(e.y),
				Math.round(e.w), Math.round(e.h), resolveMat(e), e.elevation, grp, shapeW);
		}
	}

	private static int resolveMat(DemoElement e) {
		if (e.mat >= 0) {
			return e.mat;
		}
		return switch (e.type) {
			case PANEL -> LiquidGlassRenderer.MAT_BASE;
			case PILL -> LiquidGlassRenderer.MAT_CONTROL;
			case ROUND -> LiquidGlassRenderer.MAT_SLOT;
			case AVATAR -> LiquidGlassRenderer.MAT_CONTROL;
			case SQUARE -> LiquidGlassRenderer.MAT_BASE;
			case SHARP -> LiquidGlassRenderer.MAT_BASE;
			case TRIANGLE -> LiquidGlassRenderer.MAT_CONTROL;
			case CIRCLE -> LiquidGlassRenderer.MAT_CONTROL;
		};
	}

	private static String matName(DemoElement e) {
		for (int i = 0; i < MAT_VALUES.length; i++) {
			if (MAT_VALUES[i] == e.mat) {
				return tr(MAT_NAMES[i]);
			}
		}
		return tr(MAT_NAMES[0]);
	}

	private static int nextMat(int current) {
		for (int i = 0; i < MAT_VALUES.length; i++) {
			if (MAT_VALUES[i] == current) {
				return MAT_VALUES[(i + 1) % MAT_VALUES.length];
			}
		}
		return MAT_VALUES[0];
	}

	private boolean isCustomBackdrop() {
		return backdrop == CUSTOM_INDEX;
	}

	// Recent colors first, max five, opaque ints, true when reordered
	private boolean pushHistory(int argb) {
		int c = argb | 0xFF000000;
		if (!colorHistory.isEmpty() && colorHistory.get(0) == c) {
			return false;
		}
		colorHistory.removeIf(v -> v == c);
		colorHistory.add(0, c);
		while (colorHistory.size() > 5) {
			colorHistory.remove(colorHistory.size() - 1);
		}
		return true;
	}

	// Parse #RRGGBB or #AARRGGBB, opaque fallback on garbage
	private int customBackdropColor() {
		String s = customHex.trim();
		if (s.startsWith("#")) {
			s = s.substring(1);
		}
		try {
			if (s.length() == 6) {
				return (int) Long.parseLong("FF" + s, 16);
			}
			if (s.length() == 8) {
				return (int) Long.parseLong(s, 16);
			}
		} catch (NumberFormatException ignored) {
		}
		return 0xFF5B7C4A;
	}

	private void openPhotosDir() {
		java.nio.file.Path dir;
		try {
			dir = LiquidumPaths.photosDir();
		} catch (Exception e) {
			return;
		}
		try {
			if (minecraft != null) {
				minecraft.keyboardHandler.setClipboard(dir.toString());
			}
		} catch (Exception ignored) {
		}
		com.liquidum.LiquidumMod.LOGGER.info("[lab] config dir: {}", dir);
		try {
			net.minecraft.util.Util.getPlatform().openPath(dir);
		} catch (Exception ignored) {
		}
	}

	private int backdropColor() {
		if (isCustomBackdrop()) {
			return customBackdropColor();
		}
		return BACKDROP_COLORS[Math.min(backdrop, BACKDROP_COLORS.length - 1)];
	}

	// Photo backdrop, cover crop by default, best effort only
	private void paintPhoto(GuiGraphicsExtractor g) {
		float s = Math.max(width / (float) LabPhoto.imgW(), height / (float) LabPhoto.imgH());
		int dx = 0;
		int dy = 0;
		int dw = width;
		int dh = height;
		int u = 0;
		int v = 0;
		int cw = LabPhoto.imgW();
		int ch = LabPhoto.imgH();
		if (photoFit == 1) {
			s = Math.min(width / (float) LabPhoto.imgW(), height / (float) LabPhoto.imgH());
			dw = Math.max(1, Math.round(LabPhoto.imgW() * s));
			dh = Math.max(1, Math.round(LabPhoto.imgH() * s));
			dx = (width - dw) / 2;
			dy = (height - dh) / 2;
		} else if (photoFit == 2) {
			cw = LabPhoto.imgW();
			ch = LabPhoto.imgH();
		} else if (photoFit == 3) {
			dw = Math.min(LabPhoto.imgW(), width);
			dh = Math.min(LabPhoto.imgH(), height);
			dx = (width - dw) / 2;
			dy = (height - dh) / 2;
			cw = dw;
			ch = dh;
			u = (LabPhoto.imgW() - cw) / 2;
			v = (LabPhoto.imgH() - ch) / 2;
		} else {
			cw = Math.max(1, Math.round(width / s));
			ch = Math.max(1, Math.round(height / s));
			u = (LabPhoto.imgW() - cw) / 2;
			v = (LabPhoto.imgH() - ch) / 2;
		}
		g.blit(RenderPipelines.GUI_TEXTURED, LabPhoto.textureId(),
			dx, dy, (float) u, (float) v, dw, dh, LabPhoto.imgW(), LabPhoto.imgH());
	}

	private void paintBackdrop(GuiGraphicsExtractor g) {
		if (backdrop == PHOTO_INDEX) {
			g.fill(0, 0, width, height, 0xFF101018);
			if (LabPhoto.has()) {
				paintPhoto(g);
			} else {
				g.centeredText(font, tr("Жми Открыть папку и кинь туда jpg/png"), width / 2, height / 2 - 5, 0xFFFFFFFF);
			}
			return;
		}
		g.fill(0, 0, width, height, backdropColor());
		if (flatBackdrop) {
			return;
		}
		if (backdrop == GRID_INDEX) {
			for (int y = 0; y < height; y += 20) {
				for (int x = 0; x < width; x += 20) {
					if (((x + y) / 20) % 2 == 0) {
						g.fill(x, y, x + 20, y + 20, 0x22000000);
					}
				}
			}
			for (int i = 0; i < width; i += 60) {
				int c = switch ((i / 60) % 3) { case 0 -> 0xFFFF5555; case 1 -> 0xFF55FF55; default -> 0xFF5555FF; };
				g.fill(i, height / 2 - 30, i + 20, height / 2 + 30, c);
			}
			return;
		}
		for (int y = 0; y < height; y += 20) {
			for (int x = 0; x < width; x += 20) {
				if (((x + y) / 20) % 2 == 0) {
					g.fill(x, y, x + 20, y + 20, 0x0A000000);
				}
			}
		}
		for (int i = 0; i < width; i += 20) {
			g.fill(i, 0, i + 1, height, 0x14000000);
		}
		for (int i = 0; i < height; i += 20) {
			g.fill(0, i, width, i + 1, 0x14000000);
		}
	}

	private void paintTexts(GuiGraphicsExtractor g) {
		for (DemoElement e : elements) {
			if (!e.visible || e.text == null || e.text.isEmpty()) {
				continue;
			}
			int maxChars = Math.max(1, (int) ((e.w - 8) / 6));
			int maxLines = Math.max(1, (int) ((e.h - 6) / 10));
			var lines = DemoElement.wrapText(e.text, maxChars, maxLines);
			int y = Math.round(e.y + e.h / 2 - lines.size() * 5);
			for (String line : lines) {
				g.centeredText(font, line, Math.round(e.x + e.w / 2), y, 0xFFFFFFFF);
				y += 10;
			}
		}
	}

	private void paintViewportFrame(GuiGraphicsExtractor g, int[] vp) {
		int x0 = vp[0];
		int y0 = vp[1];
		int x1 = vp[0] + vp[2];
		int y1 = vp[1] + vp[3];
		g.fill(x0, y0, x1, y0 + 1, 0x88FFFFFF);
		g.fill(x0, y1 - 1, x1, y1, 0x88FFFFFF);
		g.fill(x0, y0, x0 + 1, y1, 0x88FFFFFF);
		g.fill(x1 - 1, y0, x1, y1, 0x88FFFFFF);
		if (selected != null) {
			int sx = Math.round(selected.x);
			int sy = Math.round(selected.y);
			g.fill(sx - 1, sy - 1, sx + (int) selected.w + 1, sy, 0xFFFFD166);
			g.fill(sx - 1, sy + (int) selected.h, sx + (int) selected.w + 1, sy + (int) selected.h + 1, 0xFFFFD166);
		} else {
			for (DemoElement e : multi) {
				int sx = Math.round(e.x);
				int sy = Math.round(e.y);
				g.fill(sx - 1, sy - 1, sx + (int) e.w + 1, sy, 0xFFFFFFFF);
				g.fill(sx - 1, sy + (int) e.h, sx + (int) e.w + 1, sy + (int) e.h + 1, 0xFFFFFFFF);
			}
		}
	}

	private int fusionLanes() {
		java.util.List<DemoElement> sorted = new java.util.ArrayList<>(elements);
		sorted.sort(java.util.Comparator.comparingInt(e -> e.zOrder));
		java.util.Set<Float> lanes = new java.util.HashSet<>();
		for (int idx = 0; idx < sorted.size(); idx++) {
			DemoElement e = sorted.get(idx);
			float grp;
			if (!e.merge) grp = 1000f + idx;
			else if (e.fuseGroup >= 0) grp = (float) e.fuseGroup;
			else grp = 10f + (float) e.zOrder;
			lanes.add(grp);
		}
		return lanes.size();
	}

	private void paintInfo(GuiGraphicsExtractor g, int[] vp) {
		String info = selected != null
			? typeName(selected) + " · " + matName(selected) + " · " + tr("высота") + " " + fmt(selected.elevation)
			: !multi.isEmpty()
				? tr("Выбрано") + ": " + multi.size()
				: tr("Сцена") + ": " + tr(SCENES[scenePreset]) + " · " + elements.size() + " " + tr("объекта") + " · " + fusionLanes() + " " + tr("групп");
		g.centeredText(font, info, vp[0] + vp[2] / 2, vp[1] + vp[3] - 12, 0xFFFFFFFF);
		g.centeredText(font, tr("Стекло") + ": " + LiquidGlassRenderer.labStatus(), vp[0] + vp[2] / 2, vp[1] + 6, 0xFFDDDDDD);
	}

	private void paintHint(GuiGraphicsExtractor g, int lx, int lw) {
		String hint = tabHint(tab);
		if (tab == Tab.DEMO && selected != null) {
			hint = tr("Высота — свет, тень и слияние стекла");
		}
		if (LiquidumDebugState.soloStage != 0) {
			hint += tr(" · Вид: ") + LiquidumDebugState.soloName();
		}
		g.centeredText(font, hint, contentX + LabLayout.LEFT_W / 2, 34, 0xFFDDDDDD);
	}

	private void selectNext() {
		multi.clear();
		if (elements.isEmpty()) {
			selected = null;
			return;
		}
		if (selected == null) {
			selected = elements.get(0);
			return;
		}
		int i = elements.indexOf(selected);
		selected = elements.get((i + 1) % elements.size());
	}

	// Scene undo, snapshots of everything saveAll persists
	private static final class SceneSnapshot {
		final java.util.List<DemoElement> elements;
		final int backdrop;
		final String customHex;
		final boolean flatBackdrop;
		final boolean linkedCorner;
		final int scenePreset;
		final int selectedIdx;

		SceneSnapshot(java.util.List<DemoElement> elements, int backdrop, String customHex,
			boolean flatBackdrop, boolean linkedCorner, int scenePreset, int selectedIdx) {
			this.elements = elements;
			this.backdrop = backdrop;
			this.customHex = customHex;
			this.flatBackdrop = flatBackdrop;
			this.linkedCorner = linkedCorner;
			this.scenePreset = scenePreset;
			this.selectedIdx = selectedIdx;
		}
	}

	private static DemoElement copyOf(DemoElement e) {
		DemoElement c = new DemoElement(e.type, e.x, e.y, e.w, e.h, e.zOrder, e.elevation);
		c.text = e.text;
		c.movable = e.movable;
		c.visible = e.visible;
		c.placed = e.placed;
		c.mat = e.mat;
		c.corner = e.corner;
		c.fuseGroup = e.fuseGroup;
		c.merge = e.merge;
		return c;
	}

	private SceneSnapshot takeSnapshot() {
		var els = new java.util.ArrayList<DemoElement>();
		for (DemoElement e : elements) {
			els.add(copyOf(e));
		}
		int sel = selected == null ? -1 : elements.indexOf(selected);
		return new SceneSnapshot(els, backdrop, customHex, flatBackdrop, linkedCorner, scenePreset, sel);
	}

	private void applySnapshot(SceneSnapshot s) {
		lastTextUndoNanos = 0L;
		elements.clear();
		for (DemoElement e : s.elements) {
			elements.add(copyOf(e));
		}
		backdrop = s.backdrop;
		customHex = s.customHex;
		flatBackdrop = s.flatBackdrop;
		linkedCorner = s.linkedCorner;
		scenePreset = s.scenePreset;
		selected = s.selectedIdx >= 0 && s.selectedIdx < elements.size() ? elements.get(s.selectedIdx) : null;
		multi.clear();
		dragging = null;
		init();
	}

	// Record state before a mutation, clears redo, marks the scene dirty
	private void pushUndo() {
		undoStack.addLast(takeSnapshot());
		while (undoStack.size() > UNDO_CAP) {
			undoStack.removeFirst();
		}
		redoStack.clear();
		dirty = true;
	}

	private void undo() {
		if (undoStack.isEmpty()) {
			return;
		}
		redoStack.addLast(takeSnapshot());
		applySnapshot(undoStack.removeLast());
		dirty = true;
	}

	private void redo() {
		if (redoStack.isEmpty()) {
			return;
		}
		undoStack.addLast(takeSnapshot());
		applySnapshot(redoStack.removeLast());
		dirty = true;
	}

	// Settings column glides docked <-> centered as the demo opens and closes
	private void glideContent(LabLayout layout) {
		int dockedX = layout.leftX();
		int centerX = (width - LabLayout.LEFT_W) / 2;
		contentX = Math.round(dockedX + (centerX - dockedX) * (1f - layout.transition));
		for (int i = 0; i < tabWidgets.size(); i++) {
			tabWidgets.get(i).setX(contentX + tabDX.get(i));
		}
		for (int i = 0; i < contentWidgets.size(); i++) {
			contentWidgets.get(i).setX(contentX + contentDX.get(i));
		}
	}

	// Column scroll, bottom bar stays fixed
	private int viewTop() {
		return HEADER_BOTTOM;
	}

	private int viewBottom() {
		return height - 32;
	}

	private int contentBottom() {
		int b = viewTop();
		for (int i = 0; i < contentWidgets.size(); i++) {
			b = Math.max(b, contentDY.get(i) + contentWidgets.get(i).getHeight());
		}
		return b;
	}

	private float maxScroll() {
		return Math.max(0, contentBottom() - viewBottom());
	}

	private void applyScroll() {
		scrollPx = Math.max(0f, Math.min(scrollPx, maxScroll()));
		int culled = 0;
		String kinds = "";
		for (int i = 0; i < contentWidgets.size(); i++) {
			AbstractWidget w = contentWidgets.get(i);
			int liveY = contentDY.get(i) - (int) scrollPx;
			w.setY(liveY);
			// Engine etches recorded button scissors as-is and demands full
			// containment, so cull by live position unless fully on screen
			boolean inside = liveY >= 0 && liveY + w.getHeight() <= height;
			w.visible = inside;
			if (!inside) {
				culled++;
				if (kinds.length() < 80) {
					kinds += w.getClass().getSimpleName() + " ";
				}
			}
		}
		if (culled != lastCulledLog) {
			lastCulledLog = culled;
			if (culled > 0 && LiquidumDebugState.DEBUG_BUILD) {
				com.liquidum.LiquidumMod.LOGGER.info("[lab] scroll culls {} widgets: {}", culled, kinds);
			}
		}
	}

	// Scrollbar thumb in screen space, 2 px right of the buttons, null when it fits
	private int[] scrollThumb() {
		float max = maxScroll();
		if (max <= 0) {
			return null;
		}
		int top = viewTop();
		int bottom = viewBottom();
		int th = Math.max(20, (bottom - top) * (bottom - top) / Math.max(1, contentBottom() - top));
		int ty = top + (int) ((bottom - top - th) * (scrollPx / max));
		return new int[]{contentX + LabLayout.LEFT_W + 2, ty, 4, th};
	}

	private void paintScrollBar(GuiGraphicsExtractor g) {
		int[] th = scrollThumb();
		if (th == null) {
			return;
		}
		// Column slider stays visible for easy dragging, only popups fade
		g.fill(th[0], viewTop(), th[0] + th[2], viewBottom(), 0x44000000);
		g.fill(th[0], th[1], th[0] + th[2], th[1] + th[3], 0x88FFFFFF);
	}

	// Sliders fade out quickly after the last scroll interaction
	private float scrollFade() {
		long dt = System.nanoTime() - lastScrollNanos;
		if (dt < 900_000_000L) {
			return 1f;
		}
		return Math.max(0f, 1f - (dt - 900_000_000L) / 400_000_000f);
	}

	// Popup window below the open dropdown, clamped on screen
	private int popupMaxH() {
		if (openDrop == null) {
			return 40;
		}
		return Math.max(40, height - (openDrop.getY() + 20) - 8);
	}

	private static String typeName(DemoElement e) {
		return tr(switch (e.type) {
			case PANEL -> "Панель";
			case PILL -> "Кнопка";
			case ROUND -> "Ячейка";
			case AVATAR -> "Аватар";
			case SQUARE -> "Квадрат";
			case SHARP -> "Резкий";
			case TRIANGLE -> "Треугольник";
			case CIRCLE -> "Круг";
		});
	}

	private static String addTypeNameStatic(int addType) {
		return tr(switch (DemoElement.Type.values()[addType % DemoElement.Type.values().length]) {
			case PANEL -> "панель";
			case PILL -> "кнопку";
			case ROUND -> "ячейку";
			case AVATAR -> "аватар";
			case SQUARE -> "квадрат";
			case SHARP -> "резкий";
			case TRIANGLE -> "треугольник";
			case CIRCLE -> "круг";
		});
	}

	private String addTypeName() {
		return addTypeNameStatic(addType);
	}

	private static float clamp(float v, float min, float max) {
		return Math.max(min, Math.min(max, v));
	}

	private static String fmt(float v) {
		return String.format(java.util.Locale.ROOT, "%.1f", v);
	}

	@Override
	public void onClose() {
		dragging = null;
		if (minecraft != null) {
			minecraft.gui.setScreen(parent);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
