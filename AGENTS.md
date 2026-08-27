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

## Этап: архитектурный фундамент (эпик 1, issue #5, ветка claude/issue-5-*)

Сделано (реализация, снимок 2026-08-25):

- **DI (Hilt)**: `MahallaApplication` (`@HiltAndroidApp`), `MainActivity`
  (`@AndroidEntryPoint`), модули `core/di/AppModule` (Clock),
  `data/network/di/NetworkModule`, `data/prefs/di/DataStoreModule`
  (+ `StorageBindingsModule`), `data/db/di/DatabaseModule`,
  `data/security/di/SecurityModule`, `feature/discovery/data/di/…`.
  База MVI — `core/ui/Mvi.kt` (`UiState`/`UiEvent`/`UiEffect`/`MviViewModel`).
- **Структура**: `core/{ui,result,format,locale,di}`,
  `data/{network,prefs,db,security}`,
  `feature/<name>/{ui,domain,data}` (onboarding — полный срез, остальные фичи
  пока только `ui`).
- **Навигация (Navigation Compose 2.8, typed routes)**: `navigation/Routes.kt`
  (`@Serializable`-маршруты), `MahallaNavHost` (onboarding → main → детали),
  `MahallaApp` (bottom nav), deep link `mahalla://place/{placeId}`
  (`navigation/DeepLinks.kt` + intent-filter в манифесте).
