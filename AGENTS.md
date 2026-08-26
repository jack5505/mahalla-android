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
  по OAuth-токену подписки (секрет `CLAUDE_CODE_OAUTH_TOKEN`, значение из
  `claude setup-token`):
  - `claude-dev.yml` — ручной запуск (`workflow_dispatch`, ввод: задача, модель,
    max-turns): docker-бэкенд + postgres/redis + JDK 17 + Android SDK +
    gradle-кэш → Claude делает задачу, коммитит в ветку `claude-dev/*` и
    открывает PR (всё внутри action, ручного git-шага нет).
  - `claude.yml` — интерактивный ассистент на `@claude` в комментарии
    issue/PR, в ревью или в теле нового issue: то же Android-окружение,
    но без бэкенда и сервисов; ветки `claude/*`.
  - `ci.yml` — гейт на каждый PR: `testDebugUnitTest` + `assembleDebug`.

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
`CLAUDE_CODE_OAUTH_TOKEN` (значение — из `claude setup-token`) и переменные
`BACKEND_IMAGE` / `BACKEND_PORT` / `BACKEND_HEALTH_PATH` (Settings → Secrets
and variables → Actions), закоммитить и запушить `.github/`, `CLAUDE.md`,
`.claude/`, `.gitignore` и этот файл, затем запустить workflow «Claude Code
Dev» вручную (workflow_dispatch) либо написать `@claude ...` в issue.

1. Локально (если нужен прогон на машине): SDK в `.sdk/`, cmdline-tools,
   platform-tools, `platforms;android-35`, `build-tools;35.0.0`, лицензии
   приняты (`yes | sdkmanager --licenses`); дальше `./gradlew assembleDebug`
   и `./gradlew testDebugUnitTest`. В облаке это делает `ci.yml` на PR.
2. Дождаться зелёного `ci.yml` на PR эпика 3 (онбординг); если что-то падает —
   правки в ту же ветку.
3. Сверить онбординг с design-репозиторием (`TZ-ANDROID.md`, `SCREENS.md`,
   прототип) и с реальным контрактом OTP-эндпоинтов бэкенда — см. риски
   эпика 3. Для этого агенту нужен доступ к клонированию mahalla-репо
   (`git clone` / `gh api` в allow-list workflow).
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
