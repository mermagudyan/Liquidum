package com.liquidum.client.config;

// Lab UI language: English default, Russian when forced or auto-detected
public final class LiquidumLang {
	private LiquidumLang() {
	}

	// Russian when config forces ru, English when it forces en, else MC client language
	public static boolean ru() {
		try {
			var cfg = com.liquidum.client.LiquidumCore.getConfig();
			if (cfg != null && cfg.language != null) {
				if (cfg.language.equalsIgnoreCase("ru")) return true;
				if (cfg.language.equalsIgnoreCase("en")) return false;
			}
		} catch (Exception ignored) {
		}
		try {
			var mc = net.minecraft.client.Minecraft.getInstance();
			if (mc != null && mc.getLanguageManager() != null) {
				String code = mc.getLanguageManager().getSelected();
				if (code != null) return code.toLowerCase(java.util.Locale.ROOT).startsWith("ru");
			}
		} catch (Exception ignored) {
		}
		return false;
	}

	// Russian literal in, English out unless ru() — missing keys fall back to input
	public static String tr(String key) {
		if (key == null) return "";
		if (ru()) return key;
		String v = EN.get(key);
		return v == null ? key : v;
	}

	// Translate a Russian option array for dropdowns
	public static java.util.List<String> trList(String[] keys) {
		var out = new java.util.ArrayList<String>(keys.length);
		for (String k : keys) out.add(tr(k));
		return out;
	}

	// Short MC language code for buttons, RU for ru_*, EN for en_*, else upper pair
	public static String langCode() {
		try {
			var mc = net.minecraft.client.Minecraft.getInstance();
			if (mc != null && mc.getLanguageManager() != null) {
				String code = mc.getLanguageManager().getSelected();
				if (code != null && code.length() >= 2) return code.substring(0, 2).toUpperCase(java.util.Locale.ROOT);
			}
		} catch (Exception ignored) {
		}
		return "EN";
	}

	// Our language follows MC, so the button just opens the vanilla menu
	public static void openLanguageMenu(net.minecraft.client.gui.screens.Screen parent) {
		try {
			var cfg = com.liquidum.client.LiquidumCore.getConfig();
			if (cfg != null) {
				cfg.language = "auto";
				cfg.save();
			}
		} catch (Exception ignored) {
		}
		try {
			var mc = net.minecraft.client.Minecraft.getInstance();
			mc.gui.setScreen(new net.minecraft.client.gui.screens.options.LanguageSelectScreen(
				parent, mc.options, mc.getLanguageManager()));
		} catch (Exception ignored) {
		}
	}
	// Same for option lists
	public static java.util.List<String> trList(java.util.List<String> keys) {
		var out = new java.util.ArrayList<String>(keys.size());
		for (String k : keys) out.add(tr(k));
		return out;
	}