- **Сеть**: `NetworkFactory` (сборка стека без Hilt — её же используют тесты),
  `AuthInterceptor` (Bearer), `TokenAuthenticator` (refresh по 401, один
  повтор, отдельный `@RefreshClient` без authenticator'а),
  `core/result/{ApiResult,ApiError}` + `apiCall {}`, baseUrl из
  `BuildConfig.API_BASE_URL` (debug → `10.0.2.2:8080`, release → прод).
- **Хранилище**: DataStore Preferences (`SettingsDataStore` — язык/тема/флаг
  онбординга, `DataStoreSessionStore` — токены), Room `MahallaDatabase`
  (places / orders / cart_draft_items, `version = 1`, `exportSchema = false`),
  PIN — PBKDF2 (`PinHasher`) + шифрование хэша ключом из AndroidKeyStore
  (`AndroidKeystorePinCipher`, за интерфейсом `PinCipher` ради тестов).
- **Локализация**: все строки в `values/` (uz) и `values-ru/`,
  `res/xml/locales_config.xml`, per-app languages через `LocaleManager`
  (API 33+) с фолбэком `LocaleContextWrapper` в `attachBaseContext` для 26–32;
  форматтеры `MoneyFormatter` (NBSP-разряды), `DateTimeFormatters`
  (Asia/Tashkent), `TicketFormatter` (`A-042`).
- **Splash**: `Theme.Mahalla.Splash` (Core SplashScreen API), splash держится
  до чтения настроек из DataStore (`RootViewModel`).
- **Тесты (14 файлов)**: сборка графа (`di/GraphAssemblyTest`, включая проверку
  кодогенерации Hilt), `PhoneInputViewModelTest`, `RoutesSerializationTest`
  (сериализация маршрутов + соответствие deep-link placeholder'ов),
  `NetworkStackTest` на MockWebServer (200 / unknown fields / Bearer /
  401 + refresh / провал refresh / таймаут / битый JSON), `MahallaDatabaseTest`
  (Robolectric, DAO), `SettingsDataStoreTest`, `KeystorePinStorageTest`,
  `PinHasherTest`, `StringResourceParityTest` (ключи и placeholder'ы
  `values/` vs `values-ru/`), форматтеры, `AppLanguageTest`, `ApiResultTest`,
  `RootViewModelTest` (фиксация стартового пункта графа).

### Правки после код-ревью PR #19

- **`ui/theme/Color.kt` не компилировался** (пришло ещё с initial commit, то
  есть `main` тоже был красный): в KDoc стояла последовательность `/*`
  (`success/warning/*Soft`), а блочные комментарии в Kotlin **вложенные** — файл
  оставался незакрытым и `:app:kspDebugKotlin` падал с `Unclosed comment`.
  Текст переписан без `/*`.
- **PIN не блокирует Main**: `KeystorePinStorage.save/verify` уводят PBKDF2
  (120 000 итераций) и Keystore на `Dispatchers.Default`.
- **DataStore больше не роняет старт**: `ReplaceFileCorruptionHandler` в
  `DataStoreModule` + `.catch {}` в `SettingsDataStore.settings` (дефолты) и
  `DataStoreSessionStore.session` (нет сессии). Раньше исключение улетало в
  `stateIn` внутри `viewModelScope` под держащимся splash'ем.
- **`startDestination` фиксируется один раз** (`RootUiState.Ready.startWithOnboarding`):
  пересчёт на каждой эмиссии настроек пересобирал граф и сбрасывал back stack.
  Выход из онбординга остался только на `navigate(MainGraph) { popUpTo(...) }`.
- **Токены не текут в logcat**: `redactHeader("Authorization")` у
  `HttpLoggingInterceptor`.
- **Файл prefs исключён из бэкапа** (`backup_rules.xml`,
  `data_extraction_rules.xml`, `<device-transfer>` тоже): там токены сессии, а
  ключ Keystore на новое устройство не переносится.
- **`expiresIn` теперь nullable** (`Session.UNKNOWN_EXPIRY`): дефолт `0L`
  означал «токен истёк в 1970».
- **`attemptCount` в `TokenAuthenticator`** считает только 401-звенья цепочки
  `priorResponse`; раньше один редирект исчерпывал лимит и refresh не случался.
- **Один критерий «PIN настроен»**: `SettingsDataStore.pinConfigured` смотрит
  `PinHash` **и** `PinSalt`, как `PinStorage.isConfigured()`.

**Не сделано / риски:**

- **Иконка (1.6)** — брендового ассета нет, лаунчер и splash используют
  прежнюю заглушку. Ждём логотип.
- **Сборка и тесты агентом тогда не запускались** (`./gradlew` не был в
  allow-list `claude.yml`) — из-за этого некомпилируемые `Type.kt` и
  `MahallaApp.kt` доехали до `main`. Разрешение выдано в ходе ревью PR #21,
  теперь агент собирает сам.
- **Открытые замечания ревью** (оформить задачами, не блокеры мержа):
  `synchronized` + четыре `runBlocking` в `TokenAuthenticator` (лучше `Mutex` +
  кэш результата refresh); два независимых `OkHttpClient` с отдельными пулами;
  `PhoneInputScreen` держит форматированную строку в `OutlinedTextField`
  (каретка прыгает — нужен `TextFieldValue`/`VisualTransformation`);
  блокирующее чтение DataStore в `attachBaseContext` на API 26–32;
  `StringResourceParityTest` не ловит непозиционные `%s`/`%d`;
  `BottomNavItem.route: Any`; `TicketFormatter.parse("A--42")`;
  `GraphAssemblyTest` не закрывает созданные клиенты и DataStore;
  `exportSchema = false` включить обратно до первого релиза.
- Версии новых библиотек подобраны под связку AGP 8.7.3 / Kotlin 2.0.21 /
  KSP 2.0.21-1.0.25: Hilt 2.52, Navigation 2.8.5, Room 2.6.1, Retrofit 2.11.0,
  OkHttp 4.12.0, DataStore 1.1.1, Robolectric 4.14.1.

## Этап: UI-кит (эпик 2, issue #6, ветка claude/issue-6-*)

Библиотека компонентов живёт в `core/ui/`:

- **Токены и доступность**: `components/ComponentDefaults.kt` — все цели
  нажатия ≥ 48dp отдельными значениями (визуальная высота из `Spacing`
  меньше, и это разные величины); `components/MahallaTone.kt` — смысловые
  тоны (neutral/accent/success/warning/info/error) с парами цветов.
- **2.1 базовые**: `Buttons.kt` (primary/secondary/ghost/destructive +
  `ButtonState` enabled/loading, `MahallaIconButton`), `TextFields.kt`
  (текст, телефон `+998` с маской и удержанием каретки, OTP-ячейки поверх
  одного скрытого поля, поиск), `Chips.kt` (фильтры + бейджи),
  `Toggles.kt` (строка-переключатель, сегментированный контрол).
- **2.2 контейнеры**: `Cards.kt` (`PlaceCard`, `OrderCard`, `TicketCard`,
  `BookingCard`, `MahallaListItem`, `SectionHeader`), `Bars.kt`
  (`MahallaTopBar`, `MahallaBottomNav` — им пользуется `MahallaApp`),
  `Sheets.kt` (bottom sheet + диалог).
- **2.3 состояния**: `state/ScreenState.kt` (Loading/Empty/Error/Content +
  `toScreenState`), `ScreenStates.kt` (скелетоны, empty, error c retry,
  `ScreenStateHost`), `Refresh.kt` (pull-to-refresh), `snackbar/` +
  `Snackbars.kt` (единый снекбар через канал).
- **Превью**: `preview/Previews.kt` — multipreview `@ThemeLanguagePreviews`
  (light/dark × uz/ru) и `@LargeFontPreviews` (fontScale 1.5).
- **Тесты**: `ContrastTest` расширен на все семантические пары (текст 4.5:1,
  нетекстовые элементы 3.0:1), `PhoneFieldFormatterTest`, `OtpFieldStateTest`,
  `ScreenStateTest`, `ComponentStateTest`, `TouchTargetTest`,
  `SnackbarControllerTest`.

Найдено тестом контраста (в код не правил — палитра приходит из
design-репозитория): в светлой теме `onSecondary` на `secondary` = 4.48:1,
`accent` на `surface` = 4.17:1, `accent` на `accentSoft` = 3.65:1. Поэтому
accent в ките — цвет иконок/границ, а текст акцентного бейджа берёт
`onSecondaryContainer` (11.36:1). Нужно решение дизайна, править ли
`secondary`. Остальные пары с большим запасом: минимум по тексту — 4.88:1
(`warning/warningSoft` в светлой), в тёмной теме худшая пара 5.78:1.

### Правки после код-ревью PR #21

Ревью нашло, что три файла не компилировались и что тест контраста
измерял пустоту. Всё перечисленное исправлено, сборка и тесты прогнаны.

- **`ui/theme/Type.kt` не компилировался** (пришло с initial commit, `main`
  тоже был красный): `TextStyle(Inter, FontWeight.Bold, 24.sp, …)` — аргументы
  позиционные, а первый параметр `TextStyle` это `color`, `fontFamily` шестой.
  Все семь стилей переписаны именованными аргументами.
- **`navigation/MahallaApp.kt` не компилировался** (тоже с `main`):
  `hasRoute(route::class)` не резолвился без импорта
  `NavDestination.Companion.hasRoute`. Заодно матч идёт по `hierarchy` — на
  вложенном экране таб остаётся подсвеченным, — а `BottomNavItem.valueOf(id)`
  заменён на поиск по `name` (неизвестный id больше не кидает исключение).
- **`ScreenStateTest` не компилировался**: `ApiResult.Failure` это
  `ApiResult<Nothing>`, `T` не выводился, и из-за одной строки падал весь
  `compileDebugUnitTestKotlin` — то есть тесты эпика 2 ни разу не выполнялись.
  Тип задан явно.
- **`ContrastTest.luminance()` читал нули**: каналы брались как
  `(color.value.toLong() shr 16) and 0xFF`, но Compose кладёт sRGB-ARGB в
  **старшие** 32 бита `value` (в младших — id цветового пространства). Любая
  пара давала ровно `1.00`, все 7 тестов падали. Теперь каналы берутся из
  `color.red/green/blue`. Цифры выше (4.48 / 4.17 / 3.65) реальному замеру
  соответствуют — с исправленной формулой весь `ContrastTest` зелёный.
- **Вставка номера с кодом страны**: `PhoneFieldFormatter.digitsOf()` брал
  первые 9 цифр как есть, и `+998901234567` из SMS превращался в
  `+998 99 890 12 34`. Теперь ведущие `998` и `0` снимаются, но **только**
  когда цифр больше девяти: набранные вручную `998 12 34 56` — валидный номер
  оператора 99, а не код страны. Каретка при снятии префикса не уезжает
  (`dropped` учитывается в `apply`). Тест, закреплявший баг, переписан.

**Проверено** (впервые агентом, `Bash(./gradlew*)` появился в allow-list):
`./gradlew testDebugUnitTest` — **151 тест, 0 падений**;
`./gradlew assembleDebug` — BUILD SUCCESSFUL.

**Не сделано / риски эпика 2:** скриншот-тестов нет, соответствие макету
проверяется глазами по превью; экраны, кроме `PhoneInputScreen` и нижней
навигации, на кит ещё не переведены (это эпики 3+). Открытые замечания ревью
PR #21, оформить задачами (не блокеры): `MahallaPhoneField` пишет
`TextFieldValue` прямо в композиции (backwards write — сломается, как только
источник станет асинхронным); `state.canSubmit` вычисляется, но кнопка всегда
`enabled`; `ButtonDefaults.buttonColors` без `disabledContainerColor` (у Ghost
и Secondary выключенное состояние серое); двойной нижний отступ в
`Sheets.kt` (`navigationBarsPadding()` поверх `BottomSheetDefaults.windowInsets`);
`rememberInfiniteTransition` в каждом `SkeletonBox` — мерцание не в фазе;
KDoc `ButtonCaption` обещает склейку семантики, которой нет;
`otp_input_description` нужен `<plurals>`; `TouchTargetTest` и
`ComponentStateTest` наполовину сверяют константы сами с собой — реальные
цели нажатия закрыл бы Compose-тест под Robolectric (`ui-test-junit4`).

## Этап: онбординг и авторизация (эпик 3, issue #7, ветка claude/issue-7-*)

Первый продуктовый флоу целиком: welcome → phone → otp → pin → biometric → geo.

- **Сквозное**: `feature/auth/data/AuthRepository` (интерфейс + `DefaultAuthRepository`)
  — запрос кода, верификация (сохраняет сессию), явный refresh, logout (чистит
  сессию **и** PIN даже если запрос не ушёл). Эндпоинты добавлены в
  `data/network/auth/AuthApi`: `auth/otp/request`, `auth/otp/verify`,
  `auth/refresh`, `auth/logout` — все на «голом» `@RefreshClient`, чтобы 401 на
  них не звал `TokenAuthenticator`. Домен: `OtpChallenge` (клиентские дефолты
  6 цифр / 60 сек / 180 сек + отбрасывание мусора от сервера), `LoginResult`,
  `OtpFailure` (`asOtpFailure()` раскладывает HTTP-коды: 401/400/422 →
  InvalidCode, 410 → Expired, 429/423 → TooManyAttempts, остальное → Network).
- **3.1 Welcome**: выбор языка (сегментированный контрол, пишется в DataStore,
  на API < 33 — recreate Activity) + одна кнопка «вход или регистрация».
- **3.2 Телефон**: маска `+998`, валидация, **чекбокс согласия с офертой**
  (проверяется до сети — SMS платное), ссылка на оферту открывается по
  `onboarding_offer_url` (`translatable="false"`, пока `https://mahalla.uz/offer`
  — уточнить у продукта), состояния loading/error.
- **3.3 OTP**: ячейки поверх скрытого поля, автофокус (`focusRequester` добавлен
  в `MahallaOtpField`), автоотправка по последней цифре, таймер повтора
  (корутина в `viewModelScope`, параметры приходят маршрутом `OtpRoute(phone,
  resendAfterSeconds, codeLength)` из ответа сервера), разные тексты ошибок;
  сетевая ошибка не стирает введённые цифры, «попытки исчерпаны» блокирует ввод
  до нового кода.
- **3.4 PIN**: этап выбирается по `PinStorage.isConfigured()` — Create → Confirm
  либо Unlock. 4 цифры, ячейки маскированы (`masked = true`), проверка по
  последней цифре. Лимит 5 попыток → PIN и сессия стираются, экран уходит на
  повторный вход; отдельная кнопка «Забыли PIN?» делает то же осознанно.
  Первый введённый код живёт в поле ViewModel, не в `UiState`.
- **3.5 Биометрия**: `BiometricAvailability` (интерфейс, `BiometricStatus`
  Available/NotEnrolled/NoHardware/Unavailable) — ViewModel тестируется на JVM;
  сам `BiometricPrompt` показывает экран. Флаг `biometricEnabled` пишется
  **только** после успешного промпта, «Позже» пишет `false`. Из-за требования
  BiometricPrompt `MainActivity` теперь наследуется от `FragmentActivity`
  (androidx.biometric 1.1.0, fragment приходит транзитивно).
- **3.6 Геолокация**: экран-объяснение → `RequestMultiplePermissions`
  (coarse+fine, достаточно любого) → отказ переключает на выбор города
  (`City` — 8 городов, id пишется в DataStore ключом `settings_city_id`);
  «Выбрать город вручную» доступно и без запроса разрешения.
- **Кит пополнен**: `MahallaCheckboxRow` (роль Checkbox, цель нажатия 48dp,
  ссылка отдельной кнопкой), `MahallaOtpField(masked, focusRequester)`,
  `OnboardingStep`/`OnboardingError` (общий каркас шагов с `imePadding`).
- **DataStore**: `AppSettings` + `biometricEnabled` и `cityId`;
  `OnboardingRepository` стал интерфейсом (`DataStoreOnboardingRepository`) —
  от него зависят четыре ViewModel онбординга и стартовый пункт графа.
- **Тесты (218 всего, 0 падений)**: `AuthRepositoryTest` на MockWebServer (15
  тестов: тела запросов, дефолты и клампинг challenge, абсолютный срок жизни
  токена, 401 не сохраняет сессию, refresh без сессии не ходит в сеть, 5xx не
  разлогинивает, logout чистит локально и без сети), `OtpFailureTest`,
  `PhoneInputViewModelTest`, `OtpViewModelTest` (таймер по виртуальному
  времени), `PinViewModelTest`, `BiometricViewModelTest`, `GeoViewModelTest`,
  `WelcomeViewModelTest`; фейки в `testutil/` (`FakeAuthRepository`,
  `FakePinStorage`, `FakeOnboardingRepository`, `MainDispatcherRule`).

### Правки после код-ревью PR #22

- **Прерванный онбординг больше не стоит второго платного SMS**: `RootViewModel`
  один раз за процесс решает не только «онбординг или main», но и где онбординг
  продолжается — если сессия уже в `SessionStore` (`AuthRepository.isAuthorized`,
  до этого объявлен и не использован), граф стартует с `PinRoute`, а не с
  welcome → телефон → новый код. Стартовый пункт онбординга протянут параметром
  `onboardingStartDestination` (MainActivity → `MahallaApp` → `MahallaNavHost`).
  Заодно `onAuthRestartRequired` чистит стек целиком (`popUpTo(OnboardingGraph)
  { inclusive = true }`): «назад» на экран PIN, которого больше нет, вести
  некуда — тем более когда граф стартовал с него.
- **Биометрия перечитывает статус на `ON_RESUME`** (`BiometricEvent.ScreenResumed`
  + `LifecycleEventEffect` в `BiometricScreen`): раньше `status()` читался
  один раз в конструкторе, и пользователь, ушедший добавлять отпечаток в
  настройки устройства, возвращался к навсегда выключенной кнопке.
- **Убран двойной нижний инсет**: `OnboardingStep` больше не навешивает
  `navigationBarsPadding()` поверх `innerPadding` из `Scaffold` — кнопки на всех
  шести экранах отъезжали на высоту навбара, а с открытой клавиатурой футер
  висел над ней с тем же зазором.
- **Записи прикрыты от падения** (`core/result/runCatchingCancellable` —
  `runCatching`, который не глотает `CancellationException`): PIN
  (`save`/`verify`/`clear`/`isConfigured` — Keystore кидает `KeyStoreException`
  и `KeyPermanentlyInvalidatedException`) и записи в DataStore из
  Welcome/Geo/Biometric/`RootViewModel.onOnboardingFinished`. Поведение при
  отказе разное и осмысленное: PIN показывает ошибку
  (`PinError.STORAGE`, новая строка `onboarding_pin_error_storage`) и **не
  тратит попытку**; язык применяется, даже если не сохранился; город и флаг
  биометрии не запирают пользователя на шаге — онбординг заканчивается.
- Тесты: 230 в 32 классах, 0 падений (`assembleDebug` — BUILD SUCCESSFUL).
  Новые — перечит статуса биометрии, отказ Keystore на save/verify/clear,
  отказ DataStore в Welcome/Geo/Biometric, старт с PIN при живой сессии,
  `RunCatchingCancellableTest` (отмена не проглатывается). Фейки
  `FakePinStorage`/`FakeOnboardingRepository` умеют отказывать (`failure`,
  `writeFailure`).

**Не сделано / риски эпика 3:**

- **Design-репозиторий в этом прогоне был недоступен** (`git clone` и `gh api`
  не в allow-list workflow), поэтому вёрстка сделана по описанию issue,
  AGENTS.md и киту эпика 2 — **сверить с `TZ-ANDROID.md`, `SCREENS.md` и
  прототипом** (тексты, порядок блоков, иллюстрации).
- **Контракт бэкенда по OTP выдуман по здравому смыслу** (`auth/otp/request`,
  `auth/otp/verify`, поля `resendAfter`/`codeLength`/`isNewUser`, коды
  400/410/429). Сверить с `MAHALLA-IMPLEMENTATION.md` / реальным API и
  поправить `AuthApi` + `asOtpFailure()`.
- Ссылка на оферту — заглушка (`https://mahalla.uz/offer`).
- Биометрия не проверена на устройстве (эмулятора в CI нет): промпт,
  `FragmentActivity` и отказ в разрешении геолокации нужно прогнать руками.
- `isNewUser` доезжает до навигации, но никуда не ведёт — экран заполнения
  профиля появится в следующих эпиках.
- Разблокировки по биометрии/PIN на старте приложения ещё нет: флаги
  сохраняются, но app-lock — отдельная задача (эпик профиля/безопасности).
- Скриншот-тестов по-прежнему нет; соответствие макету проверяется глазами по
  `@ThemeLanguagePreviews`. Снятый `navigationBarsPadding()` и перечит статуса
  биометрии на `ON_RESUME` юнит-тестами не покрыть — проверить на устройстве.
- Открытые замечания ревью PR #22 (задачами, не блокеры): лимит попыток PIN
  живёт только в памяти (к app-lock переносить в DataStore); мигание заголовка
  на экране PIN (стадия читается асинхронно, стартовое значение `Create`);
  `PinRoute` не выталкивается при переходе на биометрию; `refresh()` при 401
  чистит сессию, но не PIN; голый `(context as? Activity)?.recreate()` в
  `WelcomeScreen` при аккуратной развёртке `ContextWrapper` в `BiometricScreen`;
  `City.Default`/`City.fromId` пока не используются; `pin_input_description` и
  `otp_input_description` просят `<plurals>`; `RoutesSerializationTest` шумит
  семью warning'ами `ExperimentalSerializationApi`.


## Этап: картографический SDK (эпик 4.2, issue #8, ветка claude/issue-8-20260826-1256)

**Блокер закрыт решением пользователя от 2026-08-26: карты — Yandex MapKit.**
Google Maps больше не рассматривается.

- Зависимость: `com.yandex.android:maps.mobile:4.42.0-lite` (Maven Central,
  каталог — `yandexMapkit`). Вариант **lite**: карта, маркеры, кластеризация,
  слой «моё местоположение». Search / Routing / Panorama живут в `-full` и
  приложению пока не нужны — это лишние мегабайты и лишняя лицензия.
- **Ключ в репозиторий не кладём.** `app/build.gradle.kts` читает его из
  переменной окружения `MAPKIT_API_KEY`, иначе из `local.properties`
  (`mapkit.apiKey=…`), и кладёт в `BuildConfig.MAPKIT_API_KEY`. Пустое
  значение сборку не ломает: `MapKitInitializer` вернёт `MissingApiKey`, и на
  месте карты будет объяснение. **Действие пользователя:** получить ключ в
  кабинете Yandex MapKit и добавить секрет `MAPKIT_API_KEY` в Settings →
  Secrets and variables → Actions, а в `ci.yml` / `claude*.yml` пробросить его
  в env шага сборки (workflow'ы правит пользователь — GitHub App агента не
  имеет прав на `.github/workflows`).
- Код (`feature/map/`):
  - `data/MapKitInitializer` — ленивая инициализация (не в `Application`:
    MapKit поднимает свои потоки, а карта — один экран из 35), порядок
    «ключ → локаль → initialize», идемпотентность, состояния
    `Ready / MissingApiKey / Failed`; провал не кэшируется, retry работает.
    Статика SDK спрятана за интерфейсом `MapKitSdk` ради JVM-тестов.
  - `data/UserLocationProvider` + `MapKitLocationProvider` — координаты через
    `LocationManager` MapKit (свой клиент GMS не тянем: устройства без сервисов
    Google в Узбекистане обычны), таймаут 5 сек, отсутствие координат — норма.
  - `canvas/` — SDK-зависимое полотно: `YandexMapCanvas` (MapView в
    `AndroidView`, жизненный цикл `onStart`/`onStop`, родная
    `ClusterizedPlacemarkCollection`, тап по маркеру и по кластеру, слой
    «моё местоположение»), `MapCanvas` (то же плюс экран-объяснение, когда
    движок не поднялся), `MapCameraFit` (центр и зум под набор точек — чистая
    математика, тестируется без Android), `MarkerDiff` (пересобирать полотно
    или нет), `MarkerIcons` (иконки рисуются кодом: цвет обязан следовать
    теме, два набора PNG разъезжались бы с палитрой).
- **Особенности MapKit 4.42, на которые уходит время**: слушатели принимаются
  только в `java.lang.ref.WeakReference` и SDK их не удерживает — сильную
  ссылку обязан держать вызывающий (иначе тап тихо перестаёт работать);
  `addPlacemark(Point)` и `addEmptyPlacemark(Point)` объявлены deprecated,
  живой вариант — `addPlacemark { placemark -> … }` c `PlacemarkCreatedCallback`;
  `UserLocationLayer.isHeadingEnabled` из API исчез.
- Тесты: `MapKitInitializerTest`, `MapKitLocationProviderTest`,
  `MapCameraFitTest`, `MarkerDiffTest`, `MarkerIconsTest` — 29 тестов.
  `./gradlew testDebugUnitTest` — **259 тестов, 0 падений**;
  `./gradlew assembleDebug` — BUILD SUCCESSFUL (в APK приезжает
  `libmaps-mobile.so`).

**Не сделано / риски:**

- **Ветка эпика 4 (`claude/issue-8-20260826-0311`) в `main` не влита**, а в
  allow-list `claude.yml` нет ни `git merge`/`checkout <ref>`/`cherry-pick`, ни
  `gh` — затянуть тот код в эту ветку агент не смог. Поэтому `MapScreen` из
  эпика 4 здесь **не подключён к полотну**: после слияния веток нужно заменить
  заглушку на `MapCanvas(initializer, markers, camera, …)` и отобразить домен в
  `MapMarkerUi`/`MapCameraPosition`. Это единственный оставшийся шаг 4.2.
- **На устройстве не проверено** (эмулятора в CI нет, ключа нет): тайлы,
  кластеры, слой местоположения и лицензионная плашка Yandex.
- **Сеточный `MarkerClusterer` эпика 4 при родной кластеризации MapKit
  становится лишним** — решить при слиянии: либо выкинуть, либо оставить для
  экранов без карты.
- Требования лицензии Yandex (плашка «Яндекс», условия бесплатного тарифа,
  лимиты) не проверялись — это вопрос к продукту до релиза.

### Правки после код-ревью PR #24

Ревью нашло, что ветка после слияния с `main` не собиралась, и что три
заявленных свойства полотна кодом не обеспечены.

- **`mergeDebugResources` падал на дублях строк**: `map_zoom_in`,
  `map_zoom_out`, `map_my_location` приехали в `main` вместе с экраном карты
  эпика 4, а эпик 4.2 объявил их второй раз. Удалены из нового блока.
  Ровно та грабля, о которой предупреждает этот файл: `assembleDebug` был
  зелёным **до** мерж-коммита, после слияния его не перепрогнали.
- **Два осиротевших `=======`** из ручных слияний (в этом файле, один пришёл
  ещё из `main`) убраны.
- **MapKit инициализируется на главном потоке**: `ensureInitialized()` уходил
  на `Dispatchers.IO`, хотя весь SDK требует UI-потока (в том же PR это было
  написано комментарием в `UserLocationProvider`). Теперь метод `suspend`,
  внутри `withContext(mainDispatcher)`, а `@Synchronized` заменён на `Mutex` —
  блокировать чужой поток на время загрузки нативной библиотеки нельзя.
  Диспетчер — параметр конструктора, тест закрепляет, что все три вызова SDK
  идут через него.
- **`Error` от MapKit больше не роняет приложение**: `runCatchingCancellable`
  ловит только `Exception`, а SDK сообщает о нарушении контракта
  `AssertionError` и об отсутствии `libmaps-mobile.so` — `UnsatisfiedLinkError`.
  Появился `runCatchingMapKit` (те же два семейства плюс `Exception`;
  `OutOfMemoryError` и `StackOverflowError` не глотаются). Тест кидает оба
  `Error` и ждёт `MapEngineState.Failed`.
- **KDoc `MapEngineState` больше не обещает несуществующего**: отозванный ключ
  `initialize` не ловит — MapKit проверяет ключ на сервере при первой загрузке
  тайлов, и это видно только как пустая карта. Отдельное состояние появится
  вместе с подпиской на ошибки слоя.
- **Кнопка «моё местоположение» работает второй раз**: `applyCamera` сравнивал
  запрошенную камеру с прошлым **запросом**, поэтому повторный тап после
  панорамирования не делал ничего. Теперь сравнение с **фактическим**
  положением карты через `MapCameraFit.isSamePosition` (допуск ~1 м / 0.01
  зума) — это же убирает круг «карта → экран → карта»: подтверждение своего
  жеста больше не проигрывается обратно поверх инерции.
- **`isAppearanceOnly` перестал быть мёртвым кодом**: тап по маркеру меняет
  иконку готовой метки (`placemarks` по id), а не пересобирает всю
  кластеризованную коллекцию. В `MarkerDiff` появился отдельный список
  `moved`: переехавший маркер меняет состав кластеров, и быстрый путь для него
  неприменим (раньше он попадал в `changed` вместе со сменой выделения).
- **Кэш иконок кластера ключуется подписью**, а не числом мест: кучи на 100,
  101, 102… рисуются как «99+» и хранились отдельными одинаковыми битмапами.
- **Отписка той же обёрткой**: `WeakReference` для каждого слушателя создаётся
  один раз и держится полем. Сравнивает MapKit саму обёртку или referent — из
  API не следует, а одна ссылка верна при любом варианте. В
  `MapKitLocationProvider` заодно появилась сильная ссылка на слушателя на
  время ожидания (`pendingListener`): `requestSingleUpdate` держит только
  слабую.
- **Первый кадр карты не белая дыра**, а заглушка цвета скелетона: загрузка
  нативной библиотеки заметно дольше «десятков миллисекунд» из комментария.
- Мелочи: ключ уходит в `buildConfigField` через экранирование (`stringLiteral`),
  читается через `providers.environmentVariable`/`providers.fileContents` (иначе
  configuration cache не инвалидируется при смене ключа), `FOCUS_ZOOM` вместо
  `SINGLE_MARKER_ZOOM - 1f`.

Проверено: `./gradlew testDebugUnitTest` — **457 тестов в 53 классах, 0
падений**; `./gradlew assembleDebug` — BUILD SUCCESSFUL, предупреждений
компилятора в новом коде нет.

**Осталось открытым** (не блокеры мержа, полотно к экрану не подключено):
`MapScreen` по-прежнему рисует `MapCanvasPlaceholder` — подключение
`MapCanvas` и мёртвый теперь `MarkerClusterer` эпика 4 остаются следующим
шагом; на устройстве с ключом ничего из этого не проверено (эмулятора в CI
нет) — в первую очередь проверять тайлы, кластеры, отписку слушателей и
лицензионную плашку.

## Этап: discovery (эпик 4, issue #8, ветка claude/issue-8-*)

Главная, карта, поиск с фильтрами и карточка места. Экраны собраны из кита
эпика 2, вся логика — в чистых функциях домена и MVI-ViewModel'ях.

- **Домен** `feature/discovery/domain/`: `PlaceCategory` (шесть категорий ТЗ +
  `Other` для значений, которых ещё нет в приложении), `Place`/`GeoPoint`,
  `DiscoveryFilters` (+ `PlaceSort`), `PlaceFilterEngine` (фильтрация,
  сортировка, ранг релевантности, нормализация узбекского апострофа),
  `HomeSections` (блоки «рядом»/«рекомендуем»), `SearchHistory` (порядок,
  дедупликация, кодирование в одну строку). `feature/place/domain/`:
  `PlaceDetails`, `OpeningHours` + `OpeningHoursCalculator` (ночные смены,
  круглосуточно, «неизвестно» ≠ «закрыто»), `PlaceActions`.
  `feature/map/domain/MarkerClusterer` — сеточная кластеризация, от SDK не
  зависит.
- **Данные**: `CatalogApi` (пагинация `PlacePageDto`, карточка, отзывы),
  `PlaceMappers` (мягкий разбор: битое поле не роняет список),
  `CatalogRepository`/`DefaultCatalogRepository` — сеть с фоллбэком на Room.
  Правила: кэш отдаётся только на первой странице, фильтры к нему применяются
  теми же `PlaceFilterEngine`, пустой кэш — обычная ошибка, `PlacePage.fromCache`
  доезжает до UI; перезаписывается кэш только на запросе без единого
  ограничения (`DiscoveryFilters.isUnfiltered`). `SearchHistoryStore`
  (интерфейс + DataStore-реализация). `PlaceEntity` расширена (адрес,
  координаты, фото, контакты), БД — `version = 2`.
- **UI**: `feature/discovery/ui/home/` (главная: строка-кнопка поиска, плитка
  категорий, две секции, pull-to-refresh), `feature/discovery/ui/search/`
  (поиск с debounce 300 мс, история, шторка фильтров, пагинация по достижению
  конца списка), `feature/place/ui/` (галерея, описание, часы, контакты,
  действия, отзывы; `tel:` и `geo:` через intent'ы), `feature/map/ui/`.
  Маршруты `SearchRoute(categoryId?, query?)` и `MapRoute` — вне графа табов.
- **Тесты**: `PlaceFilterEngineTest`,
  `DiscoveryFiltersTest`, `HomeSectionsTest`, `SearchHistoryTest`,
  `PlaceCategoryTest`, `MarkerClustererTest`, `OpeningHoursCalculatorTest`,
  `PlaceActionsTest`, `PlaceMappersTest`, `PlaceFormattersTest`,
  `CatalogRepositoryTest` (MockWebServer + фейковый DAO: фоллбэк, пагинация,
  параметры запроса, TTL), `SearchHistoryStoreTest` (Robolectric + DataStore),
  `DiscoveryHomeViewModelTest`, `SearchViewModelTest`, `MapViewModelTest`,
  `PlaceDetailsViewModelTest`.

**Блокер 4.2 не закрыт**: картографический SDK (Yandex MapKit vs Google Maps)
не выбран, поэтому полотно карты — заглушка со списком маркеров. Всё, что от
SDK не зависит (загрузка, кластеризация, выбор маркера, «моё
местоположение»), сделано и покрыто тестами; подключение SDK меняет только
слой отрисовки `MapScreen`.

**Грабли, стоившие времени:** `SavedStateHandle.toRoute()` разбирает
типизированный маршрут через настоящий `Bundle`, а в юнит-тестах android.jar
заглушен (`isReturnDefaultValues = true`) — все аргументы читаются как `null`,
причём молча. Поэтому `SearchViewModelTest` и `PlaceDetailsViewModelTest`
идут под Robolectric.

**Не сделано / риски эпика 4:** картинок нет (загрузчика изображений в
проекте пока нет — вместо фото скелетоны), геолокация на карте только просит
разрешение эффектом, скриншот-тестов по-прежнему нет, вертикали
(очередь/бронь/заказ) из карточки — эффект `OpenVertical` без экрана.

### Слияние с эпиком 3 (онбординг)

Эпики 3 и 4 писались параллельно от одного коммита, поэтому ветка эпика 4
доносит в себе слияние с `main`:

- `MahallaNavHost` — граф онбординга берётся из эпика 3 (включая
  `onboardingStartDestination` и новые подписи экранов), main-граф и маршруты
  `SearchRoute`/`MapRoute` — из эпика 4.
- `MainDispatcherRule` объявлен в обеих ветках. Оставлена версия эпика 3
  (публичный `dispatcher`, по умолчанию `StandardTestDispatcher` — иначе
  таймер OTP не двигался бы виртуальным временем), а тесты discovery, которым
  нужна немедленная отправка корутин, передают `UnconfinedTestDispatcher()`
  явно.
- Строки, ключи DataStore, `MahallaComponentDefaults.touchTargets` и три
  общих теста (`GraphAssemblyTest`, `RoutesSerializationTest`,
  `TouchTargetTest`) содержат добавления обеих сторон.

**Важно про историю ветки:** слияние воспроизведено обычным коммитом, а не
merge-коммитом (`git merge` не в allow-list workflow `claude.yml`). Дерево
совпадает с `main` + эпик 4, но `origin/main` **не является предком** ветки,
поэтому GitHub мержит её 3-way от общего предка `daf9af5` и может показать
конфликт в файлах, куда добавляли обе стороны. Лечится одной командой локально:
`git merge -s ours origin/main` на ветке (дерево не меняется, main становится
вторым родителем) — либо разрешением конфликтов в пользу ветки.

### Правки после код-ревью PR #23

- **Тап по категории на главной вёл в историю поиска, а не в выдачу**:
  `SearchState.showHistory` смотрел только на пустоту запроса, а с главной по
  плитке приходят с пустым запросом и активным фильтром категории. Теперь
  история показывается только когда `filters.activeCount == 0`. Баг был не
  виден на пустой истории — то есть у всех, кроме живого пользователя.
- **Ответ сервера больше не фильтруется повторно**: `PlaceFilterEngine.apply`
  ищет по названию и адресу, а сервер — по описанию, меню и тегам. Выдача по
  «osh» вырезалась локально целиком → `ScreenState.Empty` при `hasMore = true`,
  а `loadMore()` выходит по `ScreenState.Content` — до следующих страниц было
  уже не добраться. Появился `PlaceFilterEngine.applyRemote`: сортировка та же
  (порядок онлайна и офлайна обязан совпадать) плюс досекание категорий,
  которые не поместились в запрос (`apiCategory()` отдаёт одну);
  `PlaceCategory.Other` при этом остаётся — иначе новые разделы каталога были
  бы невидимы до следующего релиза. Полный `apply` остался за кэшем: кроме
  этих правил у Room ничего нет.
- **Вечный спиннер после провала догрузки**: индикатор рисовался по `hasMore`,
  а не по `isLoadingMore`, и после ошибки крутился навсегда (список не вырос →
  `LaunchedEffect(places.size)` больше не срабатывает). Добавлено
  `SearchState.loadMoreFailed`: провал показывает кнопку «Повторить», крутилка
  живёт ровно столько, сколько идёт запрос, место под неё держится всегда,
  чтобы список не дёргался.
- **404 больше не маскируется кэшем**: `placeDetails` поднимает запись из Room
  только когда место не доехало (сеть, таймаут, 5xx). На `NotFound`/`Forbidden`
  возвращается ошибка, а запись из кэша удаляется (`PlaceDao.delete`) — иначе
  удалённое заведение всплывало бы ещё и в офлайн-выдаче.
- **Номер страницы считается локально** (`loadedPage = nextPage`): сервер, не
  вернувший поле `page`, отдаёт дефолтный `0`, и «следующей» навсегда
  оставалась бы первая.
- **`SearchHistory.decode` дедуплицирует** (`distinctBy` по нижнему регистру):
  список рисуется `items(key = { it })`, дубликат ключа роняет LazyColumn, а
  строку в DataStore мог записать прежний формат.
- `RoutesSerializationTest` получил `@OptIn(ExperimentalSerializationApi::class)` —
  двадцать предупреждений в логе сборки прятали бы настоящие.

Проверено: `./gradlew testDebugUnitTest` — **424 теста в 48 классах, 0 падений,
0 ошибок**; `./gradlew assembleDebug` — BUILD SUCCESSFUL, предупреждений
компилятора нет.

Открытые мелочи ревью PR #23 (задачами, не блокеры): `SearchEvent.QueryCleared`
обрабатывается, но UI его не шлёт (крестик идёт через `onQueryChange("")` и
попадает под debounce); `map_markers_count`/`map_cluster_places` просят
`<plurals>`; `OpeningHoursCalculator.isOpenAt` говорит «неизвестно» там, где
`weekSchedule` рисует «выходной»; пустой бейдж рейтинга в отзыве
(`RatingFormatter.format(0.0).orEmpty()`); `CategoryTile` использует
`Modifier.clickable` на `Surface` вместо `Surface(onClick = …)`.

## Этап: адрес бэкенда вводится в приложении (issue #26, ветка claude/issue-26-*)

Раньше `baseUrl` приходил только из `BuildConfig.API_BASE_URL` (buildType), то
есть сменить сервер можно было лишь пересборкой. Теперь адрес вводит
пользователь, и адрес сборки остался значением по умолчанию.

- **Экран** `feature/onboarding/ui/BackendUrl{Screen,ViewModel,Contract}.kt` —
  первый экран приложения, пока адрес не задан (`RootUiState.Ready.needsBackendUrl`,
  маршрут `BackendUrlRoute` вне обоих графов). Поле предзаполнено текущим
  адресом, кнопка «Вернуть адрес по умолчанию» рядом. Вернуться на экран можно
  и позже: кнопка «Изменить адрес сервера» на welcome (до входа) и строка в
  профиле с текущим адресом (после входа) — опечатка в хосте иначе запирала бы
  приложение навсегда.
- **Кто имеет право менять адрес**: `BuildConfig.BACKEND_URL_OVERRIDE` —
  в debug всегда `true`, в release `false`, пока сборку не собрали с
  `BACKEND_URL_OVERRIDE=true` (переменная окружения или `-P`). Выключено —
  маршрута нет в графе, кнопок нет, `BackendUrlStore` игнорирует и сохранённый
  адрес, и запись: в магазинной сборке увести приложение на чужой сервер не
  должен никто, а адрес из бэкапа debug-установки не должен переезжать в релиз.
- **Cleartext**: `res/xml/network_security_config.xml` — в release `http`
  разрешён только на loopback и `10.0.2.2`, в debug (`app/src/debug/res/xml/`,
  перекрывает файл из main) разрешён везде: сервер разработчика в локальной
  сети без TLS — норма. `CleartextPolicy` (`NetworkSecurityPolicy`
  .isCleartextTrafficPermitted) проверяет адрес до сохранения и говорит прямо
  «эта сборка запрещает http», а не «сервер не ответил». Такой адрес не
  сохраняется и по кнопке «всё равно сохранить»: по нему всё равно ничего не
  уйдёт.
- **Проверка перед сохранением**: `BackendReachability` (HEAD по адресу,
  таймауты 5 сек, свой OkHttp-клиент без интерцепторов). Успех — **любой**
  HTTP-ответ, включая 404/401: корень бэкенда обычно ничего не отдаёт. Сервер
  промолчал — показывается ошибка, а повторный тап сохраняет адрес как есть
  (`BackendUrlState.checked`): сервер может не отвечать на HEAD или подняться
  позже, запирать пользователя на первом экране нельзя.
- **Подстановка адреса**: `BackendUrl.normalize` (дописывает `http://`, срезает
  query/fragment, гарантирует завершающий `/`; `ws://`/`ftp://` отклоняет) и
  `BackendUrl.rewrite` — от пути запроса отрезается путь `baseUrl` сборки и
  подставляется путь введённого адреса, query сохраняется.
  `BackendUrlInterceptor` висит на **обоих** клиентах (основном и
  `@RefreshClient`): авторизация и refresh обязаны ходить на тот же сервер.
  Пересобирать Retrofit нельзя — все созданные API-интерфейсы держат старый
  экземпляр, поэтому переписывается URL запроса.
- **Хранение**: `BackendUrlStore` (`@Singleton`) — `@Volatile`-кэш поверх
  DataStore (`settings_backend_base_url`, `AppSettings.backendBaseUrl`).
  Кэш обязателен: интерцептор читает адрес на потоке OkHttp, где ждать
  DataStore нельзя. Поднимается один раз в `RootViewModel.resolveStart`
  (под держащимся splash'ем, до первого запроса), дальше меняется только
  записью с экрана. `save()` кладёт значение в кэш **до** записи: если DataStore
  недоступен, приложение всё равно должно ходить туда, куда попросили сейчас.
- **Тесты (+42, всего 499 в 59 классах, 0 падений)**: `BackendUrlTest`
  (нормализация и rewrite, включая экранирование в сегментах и чужой путь),
  `BackendUrlStoreTest` (в том числе сборка без права менять адрес),
  `BackendUrlInterceptorTest` (Retrofit собран на `baseUrl` сборки, запрос
  приезжает на MockWebServer с правильным путём и query),
  `BackendReachabilityTest`, `CleartextPolicyTest`, `BackendUrlViewModelTest`
  (включая отказ по cleartext), плюс новые случаи в `RootViewModelTest`.
  `assembleDebug` и `assembleRelease` — BUILD SUCCESSFUL.

**Не сделано / риски:**

- **На устройстве не проверено** (эмулятора в CI нет): ввод адреса, cleartext
  на API 28+, лицензионные ограничения не при чём — проверять руками.
- **Release с `BACKEND_URL_OVERRIDE=true` примет только https**: cleartext на
  произвольный LAN-адрес в релизе остаётся запрещённым намеренно. Нужен ли
  QA-вариант с открытым cleartext — вопрос к продукту (отдельный buildType
  вроде `qa` был бы честнее, чем послабление в релизе).
- В `MahallaNavHost` «экран стартовый или нет» определяется по
  `previousBackStackEntry == null` — работает для обоих входов (welcome и
  профиль), но при появлении новых лучше вынести в параметр.
- Смена адреса не разлогинивает: токен от старого сервера остаётся в
  `SessionStore` и на новом сервере окажется невалидным (лечится 401 →
  refresh → повторный вход). Осознанно, но проверить на живых стендах стоит.

## Этап: инспектор трафика Chucker (issue #30, ветка claude/issue-30-*)

Видно, какие запросы уходят на бэкенд: адрес, метод, query-параметры,
заголовки, тело запроса и ответа, код и время.

- **Библиотека**: `com.github.chuckerteam.chucker` 4.0.0. В debug приезжает
  `library`, в release — `library-no-op`: та же публичная поверхность, но
  пустая (`Chucker.isOp = false`). Поэтому код инспектора живёт в `main` и не
  ветвится по sourceSet'ам, а в release-манифесте Chucker'а нет ни одной
  строки (проверено на `assembleRelease`).
- **`data/network/inspector/`**: `HttpInspector` (интерфейс: `isAvailable`,
  `interceptor: Interceptor?`, `launchIntent(): Intent?`) и
  `ChuckerHttpInspector`. Интерцептор создаётся лениво — Chucker поднимает
  Room-базу транзакций, а граф собирается под держащимся splash'ем. В логе
  скрыты `Authorization`, `Cookie`, `Set-Cookie` (тот же список, что у
  `HttpLoggingInterceptor`), `maxContentLength` — 1 МБ, хранение — сутки,
  `alwaysReadResponseBody(true)` (иначе тело ответа, которое приложение не
  дочитало, в инспекторе пустое — а смотрят как раз такие случаи).
- **Место в цепочке**: сборка обоих клиентов переехала в
  `NetworkFactory.mainClient`/`refreshClient` — как и всё остальное в этом
  файле, ради того чтобы тест проверял production-конфигурацию. Инспектор
  добавляется **последним**: он обязан видеть запрос ровно таким, каким тот
  уходит в сеть — с фактическим хостом (его подставил `BackendUrlInterceptor`,
  issue #26) и с уже проставленным `Authorization`. Висит на обоих клиентах:
  вход, OTP и refresh идут по «голому» `@RefreshClient`, и смотреть на них
  нужно не меньше.
- **Точки входа**: строка «Сетевые запросы» в профиле (после входа) и кнопка
  на экране адреса бэкенда (до входа — профиль ещё недоступен, а запрос кода
  из SMS смотрят чаще всего именно там). Плюс штатные пути Chucker'а —
  уведомление и ярлык на иконке приложения. Обе точки видны, только когда
  `HttpInspector.isAvailable`.
- **Тесты (+11, всего 510 в 62 классах, 0 падений)**: `NetworkClientsTest`
  (инспектор видит переписанный адрес, Bearer и тело; тот же инспектор на
  refresh-клиенте, где токена нет; порядок цепочки; сборка без инспектора),
  `ChuckerHttpInspectorTest` (Robolectric: интерцептор есть и один, экран
  открывается), `ProfileViewModelTest` (новый) и новые случаи в
  `BackendUrlViewModelTest`. `assembleDebug` и `assembleRelease` —
  BUILD SUCCESSFUL.

**Не сделано / риски:**

- **На устройстве не проверено** (эмулятора в CI нет): сам экран Chucker'а,
  уведомление и ярлык.
- **Уведомления на API 33+**: `POST_NOTIFICATIONS` приезжает из манифеста
  Chucker'а (только в debug), но приложение это разрешение не запрашивает —
  пока пользователь не выдаст его руками, уведомления не будет. Точки входа в
  UI это закрывают; отдельный запрос разрешения ради debug-инструмента не
  делался.
- **Повтор запроса после refresh'а в списке не появляется**: его выполняет
  `Authenticator` ниже application-интерцепторов. Видно исходный запрос с 401;
  успешный повтор — только следующим вызовом API.
- **Редактирование заголовков тестом не покрыто**: проверить это можно только
  через базу транзакций Chucker'а. Значения перечислены в
  `ChuckerHttpInspector`, при добавлении новых секретных заголовков список
  надо пополнять руками.
- Chucker хранит тела в открытом виде в своей базе — это ещё одна причина
  никогда не собирать release с полным вариантом библиотеки.

## Этап: самоподписанный сертификат бэкенда (issue #32, ветка claude/issue-32-*)

Стенд живёт на `https://189.74.96.232/`, приложение падало на
`SSLHandshakeException: CertPathValidatorException: Trust anchor for
certification path not found`. На голый IP публичный сертификат не выдают,
поэтому сертификат самоподписанный, и Android рвёт handshake до отправки
запроса. На экране адреса это выглядело как `backend_url_unreachable`
(«Сервер не ответил») — то есть приложение врало про причину.

- **Диагностика вместо вранья**: `BackendReachability.check` возвращает не
  `Boolean`, а `BackendCheck` — `Reachable` / `Unreachable` /
  `UntrustedCertificate(ServerCertificate)`. Провал TLS отличается от молчания
  сервера (`SSLException`/`CertificateException`, включая `cause`), и только
  тогда делается второй заход — за сертификатом.
- **Чтение сертификата** — `tls/CertificateProbe.peerCertificate`: сырой
  `SSLSocket` с trust-all и без проверки имени, только handshake, HTTP-запроса
  нет вообще (ни заголовков, ни токена). Через OkHttp не вышло:
  `Response.handshake.peerCertificates` в 4.12 приезжает **пустым**, когда
  доверие цепочке не устанавливалось — на это ушло время, тест этот факт
  фиксирует. `host` передаётся в `createSocket` ради SNI.
- **Доверие по отпечатку (TOFU, как ssh)**, а не «выключить проверку»:
  `tls/PinnedCertificateTrustManager` сначала спрашивает платформенный trust
  manager и только при его отказе сверяет SHA-256 листового сертификата с
  подтверждённым пином; `PinnedCertificateHostnameVerifier` — то же исключение
  для проверки имени (без него пин бесполезен: сертификат на IP не проходит
  SAN). Любой другой сертификат, включая другой самоподписанный на том же
  хосте, по-прежнему отклоняется. Срок действия пина не проверяется намеренно:
  даты подтверждает CA, которого здесь нет, а просроченный сертификат стенда
  иначе запирал бы в цикле «не доверенный → доверять → не доверенный».
- **Штатный верификатор имени** берётся как `OkHttpClient().hostnameVerifier`,
  чтобы не зависеть от `okhttp3.internal.tls` и не разбирать SAN руками.
- **Где висит**: `NetworkFactory.clientBuilder(certificatePin = …)`, то есть на
  основном клиенте, на `@RefreshClient` (вход и код из SMS идут по нему — в
  issue падало именно там) и на клиенте проверки адреса. Ставится **всегда**, а
  не «когда пин задан»: отпечаток читается в момент handshake, поэтому
  подтверждение действует сразу, без пересборки клиентов, а пока пина нет
  поведение ровно то же, что у клиента по умолчанию.
- **Хранение** — `BackendCertificatePin` (`@Singleton`): `@Volatile`-кэш поверх
  DataStore (`settings_backend_certificate_pin`), `hydrate()` в
  `RootViewModel.resolveStart` рядом с `BackendUrlStore.hydrate()` и по той же
  причине (интерцептор/handshake читают синхронно). Гейт тот же
  `BuildConfig.BACKEND_URL_OVERRIDE`: доверять чужому сертификату имеет смысл
  только там, где можно указать и свой сервер, а пин из debug-установки не
  должен переезжать в релиз через бэкап. Пин один — подтверждение нового
  сертификата заменяет прежний.
- **Экран адреса**: ошибка `CERTIFICATE_UNTRUSTED` показывает subject, issuer и
  отпечаток целиком (формат `openssl x509 -noout -fingerprint -sha256` — строку
  должен сверять человек) плюс кнопка «Доверять этому сертификату». По ней пин
  записывается и адрес **проверяется заново**, а не сохраняется на слово.
- **Второй, независимый путь**: debug-конфиг
  (`app/src/debug/res/xml/network_security_config.xml`) получил
  `<certificates src="user"/>` — корневой сертификат стенда, установленный в
  настройках телефона, теперь работает. В релизе этого нет.
- Тесты (+31, всего **541 в 65 классах, 0 падений**): `PinnedCertificateTlsTest`
  и `NetworkClientsTest` — на настоящем MockWebServer с самоподписанным
  сертификатом (`okhttp-tls`, `testutil/SelfSignedServer`): без пина отказ, с
  пином 200, с чужим пином отказ, сертификат без подходящего SAN проходит
  только по пину, оба клиента ведут себя одинаково; `CertificateFingerprintTest`,
  `BackendCertificatePinTest`, новые случаи в `BackendReachabilityTest` и
  `BackendUrlViewModelTest`. `assembleDebug` и `assembleRelease` —
  BUILD SUCCESSFUL.

**Не сделано / риски:**

- **На устройстве не проверено** (эмулятора в CI нет). В первую очередь: на
  Android trust manager по умолчанию — Conscrypt'овский `RootTrustManager`, и
  наша обёртка вызывает у него двухаргументный `checkServerTrusted` (без
  хоста), то есть платформенная проверка идёт без per-domain правил
  network-security-config. Приложение эти правила и не настраивает (в конфиге
  только cleartext и user-CA в debug), но проверить handshake на живом стенде
  надо.
- **Правильное решение для прода — нормальный сертификат** (домен +
  Let's Encrypt) либо http в локальной сети. Пин — для стенда, а не замена
  сертификату: он у пользователя один и переезжает вместе с DataStore.
- Смена адреса не сбрасывает пин: он относится к конкретному сертификату, а
  не к хосту. Для другого сервера с валидным CA пин просто не понадобится.
- Тестов на «валидная цепочка от системного CA всё ещё проходит» нет: на JVM
  такой сервер не поднять. Закрыто модульно — тест проверяет, что при
  положительном ответе делегата пин вообще не спрашивается.

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
`claude setup-token`), по желанию `DESIGN_REPO_PAT` (fine-grained PAT,
Contents: read на `jack5505/mahalla` — тогда дизайн-репо подтягивается в CI
в `design-repo/`, иначе агент работает без ТЗ и макетов) и переменные
`BACKEND_IMAGE` / `BACKEND_PORT` / `BACKEND_HEALTH_PATH` (Settings → Secrets
and variables → Actions), закоммитить и запушить `.github/`, `CLAUDE.md`,
`.claude/`, `.gitignore` и этот файл, затем запустить workflow «Claude Code
Dev» вручную (workflow_dispatch) либо написать `@claude ...` в issue.

1. Локально (если нужен прогон на машине): SDK в `.sdk/`, cmdline-tools,
   platform-tools, `platforms;android-35`, `build-tools;35.0.0`, лицензии
   приняты (`yes | sdkmanager --licenses`); дальше `./gradlew assembleDebug`
   и `./gradlew testDebugUnitTest`. В облаке это делает `ci.yml` на PR.
2. Дождаться зелёного `ci.yml` на PR эпика 4 (discovery); если что-то падает —
   правки в ту же ветку.
3. Сверить онбординг с design-репозиторием (`TZ-ANDROID.md`, `SCREENS.md`,
   прототип) и с реальным контрактом OTP-эндпоинтов бэкенда — см. риски
   эпика 3. Для этого агенту нужен доступ к клонированию mahalla-репо
   (`git clone` / `gh api` в allow-list workflow).
4. Закрыть блокер 4.2 — выбрать картографический SDK (Yandex MapKit или
   Google Maps) и заменить заглушку полотна в `MapScreen`; дальше вертикали
   (food: place/menu/cart/checkout/order-status и т.д.).
4. Дальше — discovery/map/search, затем вертикали (food:
   place/menu/cart/checkout/order-status и т.д.).
5. `./gradlew` агенту в CI разрешён — **прогонять `testDebugUnitTest` и
   `assembleDebug` до пуша обязательно**. Три эпика подряд `main` оставался
   некомпилируемым (`Color.kt`, потом `Type.kt` + `MahallaApp.kt`) именно
   потому, что сборка откладывалась до PR.

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
