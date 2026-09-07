---
paths:
  - "app/src/main/java/uz/mahalla/**/ui/**"
  - "app/src/main/java/uz/mahalla/navigation/**"
---

# Экраны и компоненты

## Сначала кит, потом свой компонент

Библиотека — `core/ui/components/`: `Buttons`, `TextFields`, `Chips`, `Toggles`,
`Cards`, `Bars`, `Sheets`, состояния экрана `state/ScreenState` + `ScreenStates`,
`Refresh`, `Snackbars`. Своя кнопка или поле ввода — только если в ките нет
подходящего; тогда добавляй в кит, а не в экран.

Состояния экрана — через `ScreenStateHost` (Loading / Empty / Error+retry /
Content), не самодельными `if`.

## Правила вёрстки

- **Цели нажатия ≥ 48dp** — из `MahallaComponentDefaults.touchTargets`.
  Визуальная высота из `Spacing` меньше, это разные величины.
- **Не навешивать `navigationBarsPadding()` поверх `innerPadding` Scaffold**
  или поверх `BottomSheetDefaults.windowInsets` — получается двойной отступ,
  кнопки уезжают на высоту навбара. Так было на всех шести экранах онбординга.
- **`accent` — цвет иконок и границ, не текста.** В светлой теме `accent` на
  `surface` даёт 4.17:1, текст акцентного бейджа берёт `onSecondaryContainer`.
- **Не писать `TextFieldValue` прямо в композиции** (backwards write) — сломается,
  как только источник станет асинхронным.
- Все строки — из ресурсов, сразу в `values/` (uz) и `values-ru/`.

## Превью

Каждый экран и компонент — под `@ThemeLanguagePreviews` (light/dark × uz/ru),
крупный текст — `@LargeFontPreviews` (fontScale 1.5). Скриншот-тестов в проекте
нет, превью — единственная проверка соответствия макету.
