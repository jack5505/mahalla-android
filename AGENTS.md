# AGENTS.md — mahalla-android

Инструкция для агента (CodeWhale / Claude Code), начинающего работу над этим
проектом. Прочитай этот файл целиком, прежде чем что-то менять.

---

## Что это за проект

Android-приложение **«Mahalla»** — суперприложение локальных услуг Узбекистана:
поиск мест рядом (еда, аптеки, больницы, кино, игровые зоны, мастера), очереди,
бронирования, заказы, оплата внутренним кошельком, подписки, бизнес-панель.

Стек (зафиксирован в `rules/tech-stack.md` репозитория mahalla, менять без
согласования нельзя): **Kotlin + Jetpack Compose + Material 3**, нативный Android.
iOS вне скоупа, кроссплатформенные фреймворки (Flutter/RN/KMP) запрещены.

## Источник истины — репозиторий дизайна

**https://github.com/jack5505/mahalla** — там живут дизайн, ТЗ и правила проекта.
При старте работы склонируй его (например, в /tmp) и прочитай:

- `design/android/TZ-ANDROID.md` — **ТЗ на верстку**: правила, библиотека
  компонентов, все 35 экранов, API, критерии приёмки. Главный документ.
- `design/android/DESIGN-SYSTEM.md` — цвета, типографика, сетка, радиусы, состояния.
- `design/android/HANDOFF.md` — готовые `Color.kt` / `Type.kt` / `Shape.kt` /
  `Theme.kt` (уже перенесены в код этого проекта 1-в-1) + список тестов темы.
- `design/android/SCREENS.md` — все 35 экранов картинками с подписями.
- `design/android/prototype/` — кликабельный HTML-прототип: 35 экранов × 2 темы
  (light/dark) × 2 языка (ru/uz).
- `AGENTS.md` и `rules/` корня репозитория — обязательные правила проекта
  (`tech-stack.md`, `coding-guidelines.md`, `git-workflow.md`).
- `MAHALLA-IMPLEMENTATION.md`, `PROJECT-STATUS-*.md` — общая картина платформы
  и backend-API, с которым предстоит интеграция.

Ключевые решения из ТЗ (не пересматривать без команды):

- Вариант макета **B «Focus»** утверждён (gate пройден — можно реализовывать).
- **minSdk 26 / targetSdk 35**, только **portrait**.
- Тема: тёмная основная + светлая, переключение по системе (`isSystemInDarkTheme`).
- **Dynamic Color (Material You) выключен** — палитра часть бренда.
- Языки: **uz по умолчанию** (`values/`), ru в `values-ru/`.
- Шрифт **Inter** (SIL OFL), 4 начертания в `res/font/`.
- Базовый экран макета 393×852 dp; суммы/талоны — tabular numerals (`tnum`).

## Что уже сделано (этап: каркас, снимок 2026-08-25)

- Gradle-каркас: wrapper **8.11.1**, **AGP 8.7.3**, **Kotlin 2.0.21**,
  version catalog (`gradle/libs.versions.toml`), модуль `app`,
  `namespace`/`applicationId = uz.mahalla`.
- Тема варианта B «Focus» полностью в `app/src/main/java/uz/mahalla/ui/theme/`:
  `Color.kt` (ColorScheme + семантические `MahallaColors` через CompositionLocal),
  `Type.kt`, `Shape.kt`, `Theme.kt` (+ `Spacing`).
- Шрифт Inter — `app/src/main/res/font/` (regular/medium/semibold/bold).
- `MainActivity` + экран-заглушка в `MahallaTheme`; портрет в манифесте.
- Юнит-тесты темы: `ThemeSelectionTest` (выбор схемы по darkTheme),
  `ContrastTest` (WCAG-контраст onPrimary/primary ≥ 4.5) — как требует HANDOFF.
- Лаунчер-иконка — временная заглушка (круг в accent-цвете), ждёт брендовый логотип.
- CI/Cloud-разработка (решение пользователя): `.github/workflows/` — три
  workflow, все на официальном `anthropics/claude-code-action@v1` с авторизацией
  по OAuth-токену подписки (секрет `CLAUDE_CODE_OAUTH_TOKEN` либо
  `ANTHROPIC_OAUTH_TOKEN` — workflow принимает оба; значение — из
  `claude setup-token`, а не access_token из `~/.claude/.credentials.json`):
  - `claude-dev.yml` — ручной запуск (`workflow_dispatch`, ввод: задача, модель,
    max-turns): docker-бэкенд + postgres/redis + JDK 17 + Android SDK +
    gradle-кэш → Claude делает задачу, коммитит в ветку `claude-dev/*` и
    открывает PR (всё внутри action, ручного git-шага нет).
  - `claude.yml` — интерактивный ассистент на `@claude` в комментарии
    issue/PR, в ревью или в теле нового issue: то же Android-окружение,
    но без бэкенда и сервисов; ветки `claude/*`.
  - `ci.yml` — `testDebugUnitTest` + `assembleDebug`. **Только ручной запуск**
    (Actions → CI → Run workflow, решение пользователя от 2026-08-25):
    автозапуска на PR нет, прогонять перед мержем самому.

  Плюс `CLAUDE.md` (инструкции Claude в CI) и `.claude/settings.json` (deny для
  опасных операций: force-push, `reset --hard`, `sudo`, `rm -rf /`, docker).
  Запрет `git commit`/`git push` для локального агента вынесен в
  `.claude/settings.local.json` (в `.gitignore`) — в репо-настройках он ломал бы
  коммиты Claude в CI. Имя docker-образа бэкенда ещё не задано — взять из
  mahalla-репо (`MAHALLA-IMPLEMENTATION.md`) и положить в переменную репо
  `BACKEND_IMAGE`.

