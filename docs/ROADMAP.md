# Liquidum — Roadmap

## Концепция
Мод для Minecraft, который применяет эффект Apple Liquid Glass на UI-элементы:
кнопки, инвентарь, панели, слоты, меню паузы.

---

## Архитектура рендеринга

### Конвейер (Pipeline) — РЕАЛИЗОВАН на MC 26.2 (PostChain)
```
0. GameRenderer.extract   → сбор состояния кадра (ваниль)
1. Мир/панорама           → отрисована в minecraft:main (ваниль)
2. GuiRenderer.draw HEAD  → ХУК Liquidum: кадр содержит ТОЛЬКО мир
3. PostChain liquidum:glass:
   a. box_blur X/Y ×2     → target "blurred" (радиус 12)
   b. glass pass          → SDF-панели: блюр + рефракция + Френель,
                            сэмплы main (резкий) + blurred
   c. blit                → результат обратно в minecraft:main
4. GUI-слои               → текст/кнопки рисуются ПОВЕРХ стекла (чётко)
5. present
```
**Ключевые факты 26.2 (проверено экспериментально):**
- Отдельный `CommandEncoder` НЕ исполняется на GPU — вся кастомная отрисовка только через PostChain.
- `innerBlit`/`guiGraphics.blit` игнорируют кастомные пайплайны — шейдер менять нельзя.
- Динамические uniform'ы: подмена UBO пасса через accessor (`PostPass.customUniforms`).
- Неудачный `getPostChain` кэшируется навсегда → вызывать только после загрузки ресурсов (`mc.gui.overlay() == null`).
- Точка вызова — `GuiRenderer.draw()` HEAD: мир уже готов, UI ещё нет (стекло ПОД контентом).
- Stencil не нужен: маски форм считаются в шейдере через SDF.

### Старый план (НЕ рабочий на 26.2, оставлено для истории)
```
1. FBO Capture     → Захват мира за UI (TextureTarget) ❌ encoder мёртв
2. Blur Pass       → Даунсэмпл в 4x + Gaussian Blur (3 прохода) ✅ (через box_blur x4)
3. Stencil Pass    → ❌ заменён на SDF-маску в фрагментном шейдере
4. Glass Pass      → ✅ рефракция + Френель
5. Text Pass       → ✅ автоматически (UI рисуется после стекла)
6. Gloss Pass      → ✅ ободок с бликом в glass pass
```

### Что берём из Apple Liquid Glass
| Приём Apple | Адаптация в Minecraft |
|---|---|
| Text-to-Path | Stencil buffer — форма виджета как маска |
| SDF-рефракция | Формула линзы в шейдере (сдвиг от центра к краям) |
| Vibrant Material | Анализ яркости фона → авто-подсветка/затемнение |
| Specular Highlight | Обводка 0.5px с глянцом по краю стекла |
| Layered icons | Параллакс-сдвиг иконки относительно фона |
| Гироскоп | Замена на движение мыши → стекло реагирует на курсор |

---

## Уровни реализации

### Уровень 1 — Базовый ✅ ЗАКРЫТ (проверено в игре)
- [x] FBO capture — через PostChain-сэмпл `minecraft:main`
- [x] Gaussian blur — 4× box_blur (X/Y ×2), таргет "blurred"
- [x] SDF-маски форм — roundedBoxSDF в фрагментном шейдере
- [x] Glass-шейдер (рефракция + Френель + хроматическая аберрация)
- [x] Точка вызова — `GuiRendererMixin` → HEAD `GuiRenderer.draw()`
- [x] Мультипанели из реальных координат виджетов (128 слотов, рекурсивный обход детей)
- [x] Динамические uniform'ы — подмена UBO пасса (`PostPassAccessor`)
- [x] Отказоустойчивость: direct-load цепочки (мимо кэша), восстановление после F3+T

### Уровень 2 — Средний (в процессе)
- [x] Хроматическая аберрация (RGB-сплит по силе линзы)
- [x] Френель-ободок (верхняя кромка)
- [x] Vibrant Material (luminance-адаптация, частично)
- [ ] Hover-эффект (стекло реагирует на курсор)
- [ ] SDF-слияние близких кнопок (smooth minimum)
- [ ] Докрутить линзу до айфон-вида (сила/зона/аберрация — параметрически)
- [ ] Клип плиток от перекрытия хотбаром/панелями
- [ ] Титульный экран: стекло поверх панорамы (страта-граница)
- [ ] Анимация появления/исчезновения окон
- [ ] Инвентарь/контейнеры/хотбар (Stage 4 — отдельные миксины на контейнеры)

### Форма виджетов — SDF скругления
Каждый виджет (кнопка, слот, панель) — прямоугольник со скруглением 3-5px.
SDF (Signed Distance Field) определяет расстояние пикселя до границы формы:
- Внутри (edgeFactor ≈ 0) — стекло плоское, минимальная рефракция
- На краю (edgeFactor ≈ 1) — стекло выпуклое, сильная рефракция + Френель
- Скругление角落 — 3-5px, плавный переход

Формула: `roundedBoxSDF(p, center, halfSize, radius)`
Результат: edgeFactor = 1.0 - smoothstep(-0.05, 0.0, sdf)
Рефракция: `refractionOffset = normalize(uv - center) * edgeFactor * strength`

### Уровень 2 — Средний
- [ ] Хроматическая аберрация (из liquid-glass-main)
- [ ] Hover-эффект (стекло реагирует на курсор) (из ReGlass)
- [ ] SDF-слияние близких кнопок (smooth minimum) (из ReGlass)
- [ ] Vibrant Material (адаптивная яркость)
- [ ] Specular Highlight (обводка с глянцем)
- [ ] Анимация появления/исчезновения окон (из SmoothGui)
- [ ] Glass-эффект на элементах инвентаря (слоты, контейнеры)
- [ ] Glass-эффект на хотбаре (из smoothHud)
- [ ] Glide предметов между слотами (из Smooth-Swapping)

### Уровень 3 — Продвинутый
- [ ] Параллакс-иконки (layered system)
- [ ] Тёмное/светлое стекло (Dark Mode)
- [ ] Кастомные цвета стекла
- [ ] Свайп-инвентарь (как центр уведомлений iPhone)
- [ ] Свайп-закрытие инвентаря (вверх по пустому месту)
- [ ] Glass-логотип Mojang при перезагрузке текстурпака
- [ ] Elastic Overscroll — эластичная деформация при прокрутке за пределы
- [ ] Creative Tab Stack — анимация переключения вкладок в креативе

### Elastic Overscroll — эластичная деформация

Эффект "жидкого стекла" при прокрутке за пределы (как overscroll на iPhone).

**Где применять:**
- Прокрутка инвентаря (только вверх/вниз) — слот вытягивается как капля
- Overscroll в настройках — последний элемент деформируется
- Прокрутка списка миров (если вписывается)

**Где НЕ применять:**
- Перетаскивание предметов — излишне
- Горизонтальная прокрутка — нет эффекта

**Как работает:**
- Слот вытягивается по направлению скролла
- Края истончаются и прозрачнеют
- Центр сохраняет плотность
- При отпускании — пружинный snap-back
- Динамическая жёсткость: чем сильнее тянешь, тем больше сопротивление

**Источники:** animated-gui-main (Tween + Easing), LiquidGlass-master (SDF deform)

### Creative Tab Stack — анимация переключения вкладок

Система стека вкладок в креативном инвентаре с анимацией.