	private static final java.util.Map<String, String> EN = java.util.Map.ofEntries(
		java.util.Map.entry("Демо", "Demo"),
		java.util.Map.entry("Эффект", "Effect"),
		java.util.Map.entry("Стекло", "Glass"),
		java.util.Map.entry("Стиль", "Style"),
		java.util.Map.entry("Ещё", "More"),
		java.util.Map.entry("Отладка", "Debug"),
		java.util.Map.entry("Ванильные настройки", "Vanilla settings"),
		java.util.Map.entry("Тягай стекло мышью прямо в окне", "Drag glass with the mouse right in the view"),
		java.util.Map.entry("Слои стекла: что включено", "Glass layers: what is on"),
		java.util.Map.entry("Кнопки - / + меняют стекло сразу", "- / + buttons change glass instantly"),
		java.util.Map.entry("Готовые характеры стекла", "Ready-made glass looks"),
		java.util.Map.entry("Что в игре покрывать стеклом", "What to cover with glass in game"),
		java.util.Map.entry("Инженерный режим: каналы, слои, статусы", "Engineer mode: channels, layers, statuses"),
		java.util.Map.entry("Высота — свет, тень и слияние стекла", "Elevation — light, shadow and glass fusion"),
		java.util.Map.entry(" · Вид: ", " · View: "),
		java.util.Map.entry("Просмотр", "Preview"),
		java.util.Map.entry("показан", "shown"),
		java.util.Map.entry("скрыт", "hidden"),
		java.util.Map.entry("Панель", "Panel"),
		java.util.Map.entry("справа", "right"),
		java.util.Map.entry("слева", "left"),
		java.util.Map.entry("Фон", "Backdrop"),
		java.util.Map.entry("Фактура", "Texture"),
		java.util.Map.entry("плоско", "flat"),
		java.util.Map.entry("сетка", "grid"),
		java.util.Map.entry("Копия HEX", "Copy HEX"),
		java.util.Map.entry("Скопировано", "Copied"),
		java.util.Map.entry("Файл", "File"),
		java.util.Map.entry("Заполнение", "Fit"),
		java.util.Map.entry("Обновить фото", "Reload photo"),
		java.util.Map.entry("Открыть папку", "Open folder"),
		java.util.Map.entry("Стопка", "Stack"),
		java.util.Map.entry("Вразброс", "Scatter"),
		java.util.Map.entry("Вложенность", "Nesting"),
		java.util.Map.entry("Пузыри", "Bubbles"),
		java.util.Map.entry("трава", "Grass"),
		java.util.Map.entry("снег", "Snow"),
		java.util.Map.entry("ад", "Nether"),
		java.util.Map.entry("край", "End"),
		java.util.Map.entry("решётка", "Grid"),
		java.util.Map.entry("ночь", "Night"),
		java.util.Map.entry("песок", "Sand"),
		java.util.Map.entry("мята", "Mint"),
		java.util.Map.entry("слива", "Plum"),
		java.util.Map.entry("свой", "Custom"),
		java.util.Map.entry("фото", "Photo"),
		java.util.Map.entry("нет файла", "no file"),
		java.util.Map.entry("битый файл", "broken file"),
		java.util.Map.entry("Жми Открыть папку и кинь туда jpg/png", "Hit Open folder and drop jpg/png there"),
		java.util.Map.entry("Кликни по стеклу, чтобы выбрать", "Click glass to select"),
		java.util.Map.entry("Фигура", "Shape"),
		java.util.Map.entry("Скругление", "Rounding"),
		java.util.Map.entry("из темы", "from theme"),
		java.util.Map.entry("своё", "custom"),
		java.util.Map.entry("авто", "auto"),
		java.util.Map.entry("Остро", "Crisp"),
		java.util.Map.entry("Мягко", "Soft"),
		java.util.Map.entry("Кругло", "Round"),
		java.util.Map.entry("Круг", "Circle"),
		java.util.Map.entry("Ширина", "Width"),
		java.util.Map.entry("Высота", "Elevation"),
		java.util.Map.entry("высота", "elevation"),
		java.util.Map.entry("Квадрат 1:1", "Square 1:1"),
		java.util.Map.entry("Материал", "Material"),
		java.util.Map.entry("Сбросить переопределения", "Reset overrides"),
		java.util.Map.entry("Текст", "Text"),
		java.util.Map.entry("Текст фигуры", "Shape text"),
		java.util.Map.entry("Видна", "Visible"),
		java.util.Map.entry("Слой", "Layer"),
		java.util.Map.entry("Группа", "Group"),
		java.util.Map.entry("Авто", "Auto"),
		java.util.Map.entry("Объединять", "Merge"),
		java.util.Map.entry("вкл", "on"),
		java.util.Map.entry("выкл", "off"),
		java.util.Map.entry(" (выкл)", " (off)"),
		java.util.Map.entry("Кнопка", "Button"),
		java.util.Map.entry("Ячейка", "Cell"),
		java.util.Map.entry("Аватар", "Avatar"),
		java.util.Map.entry("Квадрат", "Square"),
		java.util.Map.entry("Резкий", "Sharp"),
		java.util.Map.entry("Треугольник", "Triangle"),
		java.util.Map.entry("Прямоугольник", "Rectangle"),
		java.util.Map.entry("Слот", "Slot"),
		java.util.Map.entry("Светлая", "Light"),
		java.util.Map.entry("Плотная", "Dense"),
		java.util.Map.entry("Холод", "Cold"),
		java.util.Map.entry("Тёпл", "Warm"),
		java.util.Map.entry("Роза", "Rose"),
		java.util.Map.entry("Мята", "Mint"),
		java.util.Map.entry("Тон", "Tone"),
		java.util.Map.entry("Вид", "View"),
		java.util.Map.entry("Выбрать следующий", "Select next"),
		java.util.Map.entry("Старый дебаг", "Legacy debug"),
		java.util.Map.entry("Диагностика в лог", "Dump diagnostics"),
		java.util.Map.entry("Падение при ошибке", "Crash on error"),
		java.util.Map.entry("Геометрия слотов", "Slot geometry"),
		java.util.Map.entry("Эксперимент", "Experimental"),
		java.util.Map.entry("Купол (игнор)", "Dome (ignored)"),
		java.util.Map.entry("Солнечный блик", "Sun glare"),
		java.util.Map.entry("Слипание плиток", "Tile fusion"),
		java.util.Map.entry("Радиус слипания", "Fusion radius"),
		java.util.Map.entry("Ореол: сердца", "Halo: hearts"),
		java.util.Map.entry("Ореол: голод", "Halo: hunger"),
		java.util.Map.entry("Ореол: броня", "Halo: armor"),
		java.util.Map.entry("Ореол: воздух", "Halo: air"),
		java.util.Map.entry("Ореол: XP-полоса", "Halo: XP bar"),
		java.util.Map.entry("Плавный скролл", "Smooth scroll"),
		java.util.Map.entry("Оптика", "Optics"),
		java.util.Map.entry("Матовость", "Matte"),
		java.util.Map.entry("Свечение кромки", "Edge glow"),
		java.util.Map.entry("Радуга на краях", "Edge rainbow"),
		java.util.Map.entry("Поведение", "Behavior"),
		java.util.Map.entry("Блик за курсором", "Cursor flare"),
		java.util.Map.entry("Параллакс", "Parallax"),
		java.util.Map.entry("Анимация открытия", "Open animation"),
		java.util.Map.entry("Свет", "Light"),
		java.util.Map.entry("Источник", "Source"),
		java.util.Map.entry("ручной", "manual"),
		java.util.Map.entry("из мира", "world"),
		java.util.Map.entry("Угол света", "Light angle"),
		java.util.Map.entry("Сила света", "Light level"),
		java.util.Map.entry("Преломление", "Refraction"),
		java.util.Map.entry("Ширина края", "Edge width"),
		java.util.Map.entry("Кромка", "Edge"),
		java.util.Map.entry("Чёткость", "Sharpness"),
		java.util.Map.entry("Радиус мата", "Frost radius"),
		java.util.Map.entry("Цвет тела", "Body tint"),
		java.util.Map.entry("Цвет края", "Edge tint"),
		java.util.Map.entry("Хрома", "Chroma"),
		java.util.Map.entry("Оттенок", "Tint"),
		java.util.Map.entry("Эффекты: сохранить", "Effects: save"),
		java.util.Map.entry("Сохранить А ✓", "Save A ✓"),
		java.util.Map.entry("Сохранить А", "Save A"),
		java.util.Map.entry("Сохранить Б ✓", "Save B ✓"),
		java.util.Map.entry("Сохранить Б", "Save B"),
		java.util.Map.entry("Применить А", "Apply A"),
		java.util.Map.entry("Применить Б", "Apply B"),
		java.util.Map.entry("Сброс", "Reset"),
		java.util.Map.entry("Сейчас", "Now"),
		java.util.Map.entry("Своя", "Custom"),
		java.util.Map.entry("Мод включён", "Mod enabled"),
		java.util.Map.entry("Стекло на кнопках", "Button glass"),
		java.util.Map.entry("Стекло на хотбаре", "Hotbar glass"),
		java.util.Map.entry("Стекло на слотах", "Slot glass"),
		java.util.Map.entry("Умный док", "Smart dock"),
		java.util.Map.entry("Язык", "Language"),
		java.util.Map.entry("Сохранить ●", "Save ●"),
		java.util.Map.entry("Сохранено ✓", "Saved ✓"),
		java.util.Map.entry("Тест", "Test"),
		java.util.Map.entry("Добавить", "Add"),
		java.util.Map.entry("Готово", "Done"),
		java.util.Map.entry("Копия", "Copy"),
		java.util.Map.entry("Удалить", "Delete"),
		java.util.Map.entry("панель", "panel"),
		java.util.Map.entry("кнопку", "button"),
		java.util.Map.entry("ячейку", "cell"),
		java.util.Map.entry("аватар", "avatar"),
		java.util.Map.entry("квадрат", "square"),
		java.util.Map.entry("резкий", "sharp"),
		java.util.Map.entry("треугольник", "triangle"),
		java.util.Map.entry("круг", "circle"),
		java.util.Map.entry("Выбрано", "Selected"),
		java.util.Map.entry("Сцена", "Scene"),
		java.util.Map.entry("Видео", "Video"),
		java.util.Map.entry("Дисплей", "Display"),
		java.util.Map.entry("Качество", "Quality"),
		java.util.Map.entry("Прочее", "Misc"),
		java.util.Map.entry("Нужен рестарт", "Restart required"),
		java.util.Map.entry("Настройки", "Settings"),
		java.util.Map.entry("Звук", "Sound"),
		java.util.Map.entry("Управление", "Controls"),
		java.util.Map.entry("Громкость", "Volume"),
		java.util.Map.entry("объекта", "objects"),
		java.util.Map.entry("групп", "groups")
	);
}