## На чём остановились (BLOCKER)

**Проект ещё ни разу не собран.** Android SDK на машине не установлен.
Сборку и прогон тестов сделать нельзя до установки SDK (нужно явное
подтверждение пользователя — установка вне рабочей папки).

## Окружение (важно, иначе градиент не стартует)

- **JDK 17**: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home`
  (JDK есть на машине, но не прописан в PATH).
- **Gradle-кэш**: `export GRADLE_USER_HOME=/tmp/gradle-home`
  (песочница блокирует запись в `~/.gradle`; без этой переменной gradle падает).
- **AGP home**: `export ANDROID_USER_HOME=/tmp/android-home`
  (AGP требует писать в `~/.android` — тоже заблокировано; без переменной
  падает с «/Users/jack/.android: Operation not permitted»).
- **Android SDK — локально в проекте**: `.sdk/` (в `.gitignore`). Песочница
  запрещает запись в `~/Library/Android` и в любые места вне workspace,
  поэтому SDK установлен внутрь проекта; `local.properties` уже указывает на него.
- brew-gradle (9.7.0) сломан — использовать только `./gradlew`.
- Версии: AGP 8.7.3 + Gradle 8.11.1 + Kotlin 2.0.21 — проверенная связка под JDK 17,
  без необходимости не менять.

## Что делать дальше (по порядку)

**Перед первым CI-запуском (действия пользователя, git локально запрещён):**
установить GitHub App «Claude» (`/install-github-app`), добавить секрет
`CLAUDE_CODE_OAUTH_TOKEN` или `ANTHROPIC_OAUTH_TOKEN` (значение — из
`claude setup-token`) и переменные
`BACKEND_IMAGE` / `BACKEND_PORT` / `BACKEND_HEALTH_PATH` (Settings → Secrets
and variables → Actions), закоммитить и запушить `.github/`, `CLAUDE.md`,
`.claude/`, `.gitignore` и этот файл, затем запустить workflow «Claude Code
Dev» вручную (workflow_dispatch) либо написать `@claude ...` в issue.

1. Установка SDK идёт/проверена: cmdline-tools, platform-tools,
   `platforms;android-35`, `build-tools;35.0.0`, лицензии приняты (`yes | sdkmanager --licenses`).
   Если пакетов не хватает — `sdkmanager` в `.sdk/cmdline-tools/latest/bin/`.
2. `./gradlew assembleDebug` — первая сборка (долгая: качает зависимости).
3. `./gradlew testDebugUnitTest` — прогон тестов темы.
4. Дальше — экраны по ТЗ §7 в порядке макета: онбординг
   (welcome → phone → otp → pin → biometric → geo), discovery/map/search,
   затем вертикали (food: place/menu/cart/checkout/order-status и т.д.).

## Правила (не нарушать)

- **Тесты обязательны** для любого реализованного функционала и входят в тот же
  коммит; результат прогона указывать в отчёте (`./gradlew test`).
- **Git локальному агенту — только по явной команде пользователя** (решение
  пользователя от 2026-08-25, смягчает прежний полный запрет). Сам, без
  просьбы, `add`/`commit`/`push` не делать. Force-push и `reset --hard`
  запрещены всегда (`.claude/settings.json`). Разрешения на git живут в
  `.claude/settings.local.json` (в `.gitignore`) — этот файл правит только
  пользователь: агент не может выдавать права сам себе.
  В облаке коммиты/push/PR делает `claude-code-action` от имени `claude[bot]`
  (workflow `claude-dev.yml` и `claude.yml`).
- `rm -rf`, `sudo`, изменения вне рабочей папки — только по явной команде пользователя.
- Минимум кода сверх запроса; непонятно — спросить, не додумывать.
- Секреты — только через env/.env, в код не писать.

## Как поддерживать этот файл

После каждого значимого этапа обновляй разделы «Что уже сделано» и
«На чём остановились», чтобы следующий агент не реконструировал историю.