**Как работает:**
```
Начальное состояние: вкладка "Building Blocks"
╭──╮╭──╮╭──╮╭──╮
│🏗️││⚔️││🧪││📦│  ← вкладки
╰──╯╰──╯╰──╯╰──╯

Нажал "Combat" (вкладка 2):
  Building Blocks: уезжает вниз ↓
  Combat: появляется снизу ↑

Нажал "Spawn Eggs" (вкладка 3):
  Combat: уезжает вниз ↓
  Spawn Eggs: появляется снизу ↑

Вернулся в "Building Blocks":
  Spawn Eggs: уезжает вниз ↓
  Building Blocks: появляется снизу ↑

Нажал на вкладку инвентаря игрока:
  Все вкладки: уезжают по очереди сверху вниз ↓
```

**Стек в памяти:**
- Каждое переключение = push в стек
- Возврат на предыдущую = pop из стека
- Вкладка инвентаря = очистка стека (все закрываются)

**Анимация:**
- Появление: снизу вверх (easeOutCubic)
- Исчезновение: сверху вниз (easeInCubic)
- Скорость: 300-400ms

**Источники:** animated-gui-main (ScreenAnimController), SmoothGui (EasingStyle)

### Glass-логотип Mojang

При перезагрузке текстурпака вместо обычной текстуры — glass-версия логотипа:
- Stencil-маска по форме логотипа
- Внутри — преломление фона (refraction)
- По краям — Френель + блик
- Фон — размытый мир или чёрный
- Анимация появления (от прозрачного к матовому)
- Блик бежит по краям
- При завершении загрузки — растворяется

Mixin на `LoadingOverlay` / `SplashScreen` — перехват рендеринга логотипа.

### Стили текста (Glass / Monolith)

**Glass (Стекло):**
- Полностью прозрачный текст
- Видно только искажение стекла по контуру букв
- Слабое преломление по краям
- Параллакс (текст смещается при движении мыши)

**Monolith (Монолит):**
- Матовый, более белый текст
- Нет эффекта стекла и параллакса
- Слабый блюр сзади
- Небольшая прозрачность с передачей цвета фона

**Где применять:**
- Заголовки экранов ("Настройки", "Игра") — ✅ Monolith
- Splash-текст — ✅ Glass
- Заголовки секций — ⚠️ Средне

**Где НЕ применять:**
- Текст на кнопках — ❌ мелкий, нечитаемо
- Названия предметов — ❌ мелкий
- Чат — ❌ должен быть чётким
- Координаты/отладка — ❌ информационный текст
- Тултипы — ❌ мелкий шрифт

**Проблема:** Minecraft рендерит текст как растровые битмапы (8x8 px). Эффект виден только на большом тексте (20+ px).

### Scale-font подход

Рендерим текст в 4x размере → применяем glass → уменьшаем обратно в 1x.

```
Ванильный шрифт (8x8):
Рендерим в 4x → 32x32 → glass-эффект → обратно в 8x8

┌──────────┐     ┌──────────────────┐     ┌──────────┐
│░░▓▓▓▓░░░░│ →   │░░░░░░░░▓▓▓▓▓▓░░░░│ →   │░░▓▓▓▓░░░░│
│░▓░░░░▓░░░│ 4x  │░░░░░▓▓░░░░░░░▓▓░░│ 1x  │░▓░░░░▓░░░│
│░░▓▓▓▓░░░░│     │░░░░░░░░▓▓▓▓▓▓░░░░│     │░░▓▓▓▓░░░░│
└──────────┘     └──────────────────┘     └──────────┘
 8x8               32x32 + glass            8x8 (гладкое)
```

**Где применять:**
- Заголовки экранов (scale 4x) ✅
- Splash-текст (scale 4x) ✅
- Логотип Mojang (scale 8x) ✅

**Где НЕ применять:**
- Чат (мелко, нечитаемо) ❌
- Тултипы (мелко) ❌
- Кнопки (средне, зависит от шрифта) ⚠️

---

## Luminance Dock — Слоёный HUD с追随光源

### Концепция
HUD-элементы (здоровье, голод, броня, опыт) — не просто стекло,
а **многослойная система**, где иконки парят над стеклянной подложкой,
а блик на стекле следует за источником света в мире.

### Три слоя
```
Слой 1 (передний):  Иконки/текст — парят над стеклом (3D-глубина)
Слой 2 (средний):   Стеклянные подложки элементов (glass + Френель)
Слой 3 (задний):    Мир за HUD (размытый фон)
```

### Блик следует за светом
Вместо гироскопа (iPhone) — направление света в Minecraft:

| Ситуация | Эффект |
|---|---|
| День, солнце | Блик бежит по верхнему краю сердечек |
| Закат | Блик оранжевый, сбоку |
| У костра | Блик снизу, тёплый |
| Ночь | Блика нет, стекло тёмное |
| Пещера | Блик от факела рядом |

### Получение LightDirection из мира
```java
float sunAngle = level.getSunAngle();
Vec3 lightDir = new Vec3(cos(sunAngle), sin(sunAngle), 0.0);
```

### Шейдер блика
```glsl
uniform vec3 LightDirection;
float specular = max(0.0, dot(normalize(LightDir.xy), normalize(localUV)));
float edgeGlow = smoothstep(0.3, 0.5, sdf) * specular;
```

### Применение к элементам

| Элемент | Количество слоёв | Форма |
|---|---|---|
| Здоровье (9 сердечек) | 9 плиток | Квадраты |
| Голод (10 штук) | 10 плиток | Квадраты |
| Броня (10 штук) | 10 плиток | Квадраты |
| Опыт-бар | 1 панель | Длинный прямоугольник |
| Хотбар (9 слотов) | 9 плиток | Квадраты |

---

## Полный список GUI элементов Minecraft 26.x

### ✅ Полностью glass (30 элементов, 70%)

#### Меню и экраны
| Элемент | Статус | Как |
|---|---|---|
| Главное меню (кнопки) | ✅ | Glass на кнопках |
| Пауза (кнопки) | ✅ | Glass на кнопках |
| Настройки (кнопки/слайдеры) | ✅ | Glass на кнопках |
| Настройки графики | ✅ | Glass на кнопках |
| Настройки управления | ✅ | Glass на кнопках |
| Настройки звука | ✅ | Glass на кнопках |
| Кнопка "Назад" | ✅ | Glass на кнопке |
| Смерть (Respawn/Title) | ✅ | Glass на кнопках |
| Статистика | ✅ | Glass на кнопках |
| Достижения | ✅ | Glass на вкладках |

#### Инвентарь и крафт
| Элемент | Статус | Как |
|---|---|---|
| Инвентарь игрока (E) | ✅ | Glass на слотах |
| Крафт 2x2 | ✅ | Glass на 4 слотах |
| Результат крафта | ✅ | Glass на слоте |
| Слоты брони (4) | ✅ | Glass на слотах |
| Off-hand слот | ✅ | Glass на слоте |
| Фон панели инвентаря | ✅ | Glass |
| Верстак (3x3) | ✅ | Glass на 9 слотах |
| Рецепты (E→R) | ✅ | Glass на вкладках |

#### Контейнеры
| Элемент | Статус | Как |
|---|---|---|
| Сундук (9/18/27/54) | ✅ | Glass на слотах |
| Сундук-шалкер | ✅ | Glass на слотах |
| Бочка (3x3) | ✅ | Glass на слотах |
| Диспенсер (3x3) | ✅ | Glass на слотах |
| Дроппер (3x3) | ✅ | Glass на слотах |

#### Мастерские
| Элемент | Статус | Как |
|---|---|---|
| Печка | ✅ | Glass на слотах + полоска |
| Бланшильная печь | ✅ | Glass на слотах + полоска |
| Дымовая печь | ✅ | Glass на слотах + полоска |
| Наковальня | ✅ | Glass на слотах + полоска |
| Бродильня | ✅ | Glass на слотах |
| Лавочник | ✅ | Glass на слотах |
| Ткацкий станок | ✅ | Glass на слотах |
| Чернильный станок | ✅ | Glass на слотах |
| Кузнечник | ✅ | Glass на слотах |
| Гончарная печь | ✅ | Glass на слотах |
| Чистильная станция | ✅ | Glass на слотах |

#### HUD
| Элемент | Статус | Как |
|---|---|---|
| Хотбар (9 слотов) | ✅ | Glass на слотах |
| Выбранный слот | ✅ | Яркая подсветка |
| Крестик (прицел) | ✅ | Лёгкое свечение |

#### Креатив
| Элемент | Статус | Как |
|---|---|---|
| Креатив (вкладки) | ✅ | Glass + Creative Tab Stack |
| Креатив (слоты) | ✅ | Glass на слотах |

#### Подсказки и окна
| Элемент | Статус | Как |
|---|---|---|
| Тултипы (фон) | ✅ | Glass на фоне |
| Scoreboard | ✅ | Glass на фоне |
| Boss-бар | ✅ | Glass на панели |
| Tab (список игроков) | ✅ | Glass на фоне |
| Книга рецептов | ✅ | Glass на вкладках |
| Иконки статус-эффектов | ✅ | Glass-подложки |
| Тосты (уведомления) | ✅ | Glass на фоне |

### ⚠️ Ограниченно glass (5 элементов, 15%)

| Элемент | Статус | Проблема |
|---|---|---|
| Чат (фон) | ⚠️ | Только фон, текст без стекла |
| Тултипы (текст предмета) | ⚠️ | Фон glass, текст мелкий (8px) |
| Здоровье (Luminance Dock) | ⚠️ | Glass-плитки, но не мешать читаемости |
| Голод (Luminance Dock) | ⚠️ | Glass-плитки |
| Опыт-бар (Luminance Dock) | ⚠️ | Glass-панель, цифры чёткие |

### ❌ Без glass (5 элементов, 15%)

| Элемент | Статус | Причина |
|---|---|---|
| F3 (отладка) | ❌ | Информационный текст |
| Редактор книг | ❌ | Нужна функциональность письма |
| Редактор табличек | ❌ | Нужна функциональность |
| Блок команд | ❌ | Нужна функциональность |
| Structure/Jigsaw | ❌ | Слишком сложный UI |

### Статистика

| Категория | Количество | Процент |
|---|---|---|
| ✅ Полностью glass | 30 | 70% |
| ⚠️ Частично glass | 5 | 15% |
| ❌ Без glass | 5 | 15% |
| **Итого** | **40** | **100%** |

### Ключевое правило

**Критические элементы (здоровье, голод, опыт, чат) ВСЕГДА остаются читаемыми.**
Glass-эффект — декоративный, не функциональный.
Даже при max glass — иконки/текст поверх стекла чёткие.

---

## Элементы выживания — полный список

### HUD (на экране в мира)

| Элемент | Glass | Комментарий |
|---|---|---|
| Хотбар (9 слотов) | ✅ | Каждый слот — стеклянная плитка |
| Выбранный слот | ✅ | Яркая подсветка стеклом |
| Крестик (прицел) | ⚠️ | Лёгкое свечение, без преломления |
| Здоровье (сердечки) | ⚠️ | Luminance Dock (слой 2+3) |
| Голод | ⚠️ | Luminance Dock |
| Броня | ⚠️ | Luminance Dock |
| Опыт-бар | ⚠️ | Luminance Dock (панель) |
| Текстовое действие | ✅ | "Попробуй нажать E" — лёгкое стекло |

### Инвентарь (E)

| Элемент | Glass | Комментарий |
|---|---|---|
| Сетка инвентаря (27 слотов) | ✅ | Каждый слот — стеклянная плитка |
| Сетка крафта (2x2) | ✅ | 4 слота стеклом |
| Слот результата крафта | ✅ | Яркое стекло |
| Слоты брони (4) | ✅ | Стекло с иконкой брони |
| Off-hand слот | ✅ | Стекло |
| Предпросмотр персонажа | ⚠️ | Фон стекло, персонаж поверх |
| Фон панели | ✅ | Полупрозрачное стекло |

### Контейнеры

| Элемент | Glass | Комментарий |
|---|---|---|
| Сундук (9/18 слотов) | ✅ | Сетка стеклянных плиток |
| Верстак (3x3) | ✅ | 9 слотов стеклом |
| Печка | ✅ | Слоты + полоска прогрева |
| Наковальня | ✅ | Слоты + полоска опыта |
| Бродильня | ✅ | Слоты стеклом |
| Диспенсер | ✅ | Сетка 3x3 |
| Сундук-шалкер | ✅ | Как обычный сундук |
| Бочка | ✅ | Сетка 3x3 |

### Подсказки и окна

| Элемент | Glass | Комментарий |
|---|---|---|
| Тултипы предметов | ✅ | Glass-фон подсказки |
| Окно чата | ✅ | Glass-фон чата |
| Список игроков (Tab) | ✅ | Glass-фон списка |
| Табло (Scoreboard) | ✅ | Glass-фон табло |
| Boss-бар | ✅ | Glass-панель |
| Книга рецептов | ✅ | Glass-вкладки и иконки |
| Иконки статус-эффектов | ✅ | Glass-подложки |
| Тосты (уведомления) | ✅ | Glass-фон тоста |
| Экран смерти | ✅ | Glass-кнопки |
| Креатив (вкладки) | ✅ | Glass-вкладки |

### Что НЕ трогать

| Элемент | Причина |
|---|---|
| Здоровье/Голод/Броня | Критическая инфа, нельзя замыливать |
| Опыт-бар | Текст должен быть читаем |
| Крестик | Отвлекает от прицеливания |
| Текст чата | Должен быть чётким |
| F3 (отладка) | Слишком много текста |
| Редактор табличек | Нужна функциональность |
| Редактор книг | Нужна функциональность |
| Блок команд | Нужна функциональность |
| Structure/Jigsaw | Нужна функциональность |

---

## Интеграция с другими модами

### Обязательная поддержка
- JEI/REI — mixin на их виджеты
- Anything ванильный — инвентарь, верстак, печка, анvil и т.д.

### Опциональная поддержка
- AnyScreen — проверка не ванильный ли экран
- Inventory Profiles Next — mixin на автосортировку

### Совместимость (не ломать)
- OptiFine — detect → fallback на свой рендер или отключить
- Sodium/Rubidium — не影响ает UI, конфликтов нет
- Iris — опциональная интеграция (см. ниже)
- Лицензия мода: LGPLv3 (другие моды могут использовать как библиотеку)

---

## Аппаратное ускорение

### Что берём у Apple

| Apple | Аналог в Minecraft |
|---|---|
| Metal API | OpenGL / Vulkan |
| Tile-based rendering | Iris тайловый рендеринг |
| Neural Engine | Нет аналога |
| Аппаратный блюр | Gaussian blur через шейдер |
| Framebuffer cache | FBO reuse |

### Варианты ускорения

| Метод | Что даёт | Сложность |
|---|---|---|
| **Iris** | Готовый конвейер, тайловый рендеринг | Низкая |
| **Vulkan** (через VulkanMod) | Современный API, меньше overhead, больше FPS | Средняя |
| **Canvas Renderer** | Альтернативный рендер-движок | Средняя |
| **Compute Shaders** | Параллельные вычисления на GPU | Высокая |
| **Multi-pass** | Разбивка на простые проходы | Средняя |

### Целевой стек ускорения
```
Без Vulkan:  OpenGL + Iris (fallback)
С Vulkan:   VulkanMod + Iris +我们的шейдеры
```

### Почему Vulkan быстрее

| OpenGL | Vulkan |
|---|---|
| Один поток команд | Мультипоток |
| Высокий overhead | Минимальный overhead |
| Медленная компиляция шейдеров | Быстрая компиляция |
| Ограничение на draw calls | Тысячи draw calls |
| ~300 FPS | ~500+ FPS |

### Интеграция с VulkanMod

**VulkanMod** — мод, который заменяет OpenGL на Vulkan в Minecraft.

**Что даёт:**
- +40-60% FPS по сравнению с OpenGL
- Меньше stuttering (фризов)
- Лучшая multi-threading
- Поддержка Vulkan 1.2+

**Как интегрировать:**
1. Проверяем наличие VulkanMod в runtime
2. Если VulkanMod есть → используем Vulkan-совместимые шейдеры
3. Если нет → fallback на OpenGL

**Проблема:**
- VulkanMod не стабилен на всех версиях
- Нужно поддерживать два backend (OpenGL + Vulkan)
- Шейдеры могут отличаться

### Практический вывод

**Для нашего мода:**
- **Iris** — лучший вариант для ускорения (если доступен)
- **VulkanMod** — ещё +40-60% FPS (опционально)
- **Fallback на raw OpenGL** — если ничего нет
- **Multi-pass** — разбиваем blur на 2 прохода
- **Downsampling** — сжимаем текстуру перед blur

**Целевая производительность:**
- OpenGL: 60+ FPS
- OpenGL + Iris: 90+ FPS
- Vulkan + Iris: 120+ FPS

---

## Интеграция с Iris (опционально)

### Стратегия
```
Без Iris → Raw OpenGL (fallback, работает всегда)
С Iris   → Iris API (быстрее, тайловый рендеринг)
```

### Iris API который используется
- `IrisApi.registerCustomShader()` — регистрация кастомного шейдера
- `Iris.getFramebuffer()` — переиспользование FBO вместо создания нового
- PostChain API — пост-обработка

### Ограничения
- Iris API не стабилен, может сломаться в новой версии
- Жёсткая зависимость от Iris нежелательна
- Fallback обязателен

### Когда интегрировать
- Только после стабильной базовой версии
- Только если Iris API стабилен в текущей версии

---

## Исходные моды (папка C:\dev\Liquidum_dev\)

### Ядро рендеринга
- `LiquidGlassRenderer` — FBO capture, blur, glass shader
- `StencilHelper` — управление stencil buffer
- `GlassShader` — компиляция и привязка шейдеров

### Библиотеки (из папки с модами)
| Источник | Классы | Назначение |
|---|---|---|
| ReGlass | `liquid_glass_gui.fsh`, `sdgBox()`, `opSmoothUnion()` | Glass-шейдер, SDF |
| animated-gui | `Tween.java`, `Easing.java` | Движок анимаций |
| glsl-fast-gaussian-blur | `blur5()`, `blur9()`, `blur13()` | Blur |
| liquid-glass-main | `sdCircle()`, `getShadow()` | Рефракция, тени |
| LiquidGlass-master | `sdSuperellipse()` | Скруглённые прямоугольники |
| SmoothGui | `ScreenAnimController.java` | Анимации экранов |
| smoothHud | `InGameHudMixin.java` | Хотбар |
| Smooth-Swapping | `SwapUtil.java` | Glide предметов |
| freecursor | Концепция | Свайп-инвентарь |

### Миксины
- `ScreenMixin` — перехват Screen.extractBackground
- `WidgetMixin` — перехват AbstractWidget.render
- `InventoryScreenMixin` — перехват инвентаря

### Утилиты
- `EasingUtil` — 15+ easing-функций
- `SDFUtil` — smooth union, pill, rounded box
- `ColorUtil` — анализ яркости фона

---

## Шейдеры

### liquid_glass.fsh
- Рефракция по Снеллиусу (refract)
- Френель (Schlick's approximation)
- Хроматическая аберрация (RGB split)
- Vibrant Material (адаптивная яркость)

### blur.fsh
- 5-tap weighted Gaussian
- Даунсэмпл через GL_LINEAR

### stencil_mask.fsh
- Рисование маски виджета
- Сглаживание краёв (anti-aliasing)

---

## Производительность

### Оптимизации
- Даунсэмпл фона в 4x перед blur
- Кеширование размытого фона (не пересчитывать каждый кадр)
- Scissor test — glass только в видимой области
- Tile-based rendering (через Iris если доступно)

### Целевая производительность
- 60+ FPS на среднем ПК
- 120+ FPS на мощном ПК
- Динамическое качество при просадках FPS

---

## Что берём из каждого мода

### 100% подходит (MIT/CC0)

| Мод | Что берём | Куда интегрируем |
|---|---|---|
| **ReGlass-master** | `liquid_glass_gui.fsh` (SDF, blur, refraction, Fresnel, bloom), `sdgBox()`, `opSmoothUnion()`, `LiquidGlassUniforms.java` | Ядро glass-рендеринга |
| **animated-gui-main** | `Tween.java`, `Easing.java` (8 кривых), логика.glide предметов, `ScreenAnimController.java` | Движок анимаций |
| **glsl-fast-gaussian-blur** | `blur5()`, `blur9()`, `blur13()` | Blur-шейдер |
| **liquid-glass-main** | `sdCircle()`, `getShadow()`, `getHighlight()`, `refrakt()` | Математика glass-эффекта |
| **LiquidGlass-master** | `sdSuperellipse()`, `LiquidGlass()`, batch renderer | Скруглённые прямоугольники |
| **SmoothGui-main** | Контроллер анимаций экранов, конфигурация | Анимация открытия/закрытия |
| **smoothHud-main** | Логика интерполяции хотбара | Glass-хотбар, горизонтальная прокрутка |
| **ReGlass-master** | SDF metaballs, elastic deformation | Elastic Overscroll |
| **animated-gui-main** | Tween, Easing, ScreenAnimController | Creative Tab Stack |

### 70% подходит (адаптация LGPL/GPL)

| Мод | Что берём | Адаптация |
|---|---|---|
| **AutoHUD-main** | Концепция fade-in/out HUD | Взять идею, не код |
| **Minecraft-Smooth-Scrolling** | Концепция плавного скролла | Взять идею, не код |
| **freecursor-main** | Концепция освобождения курсора | Адаптировать для свайп-инвентаря |

### Лицензия
**LGPLv3** (GNU Lesser General Public License)
- Другие моды могут использовать как библиотеку без ограничений
- Изменения в самом API должны быть открыты
- Коммерческое использование разрешено как зависимость

---

## Freecursor — Свайп-инвентарь (как центр уведомлений iPhone)

### Концепция
Повторяем жесты iPhone:
- **Свайп вниз** → инвентарь появляется сверху (как Центр уведомлений)
- **Свайп вверх** → инвентарь закрывается
- Анимация: сначала статичное появление, потом лёгкий elastic-эффект

### Как работает на iPhone
1. Палец касается верхнего края экрана
2. Свайп вниз → шторка инвентаря выезжает
3. Отпустил → инвентарь остаётся открытым
4. Свайп вверх по пустому месту → шторка уезжает обратно

### Поведение анимации
- Сначала: статичное появление (просто выезжает)
- Потом: лёгкий elastic-эффект (элементы слегка деформируются при достижении края)

### Адаптация для Minecraft
```
Открытие:
1. Игрок нажимает E (или свайп вниз если touchscreen)
2. Инвентарь выезжает сверху с glass-эффектом
3. Позиция Y: от -height до 0 (плавная анимация)

Закрытие:
1. Свайп вверх по пустому месту инвентаря
2. Или нажатие E
3. Или нажатие Esc
4. Инвентарь уезжает вверх с glass-эффектом
```

### Техническая реализация
```java
// Mixin на MouseHandler для перехвата свайпа
@Inject(method = "onScroll", at = @At("HEAD"))
private void onScroll(double x, double y, double dx, double dy, CallbackInfo ci) {
    if (isInventoryOpen() && dy < 0) { // свайп вверх
        closeInventoryWithAnimation();
    }
}

// Mixin на Screen для свайпа вниз
@Inject(method = "mouseDragged", at = @At("HEAD"))
private void onDrag(double x, double y, int button, double dx, double dy, CallbackInfo ci) {
    if (!isInventoryOpen() && y < 10 && dy > 0) { // свайп вниз от верхнего края
        openInventoryWithAnimation();
    }
}
```

### Анимация
```
Свайп вниз (открытие):
┌──────────────────────┐
│▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓│ ← инвентарь выезжает
│  слоты инвентаря     │    с glass-эффектом
│  персонаж           │
╰──────────────────────╯
      ↑ speed = easeOutCubic

Свайп вверх (закрытие):
╭──────────────────────╮
│                      │ ← инвентарь уезжает
│                      │    обратно
╰──────────────────────╯
      ↑ speed = easeInCubic
```

---

## Elastic Overscroll — детали

### Прокрутка инвентаря (только вверх/вниз)
```
Нормально:
╭──╮╭──╮╭──╮╭──╮
│  ││  ││  ││  │
╰──╯╰──╯╰──╯╰──╯

Достиг дна:
╭──╮╭──╮╭──╮╭──╮
│  ││  ││  ││  │
╰──╯╰──╯╰──╯╰──╯
              ↓
           ╭──────╮
           │▓▓▓▓▓▓│  ← слот вытягивается как капля
           │▓▓▓▓▓▓│
           ╰──────╯

Snap-back (пружина)
```

### Настройки (overscroll в списке)
```
Нормально:
╭──────────────────────╮
│ Громкость             │
│ Музыка                │
│ Звуки                 │
╰──────────────────────╯

За пределы:
╭──────────────────────╮
│ Громкость             │
│ Музыка                │
│▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓│  ← последний элемент деформируется
╰──────────────────────╯

Snap-back
```

### Что НЕ деформировать
- Перетаскивание предметов — излишне
- Горизонтальная прокрутка — нет эффекта (use smoothHud)
- Список миров — излишне

---

## Creative Tab Stack — детали

### Стек в памяти
```
Стек: [Building Blocks]  ← начальное состояние

Нажал Combat:
Стек: [Building Blocks, Combat]
Анимация: Building Blocks ↓, Combat ↑

Нажал Spawn Eggs:
Стек: [Building Blocks, Combat, Spawn Eggs]
Анимация: Combat ↓, Spawn Eggs ↑

Вернулся в Building Blocks:
Стек: [Building Blocks]
Анимация: Spawn Eggs ↓, Building Blocks ↑

Нажал инвентарь игрока:
Стек: []  ← очистка
Анимация: все вкладки ↓ по очереди
```

### Скорость анимации
- Появление: 300ms, easeOutCubic
- Исчезновение: 300ms, easeInCubic
- Задержка между вкладками: 50ms

---

## Настройки (Config)

### Глобальные переключатели
| Настройка | По умолчанию | Описание |
|---|---|---|
| `enabled` | true | Включить/выключить весь мод |
| `blurEnabled` | true | Размытие фона |
| `refractionEnabled` | true | Рефракция (преломление) |
| `fresnelEnabled` | true | Френель-свечение на краях |
| `chromaticEnabled` | false | Хроматическая аберрация (тяжёлая) |
| `luminanceDockEnabled` | true | Luminance Dock для HUD |

### По элементам
| Настройка | По умолчанию | Описание |
|---|---|---|
| `hotbarGlass` | true | Стекло на хотбаре |
| `inventorySlotsGlass` | true | Стекло на слотах инвентаря |
| `containerGlass` | true | Стекло на контейнерах |
| `buttonsGlass` | true | Стекло на кнопках (меню, настройки) |
| `healthGlass` | true | Luminance Dock на здоровье |
| `hungerGlass` | true | Luminance Dock на голод |
| `armorGlass` | true | Luminance Dock на броню |
| `xpBarGlass` | true | Luminance Dock на опыт |
| `crosshairGlow` | false | Лёгкое свечение крестика |

### Качество
| Настройка | По умолчанию | Варианты |
|---|---|---|
| `blurQuality` | medium | low(1) / medium(3) / high(5) |
| `downsampleScale` | 0.25 | 0.25 / 0.5 / 1.0 |
| `refractionStrength` | 0.03 | 0.01 - 0.1 |
| `fresnelIntensity` | 0.3 | 0.1 - 0.5 |

### Производительность
| Настройка | По умолчанию | Описание |
|---|---|---|
| `dynamicQuality` | true | Авто-снижение качества при просадках FPS |
| `cacheBackground` | true | Кешировать размытый фон (не пересчитывать каждый кадр) |
| `maxFpsTarget` | 60 | Целевой FPS для авто-качества |

### Ключевое правило
**Критические элементы (здоровье, голод, опыт) ВСЕГДА остаются читаемыми.**
Glass-эффект — декоративный, не функциональный.
Даже при max glass — иконки/текст поверх стекла чёткие.

---

## Дистрибуция
- GitHub Releases
- Modrinth
- CurseForge
- Maven (для API-библиотеки, LGPLv3)

## Лицензия
**LGPLv3** (GNU Lesser General Public License)
- Другие моды могут использовать как библиотеку без ограничений
- Изменения в самом API должны быть открыты
- Коммерческое использование разрешено как зависимость

---

# Техническое ТЗ — Анализ и План реализации

## A. Gap Analysis: что есть vs что нужно

### Готово (из текущего кода)
| Компонент | Файл | Статус |
|---|---|---|
| Entry points | `LiquidumMod`, `LiquidumClient` | ✅ |
| FBO capture + blur + fullscreen glass | `LiquidGlassRenderer` | ✅ (fullscreen only) |
| ScreenMixin (extractBackground) | `ScreenMixin` | ✅ (fullscreen only) |
| EasingUtil (13 ф-ций) | `EasingUtil` | ✅ но **не подключён** |
| SDFUtil (7 ф-ций) | `SDFUtil` | ✅ но **не подключён** |
| Шейдеры (liquid_glass, blur) | `*.fsh/*.vsh` | ✅ |
| LiquidumCore (init/сервисы) | `LiquidumCore` | ✅ |
| LiquidumConfig (данные + JSON) | `LiquidumConfig` | ✅ (YACL UI позже) |
| LiquidumMaterial + Registry + Presets | `material/*` | ✅ (18 параметров + 8 вариантов) |
| LiquidumShape + ShapeRenderer | `shape/*` | ✅ (SDF-модель + CPU-маска; GPU-маска позже) |

### Не хватает (критично)
| Модуль ТЗ | Что нужно | Приоритет |
|---|---|---|
| **Stencil masks** | per-widget glass вместо fullscreen | HIGH |
| **WidgetMixin** | glass на AbstractWidget | HIGH |
| **InventoryMixin** | glass на инвентаре/слотах | HIGH |
| **HotbarMixin** | glass на хотбаре | HIGH |
| LiquidumMaterial | 18 параметров + 8 вариантов | ✅ готово (фундамент) |
| LiquidumShape | shape renderer (SDF → геометрия) | ✅ готово (фундамент) |
| **LiquidumAnimation** | spring physics + interruptible | MEDIUM |
| LiquidumConfig | настройки (данные + JSON) | ✅ готово (фундамент) |
| LiquidumCore | жизненный цикл/сервисы | ✅ готово (фундамент) |
| **LiquidumCompat** | API для модов | MEDIUM |
| **LiquidumDebug** | Lab + Inspector + Debugger | LOW |
| **LiquidumBenchmark** | profiler | LOW |
| **Liquid Lens** | лупа-линза | MEDIUM |
| **Adaptive Quality** | LOW/MID/HIGH/ULTRA | MEDIUM |
| **Redraw Visualizer** | overdraw debug | LOW |
| **Micro-interactions** | hover/ripple/parallax | MEDIUM |
| **Reduced Motion** | accessibility | LOW |
| **Morphing** | shape transitions | LOW |
| **ContainerMixins** | сундуки/печи/верстак | MEDIUM |

---

## B. Архитектура (ТЗ §30)

```
com.liquidum.client
├── LiquidumCore           // жизненный цикл, сервисы, init
├── LiquidumClient         // ClientModInitializer (уже есть)
├── shader/
│   ├── LiquidGlassRenderer  // GPU rendering (уже есть, расширить)
│   ├── GlassShader          // компиляция/привязка шейдеров
│   └── BlurShader
├── material/
│   ├── LiquidumMaterial     // 18 параметров
│   ├── MaterialRegistry     // варианты (Regular/Clear/Frosted/...)
│   └── MaterialPresets      // Crystal/Soft/Frosted/...
├── shape/
│   ├── LiquidumShape        // SDF → геометрия
│   ├── ShapeRenderer        // отрисовка SDF масок
│   └── MorphController       // морфинг форм
├── animation/
│   ├── LiquidumAnimation    // spring/damping/velocity
│   ├── SpringPhysics        // физика пружины
│   ├── TransitionController // interruptible transitions
│   └── EasingUtil           // (уже есть)
├── interaction/
│   ├── LiquidumInteraction  // input-aware
│   ├── PointerTracker       // курсор + parallax
│   └── InputMode            // mouse/kbd/controller/touch
├── lens/
│   └── LiquidLens           // лупа-линза
├── compat/
│   ├── LiquidumAPI          // публичный API
│   ├── LiquidumCompat       // интеграция модов
│   └── WidgetAdapter        // adapter для нестандартных GUI
├── debug/
│   ├── LiquidumLab          // лаборатория
│   ├── MaterialEditor
│   ├── AnimationEditor
│   ├── UIInspector
│   └── RenderDebugger
├── benchmark/
│   └── LiquidumBenchmark    // profiler + benchmark
├── config/
│   └── LiquidumConfig       // настройки
└── mixin/
    ├── ScreenMixin          // (уже есть)
    ├── WidgetMixin           // glass на виджетах
    ├── InventoryMixin        // glass на инвентаре
    ├── HotbarMixin           // glass на хотбаре
    ├── ContainerMixin        // сундуки/печи
    └── AbstractWidgetAccessor
```

**Правила:**
- Renderer не зависит от конкретного Screen
- Material не знает о gameplay logic
- Animation независима от конкретного виджета
- Compat не меняет игровую логику

---

## C. Материальная модель (ТЗ §2-4)

### 18 параметров материала
```java
public class LiquidumMaterial {
    float opacity;
    float blurRadius;
    float blurResolution;   // downsample scale
    int   blurSamples;
    float luminosityAdjustment;
    float saturation;
    vec3  backgroundTint;
    float edgeHighlight;
    float highlightWidth;
    float specularStrength;
    float refractionStrength;
    float distortionStrength;
    float noiseAmount;
    float shadowStrength;
    float innerReflection;
    float parallaxStrength;
    float cornerRadius;
    float borderThickness;
}
```

### 8 вариантов материала
| Вариант | Назначение |
|---|---|
| Regular Glass | универсальный, больше blur |
| Clear Glass | высокая прозрачность + dimming layer |
| Frosted Glass | плотный, для диалогов/мелкого текста |
| Dark Glass | адаптивный тёмный |
| Light Glass | светлый |
| Performance Glass | упрощённый для слабых ПК |
| Strong/Weak Glass | крайние пресеты |
| Custom Material | полностью настраиваемый |

### Стадии композиции материала (ТЗ §2)
```
1. Background sampling   → участок контента под стеклом
2. Blur                 → адаптивное разрешение + samples
3. Luminance adaptation → коррекция яркости для читаемости
4. Color transmission   → слабое прохождение цвета фона
5. Edge response        → тонкая световая кромка
6. Internal reflection  → очень слабое отражение
7. Refraction           → минимальное искажение фона (НЕ текста)
8. Depth separation     → тонкая тень/контраст
9. Optional noise       → только на HIGH/ULTRA
10. Composite           → объединение + foreground content
```

---

## D. SDF и геометрия (ТЗ §5-6)

### Формы (ShapeRenderer)
- Circle
- Rectangle
- Rounded Rectangle
- Capsule
- Pill
- Custom SDF shape
- Union / Intersection / Subtraction (экспериментальные)

Единый интерфейс: `bounds, radius, border, material, clipping, animationState`.

### Морфинг (MorphController)
Форма переходит между состояниями непрерывно:
- Button → Popover
- Button → Dialog
- Menu → Close
- Plus → Check
- Play → Pause
- Tab indicator между вкладками
- Dropdown из кнопки
- Settings button → Settings panel

Меняет геометрию, размер, radius, material, clipping одновременно.

---

## E. Система анимаций (ТЗ §7)

### Spring Physics
```java
public class SpringPhysics {
    float damping;
    float mass;
    float velocity;
    float position;
    float target;
    // обновление с учётом velocity (не просто интерполяция)
}
```

### Параметры
- Spring / Damping / Mass / Velocity
- Ease-out / ease-in-out
- Overshoot
- Interpolation
- Duration / Delay
- **Interruptible transitions** (хранит state + velocity, не start→end)

### Длительности (ТЗ §7)
- Открытие: 200-300ms
- Закрытие: 120-200ms
- Зависит от расстояния, velocity, типа действия

---

## F. Взаимодействие и Input-aware (ТЗ §12, 28)

### Pointer Response
Материал реагирует на курсор: highlight, reflection, refraction, parallax.
Небольшое запаздывание и затухание (ощущается как материал, не след).

### Input Mode
| Режим | Поведение |
|---|---|
| Mouse | тонкий pointer response |
| Keyboard | минимальный motion |
| Controller | focus-based transitions |
| Touchscreen | выраженная tactile response |

---

## G. Liquid Lens (ТЗ §13)

Отдельная система увеличения. Клавиша → стеклянная линза формируется из точки курсора.

```
Курсор → формирование линзы
Линза увеличивается + overshoot
Линза перемещается вверх/в сторону
Показывает реальный render target (не перерисовка)
Следует за курсором с задержкой
Авто-выбор стороны (не закрывать объект)
При отпускании схлопывается в курсор
Режимы: 2× / 4× / 8× / 16×
Debug: pixel grid, SDF, bounds, AA
```

---

## H. Liquidum Lab (ТЗ §14-18)

### Material Editor (ТЗ §15)
Blur, Opacity, Refraction, Distortion, Highlight, Noise, Tint, Shadow, Radius, Border, Luminosity, Saturation.
Пресеты: Crystal, Soft Glass, Frosted, Clear, Dark Glass, Ice, Liquid, Deep Glass.
Randomize → только визуально совместимые комбинации.

### Animation Editor (ТЗ §16)
duration, delay, easing, spring, damping, mass, velocity, scale, position, opacity, blur, morph.
Slow motion: 1×/0.5×/0.25×/0.1×, freeze frame.

### UI Inspector (ТЗ §17)
Данные: element ID, type, position, size, material, blur, opacity, animation, render cost, clipping, parent, children. Подсветка bounds и clipping region.

### Render Debugger (ТЗ §18)
Show bounds / clipping / SDF / blur areas / framebuffer / redraw regions / render passes / material layers.
Pipeline: BACKGROUND → BLUR → SDF → REFRACTION → HIGHLIGHT → COMPOSITE.

---

## I. Производительность (ТЗ §19-22)

### Правило: не рендерить то, что не видно
- Scissor / clipping
- Dirty regions
- Cached blur (не пересчитывать каждый кадр)
- Render target reuse
- Downsampled blur (0.25x)
- Batching
- Texture atlas
- SDF вместо множества текстур
- Кэш shader programs
- Кэш materials и geometry
- Нет постоянных framebuffer allocations
- Минимум временных объектов на кадр
- Объединение близких blur regions
- Обновление только изменившихся материалов

### Adaptive Quality (ТЗ §20)
| Пресет | Поведение |
|---|---|
| LOW | минимум blur, нет refraction/distortion, reduced samples |
| MID | нормальный blur, SDF, subtle refraction, стандартные анимации |
| HIGH | качественный blur, dynamic highlights, advanced morph |
| ULTRA | максимальное качество |
| CUSTOM | ручные настройки |

**Снижение при просадках:** сначала blur resolution → samples → refraction → distortion → noise → сложность анимаций. Не отключать всё сразу.

### Profiler & Benchmark (ТЗ §21)
GUI render time, Liquidum render time, blur cost, SDF cost, composite cost, animation cost, CPU overhead, VRAM, frame time.
Benchmark отдельно нагружает: blur, glass, refraction, SDF, morph, animations, множество panels, fullscreen.

### Redraw Visualizer (ТЗ §22)
Показывает области перерисовки. Поиск overdraw и лишних framebuffer passes.

---

## J. Совместимость с модами (ТЗ §25)

### LiquidumAPI
Регистрация: GUI type, element, button, panel, slider, toggle, tab, popup, custom shape.
Material / Shape / Animation / Interaction registration.
Priority, Clipping, Custom render adapter.

Стандартные Screen/Button/Widget → авто-стилизация.
Нестандартные GUI → adapter API.
Игровую логику не менять.

---

## K. Micro-interactions (ТЗ §26)
Delayed highlight, subtle cursor parallax, tiny shadow movement, material response, item hover, tiny ripple (только значимые), tab indicator morph, icon morph, slider snapping, tooltip continuity, error spring, success wave, progress highlight, tiny content parallax, glass edge response.
Правило: *felt, not noticed*.

---

## L. Reduced Motion & Accessibility (ТЗ §27)
Уменьшает spring, parallax, morph, animated blur, depth.
Иерархия и обратная связь сохраняются без анимации.

---

## M. Цвет (ТЗ §29)
Материал нейтральный. Цвет от контента под стеклом + семантические состояния (primary action, selected, status, important control). Не красить всё в фирменный цвет.

---

## N. Этапы реализации (ТЗ §33)

| Этап | Что делаем | Готовность после |
|---|---|---|
| **1** | Renderer + SDF + basic glass (stencil masks, WidgetMixin) | Базовый glass на виджетах |
| **2** | Blur + material system (LiquidumMaterial, 8 вариантов) | Material system |
| **3** | Animation system (SpringPhysics, interruptible) | Анимации открытия/закрытия |
| **4** | Vanilla GUI integration (Inventory/Hotbar/Container mixins) | Весь ванильный GUI |
| **5** | Liquid Lens | Лупа-линза |
| **6** | Liquidum Lab (Editor/Inspector/Debugger) | Debug tools |
| **7** | Performance profiler + redraw visualizer | Оптимизация |
| **8** | Third-party compatibility API | API для модов |
| **9** | Adaptive Quality | Auto quality |
| **10** | Advanced effects + experimental (morph, blobs, physics) | Полный мод |

**После каждого этапа проект запускаемый.**

---

## O. Критерии приёмки (ТЗ §34)
- GUI современно, но узнаваемо Minecraft
- Стекло = материал, не alpha+blur
- Текст читаем на разных фонах
- Анимации быстрые и прерываемые
- Morphing ≠ удаление+появление
- Liquid Lens показывает реальный render target
- LOW preset не уродливый
- Нет лишних fullscreen passes
- Profiler показывает реальные затраты
- Сторонний мод подключает Widget без переписывания логики
- Все эффекты отключаемы
- Reduced Motion сохраняет обратную связь

---

## P. Экспериментальные фичи (ТЗ §31)
Glass blobs (SDF/metaball), Physics Playground (gravity/mass/friction/damping/elasticity/viscosity), Liquid Lens debug, Pixel inspection, Render target inspection, Material randomizer, Animation recorder. Отделены от production path.

---

## Q. Что НЕЛЬЗЯ делать (ТЗ §32)
- Отдельный fullscreen blur для каждого элемента
- Постоянный animated noise
- Сильная refraction/glow
- Бесконечный parallax/bounce
- Одинаковая анимация для всего
- Слишком длинные transitions / огромный stagger
- Постоянные ripple / сильные gradients
- Огромные rounded rectangles
- Уничтожение Minecraft identity
- Ложные performance metrics
- Перерисовка всего GUI при изменении одного элемента

---

## R. План действий (что докачать)

### Ближайшие шаги (до запускаемого прототипа)
1. Исправить Loom build (заблокировано сейчас)
2. `LiquidumCore` — инициализация сервисов
3. `LiquidumConfig` — базовые настройки (enabled, blur, etc.)
4. `LiquidumMaterial` — 18 параметров + Regular/Clear/Frosted
5. `LiquidumShape` — ShapeRenderer + SDF masks
6. `WidgetMixin` — stencil glass на AbstractWidget
7. `InventoryMixin` + `HotbarMixin` — glass на слотах

### Среднесрочно
8. `LiquidumAnimation` — SpringPhysics + interruptible
9. `ContainerMixin` — сундуки/печи/верстак
10. `LiquidumCompat` — API для модов
11. `LiquidLens` — лупа-линза
12. Adaptive Quality

### Долгосрочно
13. Liquidum Lab (Editor/Inspector/Debugger)
14. Profiler + Redraw Visualizer
15. Morphing + Micro-interactions
16. Reduced Motion + Experimental

---

# S. Apple Liquid Glass → Liquidum: Архитектура и соответствие

## S.1 Mapping (SwiftUI → Liquidum)
| SwiftUI / Apple | Liquidum |
|---|---|
| SwiftUI Glass | `LiquidumMaterial` |
| SwiftUI Shape | `LiquidumShape` / SDF Shape |
| GlassEffectContainer | `LiquidumGlassContainer` |
| glassEffectID | `LiquidumGlassId` / Morph Group |
| GlassEffectTransition | `LiquidumGlassTransition` |
| SwiftUI Animation / Spring | `LiquidumAnimation` / Spring Physics |
| Glass rendering | Liquidum Render Pipeline |
| Background sampling | Minecraft Framebuffer Capture |
| Blur | отдельный Blur Pass |
| Refraction | отдельный Refraction Pass |
| Highlight / Edge lighting | Glass Lighting Pass |
| Final composition | Composite Pass |

## S.2 Материальная модель (19 параметров)
`LiquidumMaterial` НЕ содержит геометрию. Только параметры:
```
opacity, tint, blurRadius, blurQuality, refractionStrength,
refractionDispersion, chromaticAberration, specularIntensity,
edgeHighlight, innerHighlight, shadow, saturation, brightness,
contrast, noise, distortion, glassThickness, fresnelStrength
```
(примечание: отличается от ТЗ §4 — здесь 19 параметров, добавлены tint, brightness, contrast, glassThickness, refractionDispersion)

### Варианты (7)
`REGULAR, CLEAR, FROSTED, TINTED, DARK, LIGHT, CUSTOM`
Все параметры изменяемы через API.

## S.3 Система форм (независима от материала)
SDF вместо заранее подготовленных текстур.

### Формы
Rounded Rectangle, Circle, Ellipse, Capsule, **Rounded Rect с независимыми радиусами углов**, Ring, Line, Polygon, Triangle, Star, Custom SDF.

### Операции
fill, outline, inner border, outer border, smooth union, smooth subtraction, intersection, clipping.

## S.4 Glass Container (`LiquidumGlassContainer`)
Аналог GlassEffectContainer:
1. Собрать дочерние glass-элементы
2. Определить общий bounding region
3. Объединить пересекающиеся области
4. Один общий background capture (где возможно)
5. Общий blur buffer
6. Передавать дочерним общий sampling context
7. НЕ позволять каждому элементу независимо захватывать/размывать фон

Близко расположенные элементы → одна glass-региональная система.

## S.5 Background Capture (region-based)
НЕ fullscreen без необходимости:
- region-based capture
- объединение пересекающихся регионов
- padding для blur
- reuse захваченных областей
- downsampling
- кэширование
- **invalidation**: если фон региона не изменился → повторный capture не выполнять

## S.6 Render Pipeline (независимые стадии)
```
Minecraft UI
→ Background Capture
→ Downsample
→ Blur
→ SDF Mask
→ Refraction
→ Glass Material
→ Edge / Specular Highlights
→ Optional Noise / Distortion
→ Composite
→ Final UI
```
Не объединять всё в один огромный fragment shader. Каждый дорогой этап отключаемый / с пониженным разрешением.

## S.7 Shader Architecture
```
shaders/
├── core/
│   ├── glass.vsh / glass.fsh
│   ├── blur.fsh
│   ├── refraction.fsh
│   ├── composite.fsh
│   └── mask.fsh
└── include/
    ├── sdf.glsl
    ├── blur.glsl
    ├── glass.glsl
    ├── refraction.glsl
    ├── fresnel.glsl
    ├── noise.glsl
    └── color.glsl
```
- `.vsh` — vertex, UV, screen coords, position, attributes
- `.fsh` — pixel processing, SDF, blur, refraction, material, highlights, compositing
- `.glsl` — переиспользуемые функции (SDF primitives, blur, noise, Fresnel, refraction, color conversion, utility math)

## S.8 Оптимизация разрешения (per-pass)
Не одинаковое разрешение для всех проходов:
| Pass | Resolution |
|---|---|
| SDF / mask | full |
| Final composite | full |
| Background capture | 1/2 |
| Refraction | 1/2 |
| Blur | 1/4 или 1/2 |
| Дорогие эффекты | 1/4 или ниже |

Зависит от Quality Profile.

## S.9 Кэширование и Invalidation
Кэшировать: background capture, blur texture, SDF mask, static glass geometry, material parameters, объединённые glass regions.

**Static** (не перерисовывать при изменении одного элемента): форма панели, background blur, материал, SDF mask.

**Dynamic** (перерисовывать): hover, pressed, pointer interaction, spring, morph, refraction offset, animated highlight.

Ввести систему invalidation.

## S.10 Morphing (`LiquidumGlassId`)
Идентификатор связывает два glass-элемента. При переходе интерполировать:
position, size, corner radius, shape, opacity, tint, blur, refraction, highlight, material parameters.

Morph ≠ fade-out + fade-in. Spring-based + velocity-aware.

## S.11 Spring Animation
Параметры: stiffness, damping, mass, velocity, target, overshoot, settling, interruption.
Физически непрерывная при изменении цели во время движения. Не только линейный lerp.

## S.12 Interaction States
Состояния: `idle, hover, pressed, focused, disabled, selected`.
Меняют: opacity, tint, blur, scale, refraction, highlight, shadow, brightness.
Pointer interaction влияет только на необходимые регионы.

## S.13 Adaptive Quality (6 уровней)
`VERY_LOW, LOW, MEDIUM, HIGH, ULTRA, EXTREME`

Постепенное снижение:
1. resolution
2. blur samples
3. blur passes
4. refraction
5. chromatic aberration
6. distortion
7. noise
8. animated highlights
9. сложность morphing

Не отключать Liquidum целиком при первом снижении. Авто-выбор по GPU frame time.

## S.14 GPU Budget / Profiling
Профилировать: GPU time каждого pass, draw calls, framebuffer switches, texture samples, render target size, активные glass regions, активные glass elements.

Debug UI показывает: Pass | GPU Time | Resolution | Samples | Texture Size | Draw Calls.

## S.15 Glass Debug Lab
Отдельный debug screen для разработки материалов. Поддержать все формы + параметры. Toggle каждого render pass. Визуализация: SDF, normals, blur region, capture region, invalidated region, framebuffer, render pass, overdraw, final composite.

## S.16 Дизайн-принципы
Glass для: важных панелей, navigation, tabs, popovers, contextual controls, floating controls, важных интерактивных элементов.

НЕ для каждого мелкого элемента. Близкие элементы → `LiquidumGlassContainer`.
Главный контент остаётся главным. Без чрезмерного blur/прозрачности/хроматической аберрации/движения.

## S.17 API
High-level (без GLSL):
```java
Liquidum.glass()
    .material(Materials.REGULAR)
    .shape(Shapes.roundedRect(16))
    .interactive(true);

Liquidum.container()
    .add(element1).add(element2).add(element3);
```
Low-level (прямой контроль): render pass, shader, framebuffer, material, SDF, texture, animation, cache.

## S.18 Главный принцип
Liquidum — НЕ один shader effect. Полноценная система:
`Material + Shape + Container + Background Sampling + Blur + Refraction + SDF + Lighting + Animation + Morphing + Caching + Invalidation + Adaptive Quality + GPU Profiling + API`

Все подсистемы независимы и заменяемы. UI-компоненты НЕ связаны напрямую с конкретным shader implementation. Можно заменить blur/refraction/material без переписывания UI.
