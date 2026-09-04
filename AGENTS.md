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

> Закрыто в issue #65: полотно подключено к экрану, `MarkerClusterer` удалён —
> см. раздел «Карта работает — полотно подключено к экрану» ниже.

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

## Этап: вертикаль «Еда» (эпик 5, issue #9, ветка claude/issue-9-20260826-1623)

Первая бизнес-вертикаль целиком: карточка места → меню → корзина → checkout →
статус заказа. Весь код — в `feature/food/`.

**Важно про историю:** предыдущий прогон по этому issue (ветка
`claude/issue-9-20260826-1444`) закончился до пуша — в remote его нет, эпик
написан заново с нуля.

- **Домен** `feature/food/domain/`:
  - `Menu` (`MenuCategory`/`MenuItem`/`OptionGroup`/`MenuOption`) — стоп-лист
    это флаг `isAvailable` у позиции, а не отдельный список: пропавшее блюдо
    человек будет искать глазами.
  - `MenuOptionRules` — правила выбора модификаторов чистыми функциями:
    одиночная группа заменяет выбор, множественная копит до `maxChoices`,
    последний вариант обязательной группы снять нельзя, предвыбор первым
    доступным, цена = база + дельты, порядок вариантов — по меню, а не по
    нажатиям.
  - `Cart`/`CartLine`/`CartTotals`/`CartCalculator` — ключ строки
    (`lineId` = позиция + отсортированные модификаторы: одно блюдо с разными
    добавками это две строки), лимит 99, ноль = удаление, скидка не больше
    суммы позиций, доставка сверху и не скидывается.
  - `PromoCode` — процент округляется **вниз** (округление вверх разошлось бы
    с чеком бэкенда), потолок скидки, минимальный заказ; `asPromoFailure()`
    раскладывает 404/410/409/422, всё прочее — «сетевая ошибка», иначе человек
    начнёт переписывать правильные буквы.
  - `Checkout` — `CheckoutForm`, `CheckoutValidator` (все ошибки сразу, «сейчас»
    параметром), `DeliverySlots` (получасовые слоты от «сейчас + 30 мин»,
    округление вверх — иначе первый же слот отвергается валидацией).
  - `Order`/`OrderStatus`/`OrderStatusFlow` — цепочка этапов зависит от способа
    получения (у самовывоза нет доставки, у доставки нет «готово к выдаче»),
    `Unknown` для незнакомых статусов, отмена только до `Preparing`.
- **Данные** `feature/food/data/`: `FoodApi` (`places/{id}/menu`,
  `places/{id}/promo`, `orders`, `orders/{id}`, `orders/{id}/cancel`,
  `wallet/balance`), мягкий маппинг (битая позиция не роняет меню, границы
  группы модификаторов приводятся к выполнимым), `MenuRepository` (меню
  **не кэшируется**: стоп-лист меняется в течение дня), `CartRepository`
  (Room + промокод в памяти), `OrderRepository` (успешный заказ чистит
  черновик, повтор возвращает позиции в корзину), `WalletRepository` (только
  баланс — полный кошелёк это эпик 8).
- **БД v3**: строка черновика корзины ключуется `placeId + lineId`, добавлены
  `optionIds`, `optionsLabel`, `placeName`, `deliverySum` (последние два
  денормализованы: корзину показывают до загрузки меню и без сети).
- **UI**: `ui/menu` (категории чипами, стоп-лист неактивен, шторка
  модификаторов с радио/чекбоксами и семантикой ролей, нижняя панель корзины,
  диалог «корзина другого заведения»), `ui/cart` (степпер, модификаторы
  строкой, промокод, итог), `ui/checkout` (сегментированный контрол
  доставка/самовывоз, адрес, слоты, кошелёк/наличные с показом нехватки,
  комментарий), `ui/order` (этапы, состав, отмена с подтверждением, повтор).
- **Кит**: `MahallaQuantityStepper` (`core/ui/components/Stepper.kt`) — «−» на
  единице превращается в «удалить», число моноширинными цифрами; новые цели
  нажатия `stepper` и `menuItem` в `MahallaComponentDefaults`.
- **Навигация**: `MenuRoute`/`CartRoute`/`CheckoutRoute`/`OrderStatusRoute`;
  `PlaceAction.Order` из карточки места теперь ведёт в меню (раньше эффект
  никуда не вёл). После оформления весь путь `menu → cart → checkout` уходит
  из стека, «назад» с экрана статуса ведёт на карточку заведения.
- **Опрос статуса** идёт раз в 5 секунд и сам прекращается на финальном
  статусе; события `ScreenStarted`/`ScreenStopped` (`LifecycleEventEffect`)
  останавливают его, пока экран в фоне.
- **Тесты (11 новых классов)**: `MenuOptionRulesTest`, `CartCalculatorTest`,
  `PromoCodeTest`, `CheckoutValidatorTest`, `OrderStatusFlowTest`,
  `FoodRepositoriesTest` (MockWebServer: тела запросов, мягкий разбор,
  очистка черновика только на успехе), `CartRepositoryTest` (Robolectric +
  Room), `MenuViewModelTest`, `CartViewModelTest`, `CheckoutViewModelTest`,
  `OrderStatusViewModelTest` (опрос по виртуальному времени); обновлены
  `MahallaDatabaseTest`, `TouchTargetTest`, `RoutesSerializationTest`,
  `GraphAssemblyTest`; фейки — `testutil/FoodFixtures.kt`.

Проверено: `./gradlew testDebugUnitTest` — **591 тест в 64 классах, 0
падений**; `./gradlew assembleDebug` — BUILD SUCCESSFUL, предупреждений
компилятора в новом коде нет.

**Не сделано / риски эпика 5:**

- **Контракт бэкенда выдуман** по образцу каталога эпика 4 (design-репозиторий
  в этом прогоне снова недоступен): пути, имена полей, коды ошибок промокода,
  формат `scheduledAt`. Сверить с `MAHALLA-IMPLEMENTATION.md` и поправить
  `FoodApi` + `asPromoFailure()`.
- **Вёрстка не сверена с `TZ-ANDROID.md`/`SCREENS.md`** — порядок блоков и
  тексты сделаны по описанию issue и киту эпика 2.
- **Оплата — эпик 8**: `WalletRepository` читает только баланс, реального
  списания нет; недостаток средств проверяется дважды (клиент и сервер), но
  «пополнить кошелёк» ведёт на экран-заглушку `WalletRoute`.
- **Промокод не переживает перезапуск** (живёт в памяти репозитория) — это
  сознательно: код выдан под конкретный состав корзины.
- **Картинок в меню нет** — загрузчика изображений в проекте по-прежнему нет.
- Разбор ошибки «позиция уехала в стоп-лист между корзиной и оформлением»
  сервер сообщит обычной HTTP-ошибкой; отдельного экрана сверки нет.
- Скриншот-тестов нет; шторка модификаторов, слоты времени и опрос статуса на
  устройстве не проверялись (эмулятора в CI нет).

### Правки после код-ревью PR #29

Ветка после слияния с `main` (PR #27, «адрес бэкенда») не компилировалась, плюс
закрыты четыре смысловых замечания ревью.

- **`compileDebugUnitTestKotlin` падал после мержа**: в PR #27 у
  `NetworkModule.provideRefreshClient()` появился обязательный аргумент
  `BackendUrlInterceptor`, `main` обновил все вызовы, а новый тест эпика 5
  (`GraphAssemblyTest`, food-граф) звал метод без аргумента. Хунки не
  пересекались, поэтому слияние прошло молча — ровно та грабля, о которой
  предупреждает этот файл.
- **Слоты доставки протухали на глазах**: список считался один раз в `init`
  `CheckoutViewModel`, а `CheckoutValidator` сравнивал выбор с живым «сейчас» —
  через минуту первый слот отвергался как `TimeTooSoon`, оставаясь в списке.
  Теперь слоты, итог и ошибки считаются в `revalidated()` от одного `now()`, а
  слот, до которого кухня уже не успевает, снимается вместе со списком (ошибка
  становится «выберите время», а не «слишком рано» на предложенном времени).
  В `DeliverySlots.next` округляются и секунды: `withSecond(0)` после
  прибавления запаса отдавал в 12:00:30 слот 12:30:00, то есть заведомо
  невалидный.
- **Обязательная группа модификаторов целиком в стоп-листе больше не запирает
  кнопку**: появился `MenuItem.isOrderable` (стоп-лист позиции **или**
  невыполнимая обязательная группа). Меню, `MenuViewModel.onItemClicked` и
  `MenuOptionRules.validate` смотрят на него — позиция рисуется недоступной, а
  шторка с кнопкой, которая никогда не включится, не открывается.
- **Промокод приводится к верхнему регистру по `Locale.ROOT`**: на турецкой
  локали устройства `i` уезжал в `İ`, и правильный код улетал на сервер
  испорченным.
- **Повтор заказа больше не теряет корзину**: `clearAll()` + цикл `add` заменены
  на `CartDraftDao.replaceAll` в одной транзакции (`CartRepository.replace`).
  `OrderRepository.repeat` возвращает `null`, если черновик собрать не удалось,
  и `OrderStatusViewModel` в этом случае остаётся на экране с сообщением
  (`order_repeat_failed`) вместо перехода в пустую корзину.
- **`CartCalculator.add` больше не удаляет строку** при нулевом или
  отрицательном количестве существующей строки — «добавить», которое удаляет,
  ловушка на будущее.

Проверено: `./gradlew testDebugUnitTest` — **646 тестов, 0 падений**;
`./gradlew assembleDebug` — BUILD SUCCESSFUL.

Открытым осталось: риски эпика 5 выше (выдуманный контракт `FoodApi`,
несверенная с ТЗ вёрстка, оплата — эпик 8) — они не блокеры мержа.

### Второй круг: `main` уехал вперёд (PR #31/#33/#35)

Пока PR #29 ждал, в `main` влились инспектор трафика (#30), пиннинг
сертификата (#32) и показ ошибки бэкенда пользователю (#34), и ветка снова
отстала. Тот же тест `GraphAssemblyTest` (food-граф) снова оказался бы
некомпилируемым: у `NetworkModule.provideRefreshClient()` в `main` теперь
четыре параметра (`httpInspector`, `certificatePin`, `overrideEnabled`), а
хунки при этом не пересекаются — слияние снова прошло бы молча. Чтобы этот
тест не ломался на каждом новом параметре сетевого модуля, он собирает
Retrofit на голом `okhttp3.OkHttpClient()`: проверяется сборка репозиториев
еды, а сетевой стек проверяют соседние тесты.

**После слияния с `main` доделать** (компилируется и без этого, но поведение
разъедется с issue #34): `MenuViewModel`, `OrderStatusViewModel` и
`CheckoutViewModel` кладут в `ScreenState.Error`/`submitError` только
`result.error`, а `main` перевёл остальные экраны на `result.failure` —
иначе текст, который прислал сервер, на экранах еды не покажется.

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

### Правки после код-ревью PR #33

Ревью нашло, что в release-сборке ломался бы **любой** https-запрос, и что
обход «сохранить непроверенный адрес» распространился на случай, для которого
не задумывался.

- **Release оставался бы без сети целиком.** `PinnedCertificateTrustManager`
  был обычным `X509TrustManager`, а для такого Conscrypt зовёт
  двухаргументный `checkServerTrusted(chain, authType)`. На Android делегат —
  `RootTrustManager` из network-security-config, и его двухаргументный вариант
  цепочку не проверяет вовсе: увидев в конфиге хоть один `<domain-config>`,
  он сразу кидает `CertificateException("Domain specific configurations
  require that the hostname aware checkServerTrusted … is used")`. А
  `<domain-config>` там есть — cleartext-исключение для loopback из issue #26.
  В release пина нет по построению (`BACKEND_URL_OVERRIDE = false`), значит
  фоллбэка тоже нет, и падал бы даже прод с валидным Let's Encrypt. Теперь
  trust manager — наследник **`X509ExtendedTrustManager`** с тремя
  перегрузками `checkServerTrusted` (`Socket`, `SSLEngine`, двухаргументная):
  host-aware вызов уходит host-aware делегату, и per-domain правила
  отрабатывают штатно. На JVM этого было не видно: тамошний PKIX отвечает на
  двухаргументный вариант нормально — поэтому 541 тест и был зелёным.
- **Второй замок на ту же дверь**: `certificatePin` доезжает до клиентов
  только там, где право на пин вообще есть (`NetworkModule`,
  `OkHttpBackendReachability`). Без него `allowPinnedCertificate` не трогает
  клиент ни своей `sslSocketFactory`, ни своим верификатором имени — release
  остаётся с платформенным TLS, чем бы ни кончилась история с trust
  manager'ом. Закреплено тестом в `GraphAssemblyTest`.
- **Повторный тап «Сохранить» больше не сохраняет адрес с недоверенным
  сертификатом.** `checked = true` ставился на любой исход проверки, и обход,
  задуманный на «сервер промолчал, пользователь настаивает», срабатывал и
  здесь — приложение уходило с адресом, на котором рвётся handshake, прямо
  вопреки комментарию в том же методе. Теперь
  `checked = result is BackendCheck.Unreachable`.
- **`BackendCertificatePin.save()` возвращает `Boolean`**, а не молчит: в
  сборке без права на пин ViewModel рисовала успех и перезапускала проверку —
  «Доверять» можно было жать бесконечно. Теперь экран возвращается в прежнее
  состояние и лишнего запроса не делает.
- **Сокет в `CertificateProbe` закрывается при любом провале**: `use`
  накрывает только TLS-обёртку, а упасть могут и `connect`, и сам
  `createSocket` — каждая неудачная проверка адреса текла дескриптором.
- Тесты: **546 в 65 классах, 0 падений** (+5). Новые — host-aware вызов
  вместо двухаргументного на делегате-`RootTrustManager` (и что пин через
  него всё ещё работает), платформенный TLS в сборке без override, второй тап
  при недоверенном сертификате, отказ пина в сборке без права.
  `assembleDebug` и `assembleRelease` — BUILD SUCCESSFUL.

**Не сделано / риски:**

- **На устройстве не проверено** (эмулятора в CI нет) — проверить handshake на
  живом стенде надо в первую очередь.
- **Правильное решение для прода — нормальный сертификат** (домен +
  Let's Encrypt) либо http в локальной сети. Пин — для стенда, а не замена
  сертификату: он у пользователя один и переезжает вместе с DataStore.
- Смена адреса не сбрасывает пин: он относится к конкретному сертификату, а
  не к хосту. Для другого сервера с валидным CA пин просто не понадобится.
- Тестов на «валидная цепочка от системного CA всё ещё проходит» нет: на JVM
  такой сервер не поднять. Закрыто модульно — тест проверяет, что при
  положительном ответе делегата пин вообще не спрашивается, и отдельно — что
  host-aware вызов уходит host-aware делегату (поведение `RootTrustManager`
  сымитировано, настоящего в JVM-тестах не будет).
- **Пин не привязан к хосту**: хранится голый отпечаток, и
  `PinnedCertificateHostnameVerifier` принимает доверенный сертификат для
  любого имени. Практического риска мало (нужен приватный ключ стенда), но
  пару `host + fingerprint` стоит завести задачей.


## Этап: ошибки бэкенда видны пользователю (issue #34, ветка claude/issue-34-*)

Тело ответа об ошибке больше не выбрасывается. Раньше на экране телефона
стояло «Bu amal uchun ruxsat yo'q» (маппинг 403 → `error_forbidden`), а сервер
в том же ответе присылал `GEO_PERMISSION_REQUIRED` с инструкцией на трёх
строках — увидеть её можно было только в инспекторе трафика.

- **Модель**: `core/result/ServerError` (httpCode, httpMessage, code, message,
  requestLine, body) и `ApiFailure` = классификация + ответ сервера.
  `ApiResult.Failure` и `ScreenState.Error` теперь держат `ApiFailure`;
  у обоих остались вторичный конструктор от `ApiError` и свойство `error`,
  поэтому 60+ мест с `Failure(ApiError.X)` и `result.error` не менялись, а
  сравнения по значению (`asOtpFailure`, `GONE_ERRORS`, тесты) продолжают
  работать. Именно из-за этих сравнений сообщение не прикручено внутрь
  вариантов `ApiError` — `Forbidden(message)` сломал бы равенство.
- **Разбор** (`core/result/ServerErrorParser`): тело обходится как дерево, а не
  декодируется в DTO — у бэкенда Mahalla это `{"success":false,"error":
  {"code","message"}}`, у Spring Security `{"error","error_description"}`, у
  nginx HTML. Ищутся `message`/`error_description`/`detail`/`title`/`reason`/
  `error` (последний — строкой) во вложенном `error`, потом в корне; код —
  `code`/`error_code`/`errorCode`. HTML, стектрейс и обрывок JSON текстом
  ошибки не становятся (остаются в подробностях), короткий plain text —
  становится. Одиночный JSON-литерал не считается разобранным JSON: иначе
  `Service unavailable` уехало бы на экран в кавычках. JSON в подробностях
  переформатирован с отступами (в нём же раскрываются `\n` из сообщения),
  тело обрезано до 2000 символов. Ошибка при разборе ошибки невозможна —
  всё под `runCatchingCancellable`.
- **UI**: `ApiFailure.userMessage()` — сообщение сервера, иначе прежний
  `messageRes()` (пустая строка от сервера сообщением не считается);
  `core/ui/components/MahallaErrorDetails` — свёрнутый блок «Подробности
  ошибки» с HTTP-кодом, кодом ошибки, `METHOD URL`, телом ответа
  (моноширинно, выделяется руками) и кнопкой «Копировать» — то, что человек
  отправит в поддержку. Подключено: `OnboardingApiError` (экраны телефона и
  OTP), `ApiErrorState` → значит все `ScreenStateHost` (главная, поиск, карта,
  карточка места), хвост выдачи поиска (`loadMoreFailed: Boolean` →
  `loadMoreFailure: ApiFailure?` — кнопка «повторить» без причины тоже была
  тупиком).
- **OTP**: ответ сервера с текстом показывается отдельным блоком даже когда
  ошибка классифицирована как «код неверный» — подпись поля отвечала не на тот
  вопрос. Без текста от сервера поведение прежнее: под полем «код неверный»,
  дублирующего блока нет.
- **Тесты (+17, всего 527 в 64 классах, 0 падений)**: `ServerErrorParserTest`
  (конверт Mahalla, плоские форматы, `\n`, HTML, стектрейс, битый JSON, null'ы,
  обрезка), `ApiFailureTest`, новые случаи в `ApiResultTest` (тело доезжает,
  без ответа `server == null`), `NetworkStackTest` (сквозной 403 на
  MockWebServer: message, code, requestLine, body), `PhoneInputViewModelTest`,
  `OtpViewModelTest`, `SearchViewModelTest`. `assembleDebug` и
  `assembleRelease` — BUILD SUCCESSFUL. Счёт меньше, чем у issue #32 выше
  (546): ветки писались параллельно от одного коммита, 527 — замер на ветке
  #34 до слияния, после мержа обеих счёт складывается.

**Не сделано / риски:**

- **На устройстве не проверено** (эмулятора в CI нет): раскрытие подробностей,
  копирование в буфер, перенос длинного JSON на узком экране.
- **Язык сообщения выбирает бэкенд**: если сервер ответит по-русски
  пользователю с узбекским интерфейсом, так и будет показано. Свои строки
  остаются только фоллбэком — это осознанное требование issue #34.
- **Тело ошибки может содержать лишнее** (внутренние адреса, id пользователей).
  Это видно только пользователю на его экране и попадает в буфер обмена
  осознанно; но если бэкенд начнёт присылать в ошибках чувствительные данные,
  показ тела придётся ограничить debug-сборкой.
- Скриншот-тестов по-прежнему нет: раскрытый блок подробностей проверялся
  глазами по `@ThemeLanguagePreviews`.
- `MahallaErrorDetails` не сообщает о копировании (на API 33+ подтверждение
  показывает система, ниже — ничего).

### Правки после код-ревью PR #35

- **До кнопки «Копировать» нельзя было добраться.** `ApiErrorState` рисуется
  из `ScreenStateHost` в `Box` внутри `Column(fillMaxSize())` — прокрутки нет
  ни на одном из четырёх экранов (главная, поиск, карта, карточка места). Сама
  `StateMessage` (иконка + заголовок + текст + кнопка повтора) занимает
  полэкрана, а под ней разворачивается тело ответа до 2000 символов, и кнопка
  копирования уезжала за нижнюю границу — ровно в том случае (длинный JSON,
  стектрейс), ради которого блок и делался. При `fontScale 1.5` не помещался и
  короткий ответ. Теперь у `ApiErrorState` есть `verticalScroll`. Внутрь
  `MahallaErrorDetails` прокрутку не клали намеренно: тот же блок живёт в
  элементе `LazyColumn` (хвост выдачи поиска), где высота не ограничена, и
  вложенный скролл там упал бы на бесконечных constraint'ах. На онбординге
  прокрутка была и раньше — её даёт `OnboardingStep`.
- **Сырое тело ответа больше не уезжает в магазинную сборку.**
  `MahallaErrorDetails(showRawResponse = BuildConfig.BACKEND_URL_OVERRIDE)`:
  адрес запроса и тело показываются там же, где разрешено менять адрес
  бэкенда и открывать Chucker (в debug всегда, в release только по явной
  сборке). HTTP-код и код ошибки видны всегда — их человек и называет
  поддержке, а внутренние адреса и id живут в теле. Это закрывает риск,
  который в прошлой версии раздела был только записан.
- **JSON-строка приезжала на экран в кавычках**: комментарий обещал, что
  одиночный литерал сообщением не станет, но код лишь исключал его из `json`,
  а `isPlainSentence()` пропускал — `"` не входит в `MARKUP_STARTS`. Тело
  `"Service unavailable"` (валидный ответ `ResponseEntity<String>` у Spring)
  давало сообщение с кавычками. Теперь строковый литерал разворачивается в
  содержимое (и в `message`, и в `body`), а числа и `true` сообщением не
  считаются.
- **`data` больше не источник текста ошибки**: в конверте Mahalla там полезная
  нагрузка, и `{"success":false,"data":{"title":"…"}}` давал бы сообщением
  заголовок ответа. Ищем в `error` и в корне.
- **Ловушка `equals` описана в KDoc `ApiResult.Failure`**: парсер заполняет
  `ServerError` всегда, поэтому `assertEquals(Failure(ApiError.X), result)`
  молча ложно для любого HTTP-отказа — сравнивать нужно `result.error`. В этом
  PR оно уже прострелило четыре теста.
- **На экране кода больше не два объяснения одного отказа**: текст сервера
  идёт подписью под ячейками (он точнее «код неверный» и про то же поле), а
  блок ниже показывает только подробности. Решение вынесено в `OtpState`
  (`fieldError`, `showApiMessage`) — иначе его нечем было бы протестировать.
- **Раскрывашка сообщает состояние TalkBack** (`stateDescription` +
  строки `state_expanded`/`state_collapsed`), раскрытие идёт через
  `animateContentSize()`.

Проверено: `./gradlew testDebugUnitTest` — **566 тестов в 67 классах, 0
падений, 0 ошибок** (+19 к замеру на ветке до этих правок); `assembleDebug` и
`assembleRelease` — BUILD SUCCESSFUL. Новых предупреждений компилятора нет.

Осталось непокрытым тестами (в проекте нет `compose-ui-test`, а эмулятора в CI
нет): прокрутка состояния ошибки, гейт `showRawResponse` и семантика
раскрывашки — только глазами по превью и руками на устройстве.

## Этап: lint зелёный и снова в CI (issue #39, ветка claude/issue-39-*)

`lintDebug` был выключен из `ci.yml` («в main есть давняя lint-ошибка») и
показывал **1 ошибку и 58 предупреждений**. Теперь отчёт — `No issues found`,
причём при `warningsAsErrors = true`: новое предупреждение роняет сборку.

- **Ошибка** `ProduceStateDoesNotAssignValue` (`MapCanvas`): `produceState` со
  ссылочным типом за `by` — lint не видит присваивания `value`. Заменено на
  `remember(initializer, retryKey) { mutableStateOf<MapEngineState?>(null) }` +
  `LaunchedEffect` с теми же ключами: смысл и перезапуск по «Повторить»
  прежние, обходного `@Suppress` не потребовалось.
- **Исправлено по существу**: `<plurals>` вместо строк с числом
  (`onboarding_otp_resend_timer` — в ru появились «секунду/секунды/секунд»,
  `otp_input_progress` — форма согласуется с длиной кода, поэтому quantity это
  `state.length`, а оба числа идут аргументами формата); удалён неиспользуемый
  `discovery_subtitle` (обе локали); монохромный слой лаунчер-иконки
  (`drawable/ic_launcher_monochrome.xml` + `<monochrome>` в обоих
  adaptive-icon) — без него на Android 13+ темизированная иконка была бы
  чужой; `bundle { language { enableSplit = false } }` — язык переключается
  внутри приложения (эпик 1.5), и split по локалям оставил бы выбранный язык
  нескачанным (`AppBundleLocaleChanges`); `Icons.AutoMirrored.*` вместо
  устаревших `Send`/`ReceiptLong` (два предупреждения компилятора).
- **Подавлено с объяснением рядом с кодом**: `DiscouragedApi` на
  `screenOrientation="portrait"` (ТЗ: только портрет), `CustomX509TrustManager`
  в `PinnedCertificateTrustManager` и `CertificateProbe` (issue #32 — это и
  есть суть решения), `AcceptsUserCertificates` + `InsecureBaseConfiguration`
  в `src/debug/res/xml/network_security_config.xml` (файл в release не
  попадает вовсе).
- **Отключено в `lint {}`** с причиной: `AndroidGradlePluginVersion` и
  `GradleDependency` (версии стека зафиксированы правилами проекта, а сама
  проверка ходит в сеть за списком версий — результат зависит не от кода),
  `OldTargetApi` (targetSdk 35 задан ТЗ и связан с compileSdk 35).
- **Грабля**: совет lint «переименуйте `mipmap-anydpi-v26` в `mipmap-anydpi`»
  выполнять нельзя — ресурсный мержер AGP 8.7.3 папку без версии молча
  выбрасывает, и сборка падает на `AAPT: error: resource mipmap/ic_launcher
  not found` (проверено). Поэтому единственное подавление, которое нельзя
  поставить рядом с кодом, живёт в новом `app/lint.xml` (`ObsoleteSdkInt` на
  эту папку).
- **Тесты**: `StringResourceParityTest` расширен на `plurals.xml` — паритет
  имён uz/ru, обязательный набор форм по CLDR (uz: one/other, ru:
  one/few/many/other), непустые формы и совпадение placeholder'ов во всех
  формах обеих локалей. Прогон: `./gradlew testDebugUnitTest` — **569 тестов в
  67 классах, 0 падений, 0 ошибок**; `lintDebug`, `assembleDebug`,
  `assembleRelease` — BUILD SUCCESSFUL.

**Не сделано / риски:**

- **Шаг в `ci.yml` возвращает пользователь**: GitHub App агента не имеет прав
  на `.github/workflows`, push с изменением workflow отклоняется. Нужная
  правка — вернуть `lintDebug` в команду job'а `checks`:
  `./gradlew --no-daemon lintDebug assembleDebug testDebugUnitTest`
  (и убрать комментарий про «давнюю lint-ошибку»).
- `warningsAsErrors = true` означает, что любое новое предупреждение lint —
  красный CI. Это и было целью, но при обновлении AGP список подавлений почти
  наверняка придётся пересматривать.
- Русские формы plurals («через 21 секунду») проверены только правилами CLDR,
  на устройстве не смотрели: эмулятора в CI нет.
- Монохромная иконка — та же временная заглушка-круг, что и обычная; вместе с
  брендовым логотипом меняются обе.
- `otp_input_description` и `pin_input_description` тоже просятся в plurals
  (замечание ревью PR #21), но lint их не ловит и в объём issue #39 они не
  входили — остаются задачей.

## Этап: регистрация по контракту бэкенда (issue #42, ветка claude/issue-42-*)

Первый же запрос приложения падал: на экране телефона `auth/otp/request`
возвращал 400 с текстом «Joylashuv ruxsatini yoqing». Контракт авторизации на
бэкенде (jack5505/mahalla#80) разошёлся с клиентским по всем пунктам сразу.

**Как снят контракт.** Репозиторий `jack5505/mahalla` агенту в CI недоступен
(`DESIGN_REPO_PAT` не задан, `gh` отдаёт 404), зато стенд отвечает:
`https://189.74.96.232/v3/api-docs` — полная OpenAPI-схема, плюс
`/swagger-ui/index.html`. Дальше контракт проверялся прямыми curl-запросами.
**Это самый быстрый способ узнать реальный API, когда дизайн-репо не
подключено.**

Что изменилось (эндпоинты `bank-auth`, префикс `api/v1/` уже входит в
`API_BASE_URL`):

| | было в приложении | стало |
|---|---|---|
| пути | `auth/otp/request`, `auth/otp/verify` | `auth/send-otp`, `auth/verify-otp` |
| запрос | `{phone}` / `{phone, code}` | `{phone, device, lat, lng}` / `{otpToken, otpCode, device, lat, lng}` |
| ответ | «голый» JSON | конверт `{success, message, data, error{code,message}, timestamp}` |
| связь шагов | телефон + код | `otpToken` из ответа `send-otp` |
| токены | `accessToken/refreshToken/expiresIn` | `data.tokens{accessToken, refreshToken, accessExpiresIn, refreshExpiresIn}` |
| refresh | `{refreshToken}` | `{refreshToken, device, lat, lng}` |
| logout | `{refreshToken}` в теле | без тела: заголовок `X-Session-Id`, query `allDevices` |

Реализация:

- **Конверт**: `data/network/ApiResponse.kt` (`ApiResponse<T>` + `ApiErrorBody`
  + `payload()`). Отказ с HTTP 4xx/5xx по-прежнему разбирает `ServerErrorParser`
  (issue #34 — он уже умеет вложенный `error.code/message`), а ответ **2xx с
  `success:false`** бросает `ApiEnvelopeException` → новый вариант
  `ApiError.Business(code)`. `data == null` при `success:true` — тоже отказ:
  «успех» без данных это пустой экран без объяснения.
- **Устройство**: `data/device/` — `DeviceDescriptor` (deviceId, platform
  `ANDROID`, модель, версия ОС, версия приложения; `fcmToken` пока `null` —
  FCM в проекте нет) и `DeviceIdStore`: UUID установки в DataStore
  (`device_id`), под `Mutex` (два параллельных запроса иначе завели бы два
  устройства), отказ хранилища не запирает вход — id живёт в памяти процесса.
  Не `ANDROID_ID`: тот привязан к аккаунту Google и на части прошивок общий
  для приложений.
- **Координаты**: `data/location/` — `LocationSource` (последняя известная
  позиция через `LocationManager`, только если разрешение уже выдано; свежую
  не запрашиваем — это ожидание фикса на экране ввода номера) и
  `RequestLocationProvider`: позиция → центр выбранного города → центр
  Ташкента. **Так и должно быть**: `lat`/`lng` обязательны, а разрешение
  онбординг просит на последнем шаге (3.6), то есть на экране телефона
  настоящих координат ещё нет. `City` получил `latitude`/`longitude`.
- **Сессия**: `Session.sessionId` (ключ `session_id`) — уходит в `X-Session-Id`
  при выходе; refresh без него прежний id не стирает.
- **OTP**: `OtpChallenge.otpToken`, `OtpRoute.otpToken` (+ `OtpArgs.OTP_TOKEN`),
  `OtpState.otpToken`; `verifyCode(otpToken, code)` вместо `(phone, code)`;
  повторная отправка заменяет токен. Ответ без `otpToken` — `ApiError.Serialization`:
  уходить на экран ввода бессмысленно, проверять код будет нечем.
- **Классификация ошибок кода**: `asOtpFailure()` переехал с `ApiError` на
  `ApiFailure` и смотрит сперва на `error.code` бэкенда. Иначе 400 на
  `OTP_EXPIRED` и 400 на `VALIDATION_ERROR` (нет координат) одинаково
  становились бы «код неверный» — со стиранием ввода и заблокированным
  повтором. Точные коды → ключевые слова (`EXPIRED`, `ATTEMPT`, `COOLDOWN`,
  `OTP`…) → прежняя раскладка по HTTP-коду.
- `TokenAuthenticator` шлёт устройство и координаты тоже: для бэкенда refresh
  — это продление сессии конкретного устройства.

**Проверено**: `./gradlew testDebugUnitTest` — **593 теста в 70 классах, 0
падений**; `lintDebug`, `assembleDebug`, `assembleRelease` — BUILD SUCCESSFUL.
Новые тесты: `ApiResponseTest`, `DeviceIdStoreTest`, `RequestLocationProviderTest`,
переписанный `AuthRepositoryTest` (тела запросов, конверт, `otpToken`,
`X-Session-Id`, 200 с `success:false`) и `OtpFailureTest`. Сверх тестов — тот
же запрос отправлен на стенд руками: полное тело возвращает 200 и `otpToken`
(`expiresInSeconds: 180`, `cooldownSeconds: 60`, `channel: SMS`).

**Не сделано / риски:**

- **Координаты на экране телефона приблизительные** (центр города). Если
  бэкенду нужна именно измеренная позиция, шаг геолокации придётся перенести в
  начало онбординга — это решение продукта.
- **`nextStep` (`SETUP_PIN`/`ENTER_PIN`/`NONE`) и серверные PIN-эндпоинты**
  (`auth/setup-pin`, `auth/pin-login`, `auth/pin-resume`) не используются: PIN
  в приложении локальный (эпик 3.4). Расхождение осознанное, но при появлении
  app-lock его придётся закрывать.
- **`isNewUser` теперь выводится из пустого `user.fullName`** — отдельного поля
  бэкенд не отдаёт.
- **Остальной API под тот же конверт не переведён**: `CatalogApi` (эпик 4)
  ждёт «голый» JSON и на живом бэкенде развалится — это отдельная задача того
  же рода, вне объёма issue #42.
- **Telegram-канал доставки кода** (`channel: TELEGRAM`, `auth/telegram/*`) в
  UI никак не отражён: текст на экране всегда про SMS.
- На устройстве не проверено (эмулятора в CI нет): реальный SMS-путь,
  разрешение геолокации, повторная отправка.

## Этап: адрес стенда подставляется по умолчанию (issue #44, ветка claude/issue-44-*)

Экран ввода адреса бэкенда (issue #26) предзаполнялся адресом сборки, а в debug
им был эмуляторный `http://10.0.2.2:8080/api/v1/` — то есть при первом запуске
на телефоне приложение по умолчанию не ходило никуда, и адрес приходилось
набирать руками.

- **Дефолт debug-сборки — стенд**: `API_BASE_URL =
  https://189-74-96-232.nip.io/api/v1/` (`app/build.gradle.kts`). Дальше
  ничего менять не пришлось: `BackendUrlStore.buildDefault` уже отдаёт это
  значение в поле экрана и в кнопку «вернуть адрес по умолчанию». Release
  по-прежнему смотрит на прод (`https://api.mahalla.uz/api/v1/`).
- **`nip.io` вместо голого IP**: домен резолвится в тот же `189.74.96.232`, но
  на него выписан сертификат Let's Encrypt (проверено:
  `issuer=C = US, O = Let's Encrypt, CN = YE1`, SAN `189-74-96-232.nip.io`).
  Значит доверие по отпечатку (issue #32) для стенда больше не нужно —
  handshake проходит платформенным TLS. Механизм пина остаётся: он про любой
  другой самоподписанный стенд.
- **Путь `api/v1/` входит в baseUrl** и в дефолте сохранён: эндпоинты
  объявлены относительно него (issue #42). Проверено на стенде — GET
  `/api/v1/auth/send-otp` доходит до эндпоинта, `/auth/send-otp` отдаёт 401 от
  общего фильтра. Адрес из issue (`https://189-74-96-232.nip.io/`) без этого
  префикса дал бы 404 на каждом запросе.
- **Тест** `BackendDefaultUrlTest`: дефолт уже нормализован (иначе кнопка «по
  умолчанию» подставляла бы одну строку, а сохранялась бы другая), кончается на
  `/api/v1/`, а в debug равен адресу стенда и работает по https.

Проверено: `./gradlew testDebugUnitTest` — **596 тестов в 71 классе, 0
падений, 0 ошибок**; `lintDebug` — `No issues found`; `assembleDebug` и
`assembleRelease` — BUILD SUCCESSFUL.

**Не сделано / риски:**

- **Локальный бэкенд теперь вводится руками**: `http://10.0.2.2:8080/api/v1/`
  на экране адреса (cleartext в debug разрешён, issue #26). Отдельного
  переключателя «эмулятор/стенд» нет.
- **Сохранённый адрес важнее дефолта**: у кого в DataStore уже лежит
  `https://189.74.96.232/`, увидит его — новый дефолт увидят только чистая
  установка и кнопка «вернуть адрес по умолчанию».
- **Адрес стенда зашит в сборку** и переживёт стенд: когда появится домен
  прода, дефолт меняется здесь же одной строкой.
- На устройстве не проверено (эмулятора в CI нет): доверие сертификату nip.io
  системным хранилищем Android и первый запрос по новому адресу.

## Этап: регистрация через Telegram-бот (issue #46, ветка claude/issue-46-*)

Бесплатная замена платному SMS: если на устройстве есть Telegram, вход идёт
через бота `@MahallaVerifyBot` и не стоит ничего.

**Контракт снят со стенда** (`https://189-74-96-232.nip.io/v3/api-docs` +
живые curl'ы — дизайн-репо агенту по-прежнему недоступно). Эндпоинты на
бэкенде **уже были**, приложение о них не знало:

| | контракт |
|---|---|
| `POST auth/telegram/init` | `{device, lat, lng}` → `{deepLinkToken, telegramBotUrl, expiresInSeconds: 300}` |
| `POST auth/telegram/check` | `{deepLinkToken, device, lat, lng}` → токены, либо 400 `TG_PENDING` |

**Важно: это не «SMS-код через Telegram», как просил issue, а вход целиком.**
После нажатия Start `check` отдаёт пару токенов сразу — кода вводить не нужно,
экран OTP в этом пути не участвует. Результат тот же (бесплатно), путь короче
на один экран. Токены лежат в **корне** ответа, а не в `tokens`, и `sessionId`
не приходит вовсе — при выходе `X-Session-Id` будет пустым.

- **Домен** `feature/auth/domain/TelegramLogin.kt`: `TelegramChallenge` (+ `of()`
  с клампингом срока, как у `OtpChallenge`), `TelegramLoginState`
  (`Pending`/`Confirmed`), `TelegramBotLink` (проверка ссылки),
  `TelegramPollSchedule` (пауза опроса), `isTelegramPending()`,
  `isTelegramPollRecoverable()`.
- **Данные**: `AuthApi` + 4 DTO; `AuthRepository.startTelegramLogin()` /
  `checkTelegramLogin()`; `TelegramAvailability` — наличие Telegram на
  устройстве.
- **UI**: `feature/onboarding/ui/TelegramLogin{Screen,ViewModel}.kt` +
  `TelegramContract.kt`, маршрут `TelegramRoute`. Вход — кнопка «Telegram
  orqali kirish» на экране телефона, **видна только когда Telegram
  установлен**; SMS остаётся второй кнопкой и доступен с экрана Telegram в
  любой момент.

**Безопасность (issue просил про неё прямо):**

- **Ссылка на бота проверяется до открытия** (`TelegramBotLink.sanitize`):
  только `https` на `t.me`/`telegram.me`/`telegram.dog` либо схема `tg:`.
  Ссылку присылает сервер, а адрес сервера в debug вводит пользователь (issue
  #26) — без проверки подменённый бэкенд запускал бы на устройстве
  произвольный intent (`market://`, `intent://`, чужой deep link, в том числе
  наш `mahalla://`). Userinfo не маскирует хост: `https://t.me@evil.example/`
  отклоняется (тест закрепляет).
- **Intent адресуется конкретному пакету Telegram**: `https://t.me/…` умеет
  открывать любой браузер, а одноразовый токен входа не должен уезжать никуда,
  кроме Telegram.
- **Токен не хранится**: живёт только в поле ViewModel. В аргументы маршрута
  не вынесен намеренно — там он попал бы в `SavedStateHandle` и пережил бы
  смерть процесса.
- **Срок жизни соблюдается и клиентом** (`withTimeoutOrNull` по абсолютному
  сроку от `Clock`): опрос не переживает токен ни на секунду, «Попробовать
  снова» всегда просит **новый** `init`, а не переиспользует прежний.
- **Клиент ничего не решает сам**: «вошёл» — только когда сервер вернул
  токены. `TG_PENDING` — единственный сигнал «ждём».
- **`requiresPhoneVerify: true` не сохраняет сессию** — пользователь уходит на
  обычный SMS-путь. Полуавторизованное состояние («токены есть, телефон не
  проверен») в приложении не заводится: отличить его потом от нормального
  входа было бы нечем.
- **Оферта обязательна и на Telegram-пути**: обойти согласие через бесплатную
  кнопку нельзя.
- **Опрос с растущей паузой** (1.5 → 5 сек): на стенде лимита на `init`/`check`
  нет (проверено — 4 подряд `init` дают 200, 5 подряд `check` проходят), так
  что сдержанность целиком на клиенте. За 5 минут выходит ~61 запрос.
- **`<queries>` в манифесте** (схема `tg` + 4 пакета): с API 30 без него
  `PackageManager` отвечает «Telegram не установлен» на устройстве, где он
  есть, — и приложение уводило бы человека на платное SMS.

**Проверено**: `./gradlew testDebugUnitTest` — **621 тест в 73 классах, 0
падений**; `lintDebug` — `No issues found`; `assembleDebug` и `assembleRelease`
— BUILD SUCCESSFUL. Новые тесты: `TelegramLoginTest` (ссылка, сборка
испытания, классификация, расписание), `TelegramLoginViewModelTest`
(подтверждение, перепроверка на возврате, истечение, повтор с новым токеном,
потеря сети, отказ сервера, `requiresPhoneVerify`, уход на SMS), 6 новых
случаев в `AuthRepositoryTest` на MockWebServer.

**Не сделано / риски:**

- **Успешный путь до конца не проверен на живом стенде**: нажать Start в боте
  из CI нечем (нет Telegram-аккаунта). Проверены `init` (200 + ссылка) и
  `check` до подтверждения (`TG_PENDING`); форма ответа после Start взята из
  OpenAPI-схемы (`AuthTokenResponse`). **Это первое, что надо проверить
  руками.**
- **Замечание бэкенду (не блокер, чинить в jack5505/mahalla):**
  `deepLinkToken` **не привязан к устройству** — `check` с чужого `deviceId`
  отвечает так же (`TG_PENDING`), хотя `device` в запросе есть. Значит сессию
  заберёт тот, кто владеет токеном. Практический риск низкий (токен идёт
  только «приложение → Telegram» и живёт 5 минут), но сверку `deviceId` из
  `init` стоит завести задачей. Там же — отсутствие rate limit на `init`.
- **`TG_PENDING` приходит и на несуществующий токен** — отличить «ещё не
  нажали» от «токен выдуман» нельзя. Для клиента разницы нет (обе ситуации
  кончаются истечением), но диагностику это ухудшает.
- **Telegram-канал у `send-otp` (`channel: TELEGRAM`) по-прежнему не отражён в
  UI**: если бэкенд сам выберет доставку кода в Telegram, текст на экране всё
  равно скажет про SMS. Отдельная задача.
- `PhoneInputViewModel` спрашивает `PackageManager` в конструкторе (главный
  поток) — один binder-вызов, но при разрастании стартового экрана это первое,
  что стоит унести в фон.
- На устройстве не проверено (эмулятора в CI нет): открытие бота, выбор клиента
  при нескольких Telegram-ах, возврат по `ON_RESUME`, поведение при удалённом
  во время ожидания Telegram.

### Правки после issue #49 (Telegram подтвердил — экран крутится)

Первый живой прогон Telegram-входа: бот получил контакт, `auth/telegram/check`
ответил `200` с парой токенов — и приложение осталось на бесконечной крутилке.

- **Что отвечает стенд**: `success: true`, токены в корне `data`,
  `requiresPhoneVerify: true`, в JWT `verificationStatus: UNVERIFIED` (номер
  уже известен — его сообщил бот). По схеме `AuthTokenResponse` это штатное
  поле, а отдельного эндпоинта «привязать телефон к сессии» у бэкенда нет
  (`/v3/api-docs`: только `send-otp`/`verify-otp`). То есть контракт исполнен
  обеими сторонами — **вход через Telegram у нового пользователя один раз
  добивается SMS-кодом**, и это не ошибка сервера.
- **Причина крутилки — клиентская.** `confirm()` на `requiresPhoneVerify` слал
  только одноразовый эффект, оставляя `status = WAITING` и обнуляя
  `deepLinkToken`. Экран продолжал крутить «ждём подтверждения», `ScreenResumed`
  выходил по `deepLinkToken ?: return` — состояние без выхода. Единственным
  шагом дальше был молчаливый `popBackStack(PhoneRoute)`: сработал — человек
  без объяснений оказывался на форме номера, не сработал (или эффект приехал,
  пока приложение было в фоне) — вечная крутилка.
- **Стало**: два новых статуса, `CONFIRMED` и `PHONE_VERIFY`. Статус уходит из
  `WAITING` в любом исходе, поэтому крутилка не может пережить ответ сервера.
  `PHONE_VERIFY` — это экран с объяснением («Telegram вас узнал, но номер нужно
  один раз подтвердить кодом»), названным номером (`user.phone` доехал до
  `TelegramLoginState.Confirmed.phone`) и главной кнопкой «Подтвердить номер по
  SMS». **Автоматически на форму номера больше не уводим**: молчаливый возврат
  к началу — ровно то, что человек читает как «ничего не произошло».
- **Гонка на возврате из Telegram закрыта**: `ScreenResumed` делает
  `pollJob?.cancel()`, а подтверждение применялось уже **после** выхода из
  `withTimeoutOrNull` — то есть опрос, который только что получил подтверждение,
  мог быть отменён до `confirm()`, и одноразовый токен пропадал вместе с ним.
  Теперь `confirm()` вызывается прямо в цикле, сразу после ответа сервера:
  между ними нет точки приостановки, а отмена вклинивается только в них.
- **`popBackStack` больше не тупик**: если экрана телефона в стеке нет,
  навхост делает `navigate(PhoneRoute)` с `popUpTo(TelegramRoute)`.
- Тесты (+4, всего **624 в 73 классах, 0 падений**): статус и номер при
  `requiresPhoneVerify`, отсутствие автоперехода, возврат на экран после
  подтверждения ничего не перезапускает, подтверждение не теряется при
  одновременном `ScreenResumed`, статус `CONFIRMED` на успешном входе; в
  `AuthRepositoryTest` — `user.phone` доезжает до домена. `lintDebug` — `No
  issues found`, `assembleDebug` и `assembleRelease` — BUILD SUCCESSFUL.

**Не сделано / риски:**

- **Успешный путь без `requiresPhoneVerify` на живом стенде по-прежнему не
  проверен**: у issue #46 это был главный риск, и он остаётся — нужен аккаунт,
  который уже подтвердил номер SMS-кодом. Скриншот issue #49 закрывает только
  ветку «номер не подтверждён».
- **Номер на форму не подставляется**: человек знает, какой номер подтверждать
  (он написан на экране), но вводит его руками. Прокинуть его аргументом
  `PhoneRoute` мешает то, что маршрут — `data object`, и `popBackStack` по
  маршруту с аргументом сверяет их со значениями записи в стеке. Отдельная
  задача.
- **Вопрос к бэкенду (не блокер)**: считать ли контакт из Telegram
  подтверждением номера. Сейчас Telegram-вход для нового пользователя всё
  равно заканчивается платным SMS — то есть бесплатным он получается только со
  второго раза. Если это не задумано, чинить в `jack5505/mahalla`.
- `send-otp` умеет `channel: TELEGRAM` — возможно, добить номер можно кодом из
  того же бота, бесплатно. В UI канал по-прежнему никак не отражён (задача с
  issue #46).

## Этап: вход завершается PIN-шагом бэкенда (issue #51, ветка claude/issue-51-*)

Верный SMS-код заканчивался экраном «Nimadir xato ketdi», и дальше приложение
не шло.

**Причина.** `auth/verify-otp` токенов не выдаёт. При верном коде он отвечает
200, создаёт сессию и говорит `nextStep`; токены отдаёт **следующий** запрос —
`auth/setup-pin` (по `sessionId` из ответа) либо `auth/pin-login` (по
устройству). `DefaultAuthRepository.verifyCode` требовал `data.tokens`, не
находил их и возвращал `ApiError.Serialization` → строка `error_unknown`.
Расхождение было записано в этом файле как незакрытое ещё с issue #42 («PIN в
приложении локальный, серверные PIN-эндпоинты не используются») — оно и
выстрелило.

**Как снят контракт** (дизайн-репо агенту по-прежнему недоступно):
`https://189-74-96-232.nip.io/v3/api-docs` + прямые curl'ы. Решающая проверка —
`setup-pin` и `pin-login` **анонимны**: без заголовка `Authorization` они
отвечают `SESSION_EXPIRED` и `DEVICE_UNKNOWN`, то есть ищут сессию и
устройство, а не токен. Значит до PIN-шага токенов действительно нет.
Схемы стенда при этом достоверны — форма ответа `telegram/check` из issue #49
совпала с `AuthTokenResponse` из той же схемы.

| | было | стало |
|---|---|---|
| `verifyCode` | `ApiResult<LoginResult>`, токены обязательны | `ApiResult<VerificationResult>`: `Authorized` либо `PinRequired` |
| PIN | только локальный замок, 4 цифры | он же аккаунтный, **6 цифр** (`^[0-9]{6}$` — требование бэкенда) |
| эндпоинты | — | `auth/setup-pin`, `auth/pin-login` |

- **Домен** `feature/auth/domain/ServerPin.kt`: `ServerPinStep`
  (`Setup`/`Enter`), `ServerPinChallenge`, `VerificationResult`,
  `ServerPin.stepOf()` — разбор `nextStep` с фоллбэком на `user.pinSetup`
  (незнакомое значение не повод сдаться: раз токенов нет, PIN-шаг всё равно
  предстоит).
- **Репозиторий**: `pendingServerPin` — `@Volatile`-поле, **только в памяти**
  (`sessionId` — одноразовый пропуск к токенам, переживать перезапуск он не
  должен); `completeServerPin(pin)` сам выбирает `setup-pin` или `pin-login`.
  `logout()` испытание сбрасывает.
- **Экран PIN**: незавершённый вход важнее локального состояния. `Setup` →
  Create+Confirm, и подтверждённый код уходит **сначала на сервер**, потом в
  Keystore: пока бэкенд не принял PIN, сессии нет и защищать нечего. `Enter` →
  Unlock, но проверяет сервер (локального хэша на новом устройстве нет), а
  принятый код становится и локальным. Счётчик попыток в серверном режиме
  ведёт сервер: свой стёр бы PIN и сессию раньше времени, а сообщения об
  оставшихся попытках расходились бы. Отказ показывается текстом бэкенда
  (`PinState.apiFailure` + `OnboardingApiError`, issue #34).
- **Длина PIN хранится** (`PreferenceKeys.PinLength`, `PinStorage.configuredLength()`):
  иначе экран блокировки нарисовал бы шесть ячеек тому, кто задал
  четырёхзначный код в прошлой версии, и ввести его стало бы нечем. Ключа нет
  — PIN достался от версии до issue #51, значит 4.
- **Тупик после смерти процесса закрыт**: ни сессии, ни PIN, ни испытания —
  экран уводит на повторный вход (`AuthRestartRequired`), а не даёт придумать
  PIN и уйти в приложение, где каждый запрос отвечает 401.

Проверено: `./gradlew testDebugUnitTest` — **641 тест в 74 классах, 0 падений**;
`lintDebug` — `No issues found`; `assembleDebug` и `assembleRelease` — BUILD
SUCCESSFUL. Новые тесты: `ServerPinTest`, шесть случаев в `AuthRepositoryTest`
на MockWebServer (тела `setup-pin`/`pin-login`, отказ сохраняет испытание,
`SETUP_PIN` без `sessionId`, PIN без испытания в сеть не уходит), шесть в
`PinViewModelTest` (оба серверных шага, отказ бэкенда, старая длина PIN,
перезапуск входа) и два в `KeystorePinStorageTest`.

**Не сделано / риски:**

- **Успешный путь на живом стенде не проверен**: SMS-кода в CI нет, а угадать
  его нельзя. Проверены только отказы (`OTP_EXPIRED`, `OTP_INVALID` с остатком
  попыток) и анонимность PIN-эндпоинтов. **Это первое, что надо проверить
  руками.**
- **PIN стал шестизначным** — против ТЗ (там 4 цифры, эпик 3.4). Выбора нет:
  бэкенд принимает ровно шесть, а два разных PIN на один вход человек не
  различит. Если ТЗ важнее, менять придётся на стороне `jack5505/mahalla`.
- **`ENTER_PIN` на новом устройстве может упереться в `DEVICE_UNKNOWN`**:
  `pin-login` ищет пользователя по устройству. Тогда виден текст сервера («Bu
  qurilma tanilmadi. SMS bilan kiring») и кнопка «Забыли PIN?» — но сценарий
  на стенде не прогонялся.
- **Локальный лимит попыток в серверном режиме не работает** (его ведёт
  бэкенд). Блокировку (`lockedSecondsRemaining`) экран показывает только
  текстом сервера, таймера нет.
- **`auth/session/check` и `auth/pin-resume` по-прежнему не используются** —
  app-lock при возврате в приложение это отдельная задача.
- Остальной API под конверт бэкенда так и не переведён: `CatalogApi` (эпик 4)
  ждёт «голый» JSON, а `/api/v1/places` без координат отвечает 403
  `GEO_PERMISSION_REQUIRED` — главная после входа пустая. Отдельная задача.

## Этап: координаты в каждом запросе и реальные эндпоинты каталога (issue #53, ветка claude/issue-53-*)

Главная после входа отвечала `403 GEO_PERMISSION_REQUIRED` («Joylashuv
ruxsatini yoqing») при выданном системном разрешении — это тот самый
незакрытый риск из раздела issue #51 выше. Причин оказалось две, обе
клиентские.

**Как снят контракт** (дизайн-репо агенту по-прежнему недоступно):
`https://189-74-96-232.nip.io/v3/api-docs` + прямые curl'ы по стенду.

**1. Бэкенд ждёт координаты в заголовках, а не в теле и не в query.**
Проверено перебором:

| запрос | ответ |
|---|---|
| `GET /places/nearby?lat=41.3111&lng=69.2797` | `403 GEO_PERMISSION_REQUIRED` |
| то же + только `X-Geo-Lat` | `403 GEO_PERMISSION_REQUIRED` |
| то же + `X-Geo-Lat: abc`, `X-Geo-Lng: def` | `403 GEO_INVALID_COORDINATES` |
| то же + `X-Geo-Lat: 41.3111`, `X-Geo-Lng: 69.2797` | `200`, **даже без токена** |

Фильтр стоит до маршрутизации и накрывает весь `/api/v1` (кроме `auth/**`,
где координаты идут телом, — но и с заголовками `send-otp` отвечает 200).
Отсюда `data/network/GeoHeaderInterceptor`: координаты из
`RequestLocationProvider` (та же цепочка, что у авторизации: позиция →
центр выбранного города → Ташкент), кэш на 60 секунд (интерцептор работает на
потоке OkHttp, опрашивать `LocationManager` на каждый запрос прокрутки
незачем), формат `%.6f` в `Locale.ROOT` — иначе русская локаль дала бы
`41,3111`, а `Double.toString()` — `1.0E-5`, и оба ответа были бы
`GEO_INVALID_COORDINATES`. Висит на **обоих** клиентах, между
`BackendUrlInterceptor` и `AuthInterceptor`: инспектор трафика (issue #30)
обязан видеть запрос ровно таким, каким тот уходит. Отказ хранилища координат
запрос не роняет — уходит без заголовков и получает понятный 403.

**2. Эндпоинта `GET /api/v1/places` у бэкенда нет вовсе.** У этого пути
объявлен только `POST` (создание заведения). Выдача разложена по трём
контроллерам, ответы — в общем конверте `{success, data, error}`:

| | было в приложении | стало |
|---|---|---|
| выдача | `GET places?q&openNow&maxDistance&minRating&sort&page&size` | `GET places/nearby?lat&lng&radiusMeters&category` |
| поиск | тот же `places` с `q` | `GET search?query&category` |
| карточка | `GET places/{id}` (голый JSON) | он же, но в конверте |
| отзывы | `GET places/{id}/reviews` | `GET reviews/places/{placeId}?page&size` |
| категории | `food`, `playground`, `master` | `FOOD`, `GAMING`, `BARBER` (enum бэкенда) |

Что из этого следует для кода:

- **Пагинации нет**: оба списочных эндпоинта отдают всё найденное одним
  массивом. `PlacePage.hasMore` теперь всегда `false`, страницы старше нулевой
  в сеть не ходят вовсе (иначе та же первая дописалась бы в список второй раз).
- **Фильтры сервера скромнее**: из прежнего набора он понимает только
  категорию и радиус (`maxDistanceMeters` → `radiusMeters`). `openNow`,
  `minRating` и `sort` серверу больше не отправляются — сортировку и остаток
  фильтров по-прежнему делает `PlaceFilterEngine`, и офлайн с онлайном не
  разъезжаются.
- **Расстояние в поиске считается локально** (`GeoDistance`, гаверсинус):
  `PlaceDocument` из поискового индекса координаты отдаёт, а `distanceMeters`
  — нет, и вся выдача показывала бы «0 м».
- **Категории**: `PlaceCategory.apiValue` — значение перечисления бэкенда,
  прежние написания приняты как алиасы (`playground`, `master`, `food`), иначе
  кэш Room от старой версии превратился бы в `Other`. `FREELANCER` тоже
  «мастер»; `BAKERY`, `SHOP`, `MUSEUM`, `PARK`, `MOSQUE`, `FASHION` — `Other`.
- **Полей стало меньше, и это видно**: расписания (`openingHours`) в контракте
  нет ни в каком виде — блок часов работы на карточке пуст, `OpeningHoursDto`
  удалён (`OpeningHoursCalculator` остался, он про домен). `hasQueue` /
  `hasBooking` / `hasOrdering` бэкенд тоже не отдаёт — вертикали определяются
  категорией и своими контроллерами (`food/…/menu`, `gaming/…/zones`).
  `isRecommended` нет: блок «рекомендуем» на главной собирается по рейтингу
  (`HomeSections` это уже умел). «Открыто сейчас» — единственное поле
  `isAvailable`.
- Дата отзыва разбирается и как `Instant` (`…Z`), и как `LocalDateTime` без
  зоны: Jackson на бэкенде отдаёт второй вариант, и иначе дата пуста у всех.

Проверено: `./gradlew testDebugUnitTest` — **660 тестов в 76 классах, 0
падений, 0 ошибок**; `lintDebug` — `No issues found`; `assembleDebug` и
`assembleRelease` — BUILD SUCCESSFUL. Новые тесты: `GeoHeaderInterceptorTest`
(формат в русской локали, кэш по TTL, отказ источника, явный заголовок не
перетирается), `GeoDistanceTest`, два случая в `NetworkClientsTest` (место в
цепочке на обоих клиентах), переписанные `CatalogRepositoryTest`
(`nearby` против `search`, координаты и радиус в запросе, `category=GAMING`,
конверт с `success:false`) и `PlaceMappersTest`, новые случаи в
`PlaceCategoryTest`.

**Не сделано / риски:**

- **На устройстве не проверено** (эмулятора в CI нет): главная, поиск и
  карточка на живых данных. Каталог стенда сейчас **пуст** — `nearby`,
  `search` и `map-bounds` отвечают `200` с `data: []` на любой радиус, вплоть
  до прямоугольника на весь Узбекистан. Значит «пустая главная» после этой
  правки — ожидаемый ответ сервера, а не ошибка клиента; проверять надо после
  наполнения каталога.
- **Фильтры «открыто сейчас», «рейтинг» и сортировка теперь целиком
  клиентские** — то есть работают по той странице, что приехала. Пока сервер
  отдаёт всё одним списком, разницы нет; появится пагинация — фильтры придётся
  возвращать в запрос.
- **Радиус по умолчанию 10 км** (`CatalogApi.DEFAULT_RADIUS_METERS`) против
  серверных 3 км: в райцентре три километра дают пустой экран при живом
  каталоге через дорогу. Число подобрано на глаз — уточнить у продукта.
- **Часов работы на карточке нет** (бэкенд их не отдаёт) — вопрос к
  `jack5505/mahalla`: без расписания фильтр «открыто сейчас» опирается на один
  переключатель `isAvailable`.
- **Имена полей отзыва угаданы наполовину**: в схеме стенда `Response`
  перекрыт коллизией springdoc (несколько классов с одним простым именем),
  поэтому автор и текст принимаются под двумя именами каждый
  (`userName`/`author`, `text`/`comment`). Свериться, когда схема починится.
- `GeoHeaderInterceptor` шлёт координаты и на `auth/**`, где они дублируют
  поля тела. Бэкенд не возражает (проверено), но если появится расхождение
  «заголовок против тела», решать его придётся на сервере.

## Этап: экран кода говорит, куда код ушёл (issue #54, ветка claude/issue-54-*)

Человек вводил номер, читал «Код отправлен на +998937555505» и ждал SMS,
которого не будет: бэкенд отправил код **в Telegram-бот**. Понять это было
неоткуда — экран называл номер и молчал про канал.

**Что подтвердил стенд.** `POST auth/send-otp` возвращает поле `channel`
(`enum [SMS, TELEGRAM]` в схеме, приложение объявило его ещё в issue #42 и не
использовало). Один и тот же запрос отвечает по-разному:

| номер | ответ |
|---|---|
| незнакомый (`+998901112233`) | `"channel":"SMS"` |
| связанный с ботом (`+998937555505`, номер со скриншота issue) | `"channel":"TELEGRAM"` |

То есть **обе стороны отработали верно**, дыра была ровно в тексте экрана.
Канал выбирает сервер: у кого номер уже привязан к боту, тот получает код
бесплатным сообщением, остальные — платным SMS. Приложение канал не
запрашивает и не переопределяет.

- **Домен**: `OtpDeliveryChannel` (`Sms`/`Telegram`) в `OtpChallenge.kt` +
  поле `OtpChallenge.channel`. Незнакомое значение и молчание сервера — `Sms`:
  обещать Telegram там, где его может не быть, хуже, чем не обещать ничего
  (это и есть поведение до issue #54).
- **Маршрут**: `OtpRoute.channel: String` — имя константы, а не само
  перечисление. Типизированные маршруты Navigation кладут аргументы в `Bundle`,
  и для enum понадобился бы собственный `NavType`; читает его
  `OtpDeliveryChannel.byName`, битое значение опять же `Sms`.
- **Экран**: подпись под заголовком меняется целиком («Код отправлен не по SMS,
  а в Telegram-аккаунт с номером …»), плюс выделенный фоном блок
  `OnboardingNotice` (новый, тон `MahallaTone.Info`) с объяснением и кнопкой
  **«Открыть Telegram»**. Блок именно выделенный: экран кода открывают, чтобы
  найти поле ввода, и обычный абзац рядом с ним пропускают.
- **Кнопка ведёт в само приложение Telegram** (launch-intent по пакету из
  `TelegramAvailability`), а не в чат с ботом: `send-otp` ссылки на бота не
  отдаёт (в ответе только `channel`), а угадывать имя бота на клиенте нельзя —
  ровно то, от чего защищает `TelegramBotLink` в issue #46. Сообщение бота в
  списке чатов будет последним.
- **Telegram не установлен — текст другой**: кнопки нет (открывать нечего), а
  блок объясняет, что код лежит в Telegram-аккаунте с этим номером и SMS не
  придёт. Молчание здесь оставило бы человека ждать до истечения кода.
- **Повторная отправка обновляет канал**: сервер выбирает его на каждый запрос
  заново, и второй код может уйти уже не туда, куда первый.

Проверено: `./gradlew testDebugUnitTest` — **672 теста в 77 классах, 0
падений**; `lintDebug` — `No issues found`; `assembleDebug` и `assembleRelease`
— BUILD SUCCESSFUL. Новые тесты: `OtpDeliveryChannelTest` (разбор значения
сервера, регистр и пробелы, незнакомый канал, аргумент маршрута), пять случаев
в `OtpViewModelTest` (канал из маршрута, отсутствие Telegram, эффект открытия,
смена канала на повторе), два в `AuthRepositoryTest` на MockWebServer, правки
в `RoutesSerializationTest`.

**Не сделано / риски:**

- **На устройстве не проверено** (эмулятора в CI нет): открытие Telegram по
  кнопке и сам текст на узком экране. Проверять на номере, привязанном к боту,
  — на любом другом канал будет `SMS` и блок не появится.
- **Ссылки прямо на чат с ботом нет**: открывается список чатов. Чтобы вести в
  чат, бэкенду надо отдавать `telegramBotUrl` (или имя бота) и в ответе
  `send-otp` — задача для `jack5505/mahalla`, не блокер.
- **Экран телефона про канал по-прежнему молчит**: узнать, что код уйдёт в
  Telegram, можно только после отправки. Заранее это знает сервер — отдельного
  эндпоинта «какой канал у этого номера» в схеме нет.
- Замечание с issue #49 в силе: для нового пользователя Telegram-вход всё равно
  добивается кодом. Зато теперь видно, что и этот код бесплатный — приходит от
  бота.

## Этап: профиль — кто вошёл, «Выйти», мои устройства (issue #61, задача T2)

Из приложения нельзя было выйти: `AuthRepository.logout()` был реализован, но
из UI не вызывался нигде — единственным вызовом во всём `src/main` был
автоматический сброс после исчерпания попыток PIN. Сам профиль показывал язык,
тему, адрес сервера и Chucker — ни имени, ни номера, ни аватара.

**Контракт снят со стенда** (`https://189-74-96-232.nip.io/v3/api-docs` +
прямые curl'ы; дизайн-репо агенту по-прежнему недоступно):

| эндпоинт | контракт | без токена |
|---|---|---|
| `GET auth/sessions` | query `deviceId` (обяз.), `platform` (обяз.), `osVersion` → `data: [ActiveSessionResponse]` | `401 UNAUTHORIZED` |
| `POST auth/sessions/revoke` | тело `{sessionId, revokeAll}` | `401` |
| `POST auth/sessions/{sessionId}/trust` | query `trusted` | `401` |

`ActiveSessionResponse`: `sessionId, deviceName, platform, appVersion,
status (PENDING_PIN_SETUP|PIN_REQUIRED|ACTIVE|LOCKED|REVOKED), lastActivityAt,
lastIp, trustedDevice, currentDevice`. Все три требуют Bearer — значит
`SessionsApi` собирается на **основном** Retrofit, а не на «голом»
`@RefreshClient`, как остальная авторизация.

- **Профиль хранится локально**: `GET /users/me` у бэкенда нет вовсе, имя,
  номер и аватар приезжают **только** в ответе на вход. Поэтому появился
  `data/prefs/UserProfileStore` (`UserProfile` + ключи `profile_*` в том же
  DataStore), а `DefaultAuthRepository` пишет туда `user` из `verify-otp`,
  `setup-pin`, `pin-login` и `telegram/check` и **стирает при выходе** — имя
  прошлого пользователя в шапке после выхода читалось бы как «вы всё ещё
  здесь». Ответ без блока `user` профиль не затирает: это не «пользователь
  стал безымянным», а «эндпоинт про другое». Редактирования профиля нет —
  эндпоинта под него у бэкенда тоже нет.
- **Шапка**: аватар, имя, номер. Аватар — круг с инициалами (`UserProfile.initials()`,
  регистр по `Locale.ROOT`: на турецкой локали `i` уезжает в `İ`), потому что
  загрузчика изображений в проекте всё ещё нет; `avatarUrl` уже сохраняется и
  подставится без изменений экрана. Имени нет — иконка человека, а не пустой
  круг.
- **«Выйти»** — `MahallaButtonVariant.Destructive` внизу экрана, с
  подтверждением (кнопка стоит рядом с языком и темой, случайное нажатие
  стоило бы повторного входа). После `logout()` сбрасывается **и флаг
  онбординга** (`setOnboardingCompleted(false)`): иначе следующий запуск
  привёл бы прямо в main без сессии, где каждый запрос отвечает 401.
  Навигация — `navigate(WelcomeRoute) { popUpTo(MainGraph) { inclusive = true } }`;
  именно на welcome, а не в `OnboardingGraph`, потому что граф онбординга мог
  стартовать с `PinRoute` (прерванный вход), а после выхода проверять нечего.
- **«Мои устройства»**: список с именем, статусом, временем последней
  активности и IP; своё устройство помечено бейджем и **не отзывается** —
  отзыв своей сессии был бы выходом, о котором экран не сказал ни слова (для
  него есть кнопка «Выйти»). Текущее определяется по флагу `currentDevice`
  **или** по совпадению `sessionId` с сохранённой сессией: ошибиться здесь
  значит предложить человеку отозвать вход, на котором он сейчас работает.
  Переключатель «доверенное устройство» — `POST …/trust`. Пока идёт запрос по
  одной строке, остальные заблокированы: ответы приезжали бы на список,
  которого уже нет. Отказ показывается текстом сервера (issue #34), а список
  после успеха перечитывается у сервера, а не правится на клиенте.
- **Разбор мягкий**, как в каталоге: запись без `sessionId` отбрасывается (её
  нечем отозвать), `REVOKED` в списке «мои устройства» не показывается, а
  незнакомый статус (`Unknown`) показывается — новые состояния бэкенда не
  должны прятать устройство. Дата разбирается общим
  `core/format/parseServerInstant` (вынесен из `PlaceMappers`): Jackson отдаёт
  `LocalDateTime` без зоны, и иначе время пусто у всех.
- **`ApiResponse.ensureSuccess()`**: у `revoke` и `trust` конверт без
  полезной нагрузки (`ApiResponseVoid`), `data` там `null` и при успехе —
  `payload()` превратил бы штатный ответ в ошибку.
- **`RevokeSessionRequest.revokeAll` объявлен без значения по умолчанию**
  намеренно: kotlinx.serialization выбрасывает из тела поля, равные дефолту, и
  бэкенд получал бы запрос без флага.
- **Состояния устройств разложены руками, а не `ScreenStateHost`**: экран
  прокручивается целиком, а `ApiErrorState` несёт собственную прокрутку —
  вложенная в родительскую, она измерялась бы бесконечной высотой.

Проверено: `./gradlew testDebugUnitTest` — **843 теста в 90 классах, 0
падений**; `lintDebug` — `No issues found`; `assembleDebug` и `assembleRelease`
— BUILD SUCCESSFUL. Новые тесты: `SessionsRepositoryTest` (MockWebServer:
query-параметры, конверт, дата без зоны, отбрасывание записей, тело `revoke`,
`trusted` в query, 2xx с `success:false`, 401), `UserProfileTest` (инициалы,
турецкая локаль), переписанный `ProfileViewModelTest` (подтверждение выхода,
сброс флага онбординга, эффект, отзыв чужой сессии и запрет своей, отказ
сервера, доверие, перечит на возврате), три новых случая в
`AuthRepositoryTest` (профиль сохраняется на обоих путях входа и стирается при
выходе); фейки `FakeUserProfileStore` и `FakeSessionsRepository`.

**Заодно (иначе `lintDebug` красный):** на `main` висели две ошибки
`PluralsCandidate` (`checkout_error_time_too_soon`, `order_eta` — приехали с
вертикалью «Еда» уже после уборки issue #39; проверено на чистом дереве).
Обе строки переведены в `<plurals>` с русскими формами, вызовы — на
`pluralStringResource`. Неиспользуемый `profile_subtitle` удалён из обеих
локалей: новый экран профиля подписи под заголовком не показывает.

**Не сделано / риски:**

- **На устройстве не проверено** (эмулятора в CI нет): список устройств на
  живом аккаунте, отзыв чужой сессии, переключатель доверия и сам выход.
  Успешный путь `auth/sessions` без токена не проверить — на стенде нужен
  вошедший пользователь, а SMS-кода в CI нет.
- **Смена PIN (`PUT pin/change`) и переключатель биометрии (`PUT pin/biometric`)
  из T2 не сделаны**: оба требуют ввода текущего PIN, то есть отдельного
  экрана ввода и решения, что делать с локальным Keystore-хэшем (issue #51).
  Это самостоятельная задача, а не строка в профиле.
- **`trustedDevice` ни на что не влияет в приложении**: что именно бэкенд даёт
  доверенному устройству (пропуск PIN-шага?), из схемы не следует — вопрос к
  `jack5505/mahalla`.
- **Профиль переживает переустановку только вместе с DataStore**, а он
  исключён из бэкапа (там же токены). После восстановления на новом устройстве
  шапка будет пустой до первого входа — это правильно, но выглядит как потеря
  данных.
- «Выйти на всех устройствах» (`revokeAll: true`, `allDevices` у `logout`) не
  сделано намеренно: это отдельное решение пользователя, а не побочный эффект
  отзыва одного устройства.

## Этап: кошелёк — настоящий баланс и история (issue #62, задача T3)

`WalletScreen` показывал зашитый `DEMO_BALANCE_SUM = 1_284_500` — одно и то же
число всем, то есть экран врал про деньги. Теперь это 8.1 эпика #12: баланс и
история операций с бэкенда.

**Контракт снят со стенда** (`https://189-74-96-232.nip.io/v3/api-docs` +
curl'ы; дизайн-репо агенту по-прежнему недоступно):

| эндпоинт | контракт | без токена |
|---|---|---|
| `GET wallet` | → `WalletResponse` (`balance`, `bonusBalance`, `heldAmount`, `availableBalance`, `currency`, `status`, `balanceSom`, `bonusBalanceSom`) | `401 UNAUTHORIZED` |
| `GET wallet/transactions` | query `page`, `size` → `PageResponseTransactionResponse` (`content`, `page`, `totalPages`, `last`) | `401` |

Обе требуют Bearer — значит `WalletApi` собирается на **основном** Retrofit, а
не на «голом» `@RefreshClient`, как авторизация.

- **Единица денег выводится из ответа, а не зашита.** Бэкенд отдаёт каждую
  сумму дважды: целым числом (`balance`, `amount`) и дробным «в сумах»
  (`balanceSom`, `amountSom`). Что за единица у целого поля, схема **не
  говорит**, а проверить живым ответом нельзя — `GET /wallet` требует входа,
  которого в CI нет. Поэтому `WalletAmounts.scaleOf` выбирает делитель (1 или
  100) по самой паре: сервер уже сказал, сколько это в сумах. Найденный
  делитель применяется ко всем суммам того же ответа, включая те, у которых
  дробного близнеца нет (`heldAmount`, `availableBalance`, `balanceAfter`) —
  иначе «на счету» и «доступно» разъехались бы в сто раз внутри одной
  карточки. Пары нет — считаем тийины: отдельное поле `*Som` существует ровно
  потому, что целое поле хранит что-то другое.
- **Домен** `feature/wallet/domain/Wallet.kt`: `Wallet`, `WalletStatus`,
  `WalletTransaction`, `TransactionDirection`, `TransactionStatus`,
  `WalletTransactionPage`. Написание значений перечислений бэкенд не
  фиксирует (в схеме это `string`), поэтому принимаются распространённые
  варианты (`IN`/`CREDIT`/`DEPOSIT`, `OUT`/`DEBIT`/`PAYMENT`, …), а
  незнакомое значение — `Unknown`, а не пугающий статус: новый статус бэкенда
  не должен рисовать плашку «заблокирован» всем подряд. Направление сильнее
  знака суммы (сервер шлёт списания и с минусом, и без него).
- **Данные** `feature/wallet/data/`: `WalletApi` + `WalletRepository`
  (интерфейс и `Default`). Кэша нет намеренно — устаревший баланс из Room хуже
  честной ошибки. Разбор мягкий: операция без `id` отбрасывается (в
  `LazyColumn` это дубликат ключа), `hasMore` считается по `last`, при его
  отсутствии — по `page`/`totalPages`, а полное молчание о страницах
  останавливает догрузку: лучше не показать хвост, чем крутить одну страницу
  в цикле.
- **Экран**: `WalletViewModel` + `WalletScreen` (карточка баланса, «можно
  потратить» крупно, заморозка и бонусы строками только когда они не нули,
  история страницами с догрузкой по концу списка, pull-to-refresh, перечит на
  `ON_RESUME`). Баланс и история — **два независимых `ScreenState`**: отказ
  истории не прячет баланс, который уже приехал, и наоборот; отказы
  показываются текстом сервера (issue #34). Состояния разложены руками, а не
  через `ScreenStateHost`: тот рисует `ApiErrorState` с собственной
  прокруткой, а внутри `LazyColumn` вложенная прокрутка меряется бесконечной
  высотой.
- **Выдуманная ручка «Еды» убрана**: `FoodApi.walletBalance()` (`wallet/balance`)
  у бэкенда нет вовсе, и `feature/food/data/WalletRepository` (заведённый под
  checkout эпика 5 с пометкой «переедет в кошелёк, когда он появится») удалён.
  `CheckoutViewModel` теперь спрашивает настоящий кошелёк и сравнивает сумму
  заказа с `availableSum`: заморозка под другую незавершённую операцию
  потратить себя не даст.

Проверено: `./gradlew testDebugUnitTest` — **867 тестов в 93 классах, 0
падений**; `lintDebug` — `No issues found`; `assembleDebug` и `assembleRelease`
— BUILD SUCCESSFUL. Новые тесты: `WalletAmountsTest` (выбор масштаба, знак,
округление, разбор перечислений), `WalletRepositoryTest` (MockWebServer:
пересчёт сумм, «доступно» без поля, отрицательный баланс, тело и query
запроса, дата без зоны, отбрасывание записей, `last`/`totalPages`, 2xx с
`success:false`, 401), `WalletViewModelTest` (независимость баланса и истории,
догрузка и дедупликация, провал догрузки, перечит на возврате,
pull-to-refresh); фейк `FakeWalletRepository` переехал из `FoodFixtures` в
свой файл.

**Не сделано / риски:**

- **На устройстве и на живом аккаунте не проверено** (эмулятора в CI нет, а
  успешный вход требует SMS-кода): реальные суммы, единица денег бэкенда и
  пагинация. **Масштаб сумм — первое, что надо проверить руками**: если
  бэкенд перестанет отдавать `balanceSom`/`amountSom`, приложение будет делить
  на 100 по предположению.
- **Пополнения нет**: `POST wallet/top-up` требует выбора провайдера
  (`PAYME|CLICK|UZUM`, минимум 100 000 в единицах бэкенда) и возврата из его
  веб-формы — это 8.2 эпика #12, отдельная задача. Кнопка «пополнить» из
  checkout'а по-прежнему просто открывает экран кошелька.
- **Бизнес-кошелёк** (`GET wallet/business`) не используется — он для
  бизнес-панели (эпик #16).
- **Тип операции показывается как есть**, если сервер не прислал
  `description`: `ORDER_PAYMENT` на экране выглядит машинно. Локализовать
  незнакомый набор значений нечем — нужен либо список типов от бэкенда, либо
  `description` во всех ответах.
- Расшифровки `referenceType` (заказ, бронь, подписка) в UI нет: перехода из
  истории к самой операции бэкенд не даёт — нет ни id сущности, ни ссылки.

## Этап: миграции Room вместо destructive-фоллбэка (issue #64, задача T5)

`Room.databaseBuilder(...).fallbackToDestructiveMigration()` при `version = 3`
означал, что каждое обновление приложения, поднимающее версию схемы, молча
пересоздаёт БД. Кэш каталога потерять не жалко — он и так протухает по TTL, — а
вместе с ним пропадал **черновик корзины**, то есть работа пользователя, и без
единого слова на экране.

- **Схемы экспортируются**: `exportSchema = true` + ksp-аргумент
  `room.schemaLocation` (`app/build.gradle.kts`), результат —
  `app/schemas/uz.mahalla.data.db.MahallaDatabase/3.json` в репозитории.
  Без него следующая миграция писалась бы по памяти, а сравнить две версии
  было бы нечем. Версия вынесена в константу `MahallaDatabase.VERSION`, чтобы
  тест сверялся с ней, а не с числом в своём коде.
- **`data/db/MahallaMigrations.kt`** — две миграции, восстановленные по истории
  сущностей (`git log` по `PlaceEntity`/`CartDraftItemEntity`):
  - **1→2** (эпик 4): девять `ALTER TABLE places ADD COLUMN` (счётчик отзывов,
    адрес, координаты, фото, флаг «рекомендуем», контакты). `DEFAULT` у
    ненулевых столбцов нужен только на время `ALTER TABLE`: у сущности
    `@ColumnInfo(defaultValue = …)` нет, а Room сверяет дефолты, лишь когда они
    объявлены с обеих сторон.
  - **2→3** (эпик 5): у `cart_draft_items` сменился первичный ключ
    (`placeId + productId` → `placeId + lineId`), а в SQLite это делается
    только пересозданием таблицы — строки переносятся `INSERT … SELECT`.
    **`lineId` старой строки равен её `productId`**: модификаторов до v3 не
    было, и `CartCalculator.lineId(itemId, emptySet())` возвращает ровно это —
    иначе перенесённая позиция двоилась бы при следующем добавлении того же
    блюда. `placeName`/`deliverySum` заполняются пустыми: взять их неоткуда, а
    корзина обновляет их после загрузки меню.
- **`DatabaseModule`**: `addMigrations(*MahallaMigrations.ALL)`; destructive
  остался **только на понижение версии**
  (`fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)`) — старая
  сборка поверх новой физически не знает схемы, которая уже лежит в файле, и
  пересоздание тут единственный выход, который не превращает откат в кирпич.
  На повышении версии без миграции приложение теперь **падает** — это
  осознанно: молчаливая потеря корзины хуже заметного падения в CI.
- **Тесты** `MahallaMigrationsTest` (Robolectric, 5 тестов): БД прежней версии
  собирается сырым SQL — теми же `CREATE TABLE`, что выдавала Room, — и
  открывается **через production-конфигурацию** `DatabaseModule.provideDatabase`,
  а не через builder, собранный в тесте: иначе тест остался бы зелёным, даже
  если миграции не подключены к приложению. Проверяется путь 1→3 и 2→3
  (корзина, кэш мест и заказов доезжают, новые столбцы пусты, а не потеряны),
  что перенесённая строка складывается с новым добавлением того же блюда,
  пересоздание при понижении версии и сплошная цепочка миграций до
  `MahallaDatabase.VERSION`. Схему после миграций сверяет сама Room: у
  подсунутого файла нет `room_master_table`, поэтому на открытии она валидирует
  фактические таблицы против ожидаемых и падает, если миграция что-то забыла.

Проверено: `./gradlew testDebugUnitTest` — **872 теста в 94 классах, 0
падений**; `lintDebug` — `No issues found`; `assembleDebug` и `assembleRelease`
— BUILD SUCCESSFUL.

**Не сделано / риски:**

- **Схемы 1.json и 2.json не экспортированы** — их неоткуда взять, кроме как
  собрать те коммиты заново. Поэтому `MigrationTestHelper` (он читает схемы из
  ассетов) не используется, а миграции проверяются сырым SQL. Со следующей
  версии схемы появятся сами.
- **Room в рантайме резолвится в 2.7.0** (подтягивает Chucker), хотя в каталоге
  объявлена 2.6.1 и компилятор Room тоже 2.6.1. Работает, но при обновлении
  стека это первое место, где разъедется поведение миграций.
- **На устройстве не проверено** (эмулятора в CI нет): реальное обновление
  установленной сборки поверх прежней. Тесты закрывают ту же цепочку на
  Robolectric, но живая установка с наполненной корзиной — отдельная проверка.
- **Правило на будущее**: новая `version` — новая `Migration` в
  `MahallaMigrations.ALL` и новый случай в тесте. Пропуск теперь не тихая
  потеря данных, а падение при открытии БД.

## Этап: карта работает — полотно подключено к экрану (issue #65, задача T6)

`MapScreen` рисовал `MapCanvasPlaceholder` (текст «SDK ещё не выбран» + список
маркеров), хотя полотно `MapCanvas` на Yandex MapKit написано ещё в эпике 4.2 и
покрыто тестами. Блокер 4.2 закрыт решением от 2026-08-26, и заглушке остаться
было не от чего — единственным шагом оставалось соединить два готовых слоя.

- **Экран**: `MapCanvas(initializer, markers, camera, showUserLocation,
  onMarkerClick, onCameraChanged)` на весь экран, поверх него — только то, чего
  SDK не даёт: плашка состояния выдачи, кнопки масштаба и «моё
  местоположение», карточка выбранного места. Список маркеров и `ClusterList`
  удалены: это была имитация карты.
- **Состояния выдачи разложены руками, а не `ScreenStateHost`**: тот заменяет
  содержимое целиком, а карта обязана остаться на экране и при отказе каталога
  — тайлы к бэкенду Mahalla отношения не имеют. Загрузка, пустой ответ и ошибка
  (текстом сервера, issue #34) показываются узкой плашкой сверху. Раскрываемых
  подробностей ответа на карте нет — под них нет места; они остались на
  главной и в поиске.
- **`MapState` переехал на модели полотна**: `markers: List<MapMarkerUi>` и
  `camera: MapCameraPosition` (зум `Float`, как у MapKit) вместо `clusters` и
  целого `zoom`. От SDK модель по-прежнему не зависит — слой `canvas` изолирует
  типы Yandex, поэтому вся ViewModel проверяется обычным JVM-тестом.
- **Сеточный `MarkerClusterer` эпика 4 удалён** вместе с тестом (это и был
  открытый вопрос «выкинуть или оставить»): MapKit кластеризует сам
  (`ClusterizedPlacemarkCollection`) и **пересобирает кучи на каждом зуме**,
  чего сеточный вариант не умел — он считал кластеры для одного заранее
  известного зума. Других потребителей у него не было (проверено по коду), а
  два разных набора кластеров на одном экране разъезжались бы.
- **Камера**: на загрузке — `MapCameraFit.fit(маркеры, fallback = текущая)`;
  пустая выдача оставляет камеру там, где она стоит (уносить экран в дефолтный
  город на каждом обновлении — потеря того, что человек только что нашёл).
  Жест пользователя приезжает событием `CameraMoved` и становится состоянием;
  своё же движение полотно наверх не отдаёт (`MapCanvasController`).
- **Выбор маркера камеру не двигает**: пользователь ткнул в то, что видит, и
  самопроизвольный полёт под пальцем читается как промах. Тап по маркеру из
  прошлой выдачи (полотно могло отдать его, пока приезжал новый список)
  игнорируется. Выбранное место показывается карточкой `PlaceCard` внизу — по
  тапу открывается карточка заведения, как из выдачи.
- **«Моё местоположение»**: разрешение спрашивает экран
  (`RequestMultiplePermissions`, coarse+fine, достаточно любого), уже выданное
  перечитывается на `ON_RESUME` — его могли дать в онбординге (3.6) или в
  настройках устройства. Дальше координаты берёт `UserLocationProvider`
  (MapKit), камера уходит на `MapCameraFit.focusOn`, слой «моё местоположение»
  включается. **Отказ объясняется словами**: нет разрешения и не удалось
  определиться — два разных текста в плашке, потому что тап без последствий
  читается как сломанная кнопка. Пока идёт запрос, кнопка занята — второй тап
  не заводит второй запрос.
- Строки `map_sdk_pending`, `map_markers_count`, `map_cluster_places` удалены
  (обеих локалей) — вместе с ними ушло и замечание ревью PR #23 «просят
  `<plurals>`». Добавлены `map_empty_places`, `map_location_denied`,
  `map_location_unavailable`.

Проверено: `./gradlew testDebugUnitTest` — **868 тестов в 93 классах, 0
падений**; `lintDebug` — `No issues found`; `assembleDebug` и `assembleRelease`
— BUILD SUCCESSFUL. Тесты `MapViewModelTest` переписаны под новое состояние
(21 случай: подгонка камеры, пустая выдача не сбрасывает камеру, выбор маркера
и его снятие, неизвестный маркер, клампинг зума, жест пользователя, все четыре
исхода «моего местоположения», защита от второго запроса); фейки —
`FakeUserLocationProvider` и `fakeMapKitInitializer()`.

**Не сделано / риски:**

- **Ключ `MAPKIT_API_KEY` — действие пользователя.** Без него сборка не
  ломается, но на месте карты будет объяснение (`MapEngineState.MissingApiKey`):
  нужно получить ключ в кабинете Yandex MapKit, положить секрет
  `MAPKIT_API_KEY` в Settings → Secrets and variables → Actions и пробросить
  его в env шагов сборки в `ci.yml`/`claude*.yml` (workflow'ы правит
  пользователь — у GitHub App агента нет прав на `.github/workflows`).
  Локально — `local.properties`: `mapkit.apiKey=…`.
- **На устройстве ничего из этого не проверено** (эмулятора и ключа в CI нет).
  В первую очередь смотреть: тайлы и лицензионную плашку Yandex, кластеры и
  тап по ним, слой «моё местоположение», отписку слушателей при уходе с экрана
  и то, что карта не тянет трафик в фоне (`onStop`).
- **Каталог стенда пуст** (см. issue #53): маркеров на живом бэкенде сейчас не
  будет — карта покажет плашку «здесь пока нет мест». Это ответ сервера, а не
  поломка экрана.
- **Своей отрисовки «пустого» состояния движка карта не даёт**: отозванный или
  неверный ключ MapKit виден только как пустые тайлы — `MapKitInitializer` про
  это знает и честно об этом пишет в KDoc. Подписка на ошибки слоя —
  отдельная задача.
- Скриншот-тестов по-прежнему нет: плашки, кнопки и карточка проверялись
  глазами по `@ThemeLanguagePreviews` (`MapOverlayPreview`), само полотно в
  превью не поднимается.

## Этап: отчёты о падениях — Sentry (issue #74, задача T8)

Ни Crashlytics, ни Sentry в проекте не было: `grep -i 'crashlytics\|sentry\|firebase'`
по каталогу версий и `app/build.gradle.kts` не находил ничего. Падение у
пользователя было невидимо полностью — эмулятора в CI нет, на устройстве не
проверялся ни один экран, а единственным каналом обратной связи оставался
скриншот в issue (так чинились #49 и #54).

**Спецификация T8 живёт в `docs/TASKS-BACKLOG.md` на невлитой ветке
`claude/issue-59-20260830-0152`** — в `main` этого файла нет, и ссылка из issue
битая. Текст снят через `gh api` с той ветки.

### Выбор: Sentry, не Crashlytics

T8 начинается решением продукта, и оно принято так:

- **Crashlytics доставляет отчёты через Google Play Services**, а устройства без
  сервисов Google в Узбекистане обычны — это уже записанная причина, по которой
  в эпике 4.2 выбран Yandex MapKit вместо Google Maps. Отчётов не было бы ровно
  с тех прошивок, где падений больше всего.
- **Crashlytics требует `google-services.json` в репозитории** плюс два
  gradle-плагина; T8 прямо просит держать ключ в секрете Actions. Sentry — это
  одна строка DSN, читается как `MAPKIT_API_KEY`.
- Sentry можно поднять у себя, как поднят бэкенд-стенд.

Взят `io.sentry:sentry-android-core:7.14.0`, **без** `-ndk`: нативных
библиотек он не тянет (в APK от Sentry приезжает 36-байтный
`native-image.properties` и ничего больше), а размер сборки — отдельная
задача T13. Плагин Sentry для Gradle не подключён: он ходит в сеть за
загрузкой mapping-файлов по токену, а release сейчас всё равно собирается с
`isMinifyEnabled = false`, то есть стеки читаемы и без него.

### Как включается

| | debug | release |
|---|---|---|
| `SENTRY_DSN` (env или `local.properties`, `sentry.dsn=…`) | пусто → сбора нет | секрет Actions |
| `CRASH_REPORTING_ENABLED` | `false`, включает `SENTRY_ENABLED_IN_DEBUG=true` | `true` |

Оба условия обязательны (`CrashReportingConfig.isEnabled`). Пустой DSN — не
ошибка сборки: приложение работает без него, иначе один незаполненный секрет
ронял бы сборку всем. Заведомо непохожий на DSN мусор тоже выключает сбор:
`Sentry.init` бросает на нём `IllegalArgumentException`, и опечатка в секрете
уронила бы старт приложения у всех.

**Автозапуск Sentry выключен** (`io.sentry.auto-init=false` в манифесте):
SDK поднимается из `MahallaApplication.onCreate` сразу после `super.onCreate()`
(там Hilt внедряет поля Application). Иначе решение «слать или нет» принимал бы
`ContentProvider` по метаданным манифеста — мимо `CrashReportingConfig` и мимо
вычистки секретов.

### Секреты в отчёт не попадают

Защита двухслойная, потому что одного слоя тут мало.

1. **В отчёт не кладут лишнего.** `CrashReporter` не принимает произвольных
   данных: у `recordNonFatal` есть только исключение и машинное имя операции
   (`pin.save`, `mapkit.initialize`). Пользователя приложение не сообщает
   вовсе — ни `setUser`, ни `sendDefaultPii`. Скриншот и дерево вью выключены
   явно (`isAttachScreenshot`/`isAttachViewHierarchy = false`): экраны PIN,
   кода из SMS и кошелька уехали бы в панель картинкой.
2. **Всё, что собрал SDK, проходит через `CrashScrubber`** в
   `beforeSend`/`beforeBreadcrumb` — включая то, что положили его собственные
   интеграции. Вырезаются: заголовки `Authorization`, `Proxy-Authorization`,
   `Cookie`, `Set-Cookie`, `X-Session-Id` (тот же список, что редактируется в
   logcat и в Chucker, плюс `X-Session-Id` — по нему завершают чужую сессию,
   issue #61); секретные query-параметры в URL (`otpToken`, `deepLinkToken`, …)
   — остальные остаются, иначе непонятно, какой запрос упал; `Bearer …`, голые
   JWT и `token=…`/`"pin":"…"` в текстах сообщений и исключений; секретные по
   имени поля в extras, тегах и «хлебных крошках»; IP-адрес пользователя.
   **Тело запроса и ответа не отправляется вовсе, даже вычищенным**: по issue
   #34 бэкенд кладёт в ответ произвольный текст, и что там окажется завтра,
   клиент знать не может.

Порядок правил в `SecretScrubber.scrubText` не косметика: сначала режется
значение по имени поля, потом добираются `Bearer` и JWT. Обратный порядок
оставлял бы `token=[REDACTED]]` — уже вырезанное снова попадало бы под правило.

### Non-fatal там, где ошибка глотается

`Result.reportSwallowed("операция")` рядом с `runCatchingCancellable` —
точечно, а не внутри самой функции: тем же `runCatchingCancellable` закрыты и
штатные ситуации (сервер не ответил на `HEAD`, Telegram не установлен, тело
ошибки не разбирается как JSON, разрешения на геолокацию нет). Сообщать о них
значило бы утопить настоящие отказы в шуме.

Отмечены: Keystore (`pin.save`/`pin.verify`/`pin.clear`/`pin.configuredLength`,
`auth.logout`), записи в DataStore (`settings.setLanguage`, `settings.setCity`,
`settings.setBiometricEnabled`, `settings.markOnboardingCompleted`,
`settings.setOnboardingCompleted`, `backend.saveUrl`,
`backend.saveCertificatePin`, `device.readId`, `device.storeId`),
`mapkit.initialize` и `cart.replaceFromOrder`.

Зависимость берётся не из Hilt, а из процессного `CrashReporting` — это
единственное такое место в приложении и оно сознательное: сообщать нужно из
функции верхнего уровня, которой нечего внедрять, а тянуть `CrashReporter`
параметром через сорок вызовов значило бы переписать половину ViewModel'ей ради
одной строки в каждой. До `install` и во всех тестах там стоит
`NoopCrashReporter`, поэтому существующие тесты не менялись.

Проверено: `./gradlew testDebugUnitTest` — **898 тестов в 97 классах, 0
падений, 0 ошибок**; `lintDebug` — `No issues found`; `assembleDebug` и
`assembleRelease` — BUILD SUCCESSFUL. Новые тесты: `SecretScrubberTest`
(токены, JWT, query, заголовки, регистр, «текст без секретов не трогаем»),
`CrashScrubberTest` (на **настоящих** объектах Sentry — событие, исключение,
request, extras, теги, user, «хлебные крошки»), `CrashReportingConfigTest`
(пустой и битый DSN, сборка без флага), `CrashReportingTest` (отчёт с именем
операции, успех не сообщается, отмена не сообщается и не глотается, работа без
установленного репортера), новый случай в `PinViewModelTest` (отказ Keystore
доезжает до отчётов) и в `GraphAssemblyTest` (без DSN граф отдаёт заглушку);
фейк `FakeCrashReporter`.

**Не сделано / риски:**

- **Действие пользователя: секрет `SENTRY_DSN`.** Завести проект Android в
  Sentry, положить DSN в Settings → Secrets and variables → Actions и
  пробросить его в env шагов сборки в `ci.yml`/`claude*.yml` (workflow'ы правит
  пользователь — у GitHub App агента нет прав на `.github/workflows`).
  Локально — `local.properties`: `sentry.dsn=…`. **Пока секрета нет, сбор
  выключен и критерий «падение доезжает до панели» не проверен ничем, кроме
  тестов.**
- **На устройстве не проверено** (эмулятора в CI нет): что отчёт действительно
  доезжает, как выглядит в панели и что в нём после вычистки. Первое, что надо
  сделать с ключом, — уронить debug-сборку с `SENTRY_ENABLED_IN_DEBUG=true` и
  прочитать отчёт глазами.
- **Согласия пользователя на отправку отчётов нет** — сбор включается сборкой.
  Персональных данных в отчёт не уходит (ни id, ни IP, ни телефона), но экрана
  «отправлять отчёты о падениях» в приложении нет; нужен ли он — вопрос
  продукта и юриста, вместе с офертой (её ссылка тоже заглушка с эпика 3).
- **ProGuard-mapping в Sentry не загружается** (плагина нет), и пока release
  собирается без обфускации это неважно. Как только T13 включит
  `isMinifyEnabled`, стеки в панели станут нечитаемыми — плагин или ручная
  загрузка mapping'а придётся добавить той же задачей.
- **Полной гарантии «в отчёте нет секретов» не даёт никто**: сообщение
  исключения пишет тот, кто его бросил, и предугадать все формы нельзя.
  Поэтому первый слой — не класть лишнего, и только второй — регулярки.
  Появится новый секретный заголовок — его надо руками добавить в
  `SecretScrubber.SECRET_HEADERS`, как уже приходится делать в
  `ChuckerHttpInspector`.
- **Нативные падения не ловятся** (взят `-core`, а не `-ndk`): краш внутри
  `libmaps-mobile.so` останется невидимым. Это осознанный размен на размер
  APK (T13); когда карта поедет на устройствах, `-ndk` стоит вернуть отдельной
  задачей.
- ANR-детектор Sentry включён по умолчанию — на медленных устройствах он даст
  поток отчётов, которого раньше не было. Если панель зашумит, выключается
  `options.isAnrEnabled`.

## Этап: оставить отзыв (issue #76, задача T10)

Отзывы только читались (`GET reviews/places/{placeId}`, эпик 4), оставить свой
было нельзя — при том что рейтинг главный сигнал и в выдаче, и на карточке.

**Контракт снят со стенда** (`https://189-74-96-232.nip.io/v3/api-docs` +
прямые curl'ы; дизайн-репо агенту по-прежнему недоступно):

| эндпоинт | контракт | без токена |
|---|---|---|
| `POST reviews` | тело `{placeId, rating 1..5, text ≤2000, appointmentId?}` | `401 UNAUTHORIZED` |
| `DELETE reviews/{id}` | путь, тела нет | `401 UNAUTHORIZED` |
| `GET reviews/places/{placeId}` | query `page`, `size` → конверт + `PageResponse` | `200` (анонимно) |

**Тело `POST` живым запросом не проверить**: `401` приходит до валидации, а
токена в CI нет (нужен SMS-код). Имя схемы `CreateRequest` в `/v3/api-docs`
перекрыто коллизией springdoc — под ним склеены **пять** разных запросов
(отзыв, заведение, акция, товар аптеки, фрилансер), и уцелевший вариант
описывает как раз отзыв (`placeId` + `rating` + `text` + `appointmentId`;
у остальных поля были бы другие). Так же перекрыт и `Response`, поэтому
**ответ `POST` не разбирается вовсе** (`ApiResponse<JsonElement>` +
`ensureSuccess()`): опечатка в имени поля превратила бы удачную отправку в
ошибку, а рейтинг всё равно приходит перезапросом карточки.

- **Домен** `feature/place/domain/ReviewDraft.kt`: оценка (0 — «не выбрано»,
  вне `RATING_RANGE`), текст, `canSubmit`, `isTooLong` (2000 по `@Size` бэкенда),
  `textOrNull()`. Текст **необязателен** — одна оценка это уже отзыв; пробелы
  не считаются ни длиной, ни содержанием, и лишнее не режется на вводе (человек
  не поймёт, куда пропали символы), а блокирует кнопку с объяснением.
- **Данные**: `CatalogApi.createReview`/`deleteReview`, `CreateReviewRequest`
  (`text` уходит **отсутствующим полем**, а не `"text":null` — `explicitNulls =
  false`), `CatalogRepository.addReview`/`deleteReview`. Незаполненный черновик
  в сеть не уходит (`ApiError.Business(ReviewDraft.INVALID_CODE)`): 400 сказал
  бы то же самое, но платой были бы запрос и спиннер.
- **Своё против чужого**: `ReviewDto.userId` → `Review.authorId`, сравнение с
  `UserProfileStore.current().id` живёт в состоянии экрана
  (`PlaceDetailsState.myReview`/`isMine`). Отдельного флага «это ваш отзыв»
  бэкенд не отдаёт, `GET /users/me` у него нет вовсе — профиль лежит локально с
  ответа на вход (issue #61). Отзыв **без** `userId` своим не считается:
  показать чужому кнопку удаления хуже, чем не показать её владельцу.
- **Экран**: блок отзывов больше не исчезает на пустом списке (раньше оставить
  первый отзыв о месте было негде) — заголовок, кнопка «Оставить отзыв» и текст
  «отзывов пока нет». Форма — шторка кита: `MahallaRatingInput` (новый
  компонент, звёзды это `selectableGroup` с ролью `RadioButton` и подписью
  plurals — TalkBack иначе читает пять безымянных картинок), поле текста со
  счётчиком, кнопка с `ButtonState` и подписью, чего не хватает. Отказ сервера
  остаётся **в шторке** рядом с набранным текстом (issue #34): закрыть её значит
  потерять и объяснение, и работу человека.
- **Удаление своего** — иконка на карточке своего отзыва + подтверждение
  (`MahallaDialog`, destructive). Отказ показывается текстом сервера в блоке
  отзывов, а не молчанием.
- **Рейтинг после отправки не считается на клиенте**: карточка перезапрашивается
  «тихо» (`load(silent = true)`) — экран, который человек только что читал, не
  мигает скелетоном, а провал обновления не стирает уже показанные данные (отзыв
  ведь ушёл). Сложить рейтинг самим значило бы разойтись с выдачей на главной.
- **Второй отзыв не предлагается**, пока свой на карточке (`canAddReview`):
  бэкенд его отклонит, и правильнее показать свой с кнопкой удаления. На
  карточке из кэша формы нет — там нет ни свежего списка, ни подтверждения, что
  место существует.

Проверено: `./gradlew testDebugUnitTest` — **928 тестов в 98 классах, 0
падений, 0 ошибок**; `lintDebug` — `No issues found`; `assembleDebug` и
`assembleRelease` — BUILD SUCCESSFUL. Новые тесты: `ReviewDraftTest`,
восемь случаев в `CatalogRepositoryTest` (MockWebServer: тело и путь `POST`,
текст обрезан, пустой текст без поля, незаполненный черновик не уходит в сеть,
409 с текстом бэкенда, 2xx с `success:false`, `DELETE` по id, 403 на чужой
отзыв, `userId` доезжает до домена), пятнадцать в `PlaceDetailsViewModelTest`
(форма, отправка черновика, перезапрос карточки, отказ сохраняет текст, правка
стирает прошлый отказ, свой отзыв и отзыв без автора, запрет второго,
удаление с подтверждением и без, отказ на удалении, тихий перезапрос не
стирает карточку); `ratingStar` добавлен в `TouchTargetTest`.

**Не сделано / риски:**

- **На устройстве и на живом аккаунте не проверено** (эмулятора в CI нет,
  успешный вход требует SMS-кода): форма, отправка, удаление. Каталог стенда
  по-прежнему пуст (issue #53) — отзыв оставить негде, пока в нём нет мест.
  **Первое, что надо проверить руками: тело `POST reviews` принимается
  бэкендом** — имена полей взяты из перекрытой коллизией схемы.
- **Имя поля автора в ответе отзыва угадано**: `userId` с алиасами `authorId`
  и `createdBy`. Не совпадёт — свой отзыв не опознается, кнопки удаления не
  будет (кнопка не появится ошибочно у чужого: это выбрано намеренно).
  Свериться, когда `Response` в схеме перестанет быть коллизией.
- **Фото к отзыву (`POST media/upload`) не сделано**: в T10 оно помечено
  зависимостью от задачи про Coil, а загрузчика изображений в проекте
  по-прежнему нет — снимки некуда показывать.
- **Форма предлагается с карточки места всем**, а не после заказа или визита,
  как «логично» по T10: вертикали до отзывов не доведены, и `appointmentId`
  приложение не шлёт. Это прямо разрешено формулировкой задачи.
- **Ответ заведения** (`POST reviews/{id}/reply`) — бизнес-панель, в объём не
  входил.
- **Редактировать отзыв нельзя**: у бэкенда нет `PUT reviews/{id}`. Исправление
  — удалить и написать заново.
- Скриншот-тестов по-прежнему нет: шторка, счётчик и звёзды проверялись глазами
  по `@ThemeLanguagePreviews` (`RatingInputPreview`).

## Этап: экран обязательного обновления (issue #80, задача T12)

`app/version/check` бэкенд умел с самого начала, а приложение о нём не знало.
Цена: контракт API за месяц ломался четырежды (#42, #51, #53 и пути вертикали
«Еда»), и каждый раз старая сборка на устройстве переставала работать **молча** —
человек видел «Nimadir xato ketdi» и не понимал, что надо обновиться.

**Контракт снят со стенда** (`https://189-74-96-232.nip.io/v3/api-docs` +
прямые curl'ы; дизайн-репо агенту по-прежнему недоступно):

| эндпоинт | контракт | без токена |
|---|---|---|
| `POST app/version/check` | тело `{platform, currentVersionCode, currentVersionName?}` → `{updateAvailable, updateRequired, policy, latestVersionName, latestVersionCode, releaseNotes, storeUrl, remainingSkips, versionId}` | **`200`** |
| `POST app/version/skip` | тело `{versionId}` → конверт без нагрузки | `401 UNAUTHORIZED` |

Две вещи, которые видно только живым запросом:

- **`check` анонимен** — и это ровно то, что нужно: проверка идёт под
  держащимся splash'ем, то есть до входа. А вот **гео-заголовки обязательны**
  (`403 GEO_PERMISSION_REQUIRED` без них, issue #53) — их ставит
  `GeoHeaderInterceptor` на обоих клиентах, так что вопрос закрыт сам собой.
- **Все поля ответа nullable**: реестр версий на стенде пуст, и приходит
  `{"updateAvailable":false,"updateRequired":false,"policy":null, …}`. `null` у
  флага читается как «нет», а не как ошибка разбора.

`skip` требует Bearer — значит `AppVersionApi` собирается на **основном**
Retrofit, а не на «голом» `@RefreshClient`.

- **Домен** `feature/update/domain/AppUpdate.kt`: `AppUpdate`, `UpdatePolicy`
  (`OPTIONAL`/`FLEXIBLE`/`IMMEDIATE` + `Unknown`), `UpdateDecision`
  (`None`/`Required`/`Suggested`) и **`UpdateDecision.of` — чистая функция**,
  потому что цена ошибки здесь несимметрична: лишний блокирующий экран
  превращает приложение в кирпич сразу на всех устройствах.
  - `updateRequired` **или** политика `IMMEDIATE` → блокируем. Флага достаточно
    самого по себе: политика может не приехать, а требование — приехать.
  - `updateAvailable` и пропуски есть → мягкое предложение. `remainingSkips ==
    null` — это «сервер не считает пропуски», а не «пропусков не осталось».
  - `remainingSkips == 0` без требования → **не показываем ничего**. Экран без
    «Позже» при неблокирующем ответе был бы той блокировкой, о которой бэкенд
    не просил; настоять он может флагом.
- **Отказ проверки не запирает приложение** (`AppUpdateGate`): сеть, таймаут,
  любой код ответа → `UpdateDecision.None`. Плюс **собственный бюджет 3 секунды**
  — на клиенте стоят таймауты 15 сек на соединение и 30 на чтение
  (`NetworkFactory`), то есть недоступный сервер держал бы splash почти минуту,
  и запуск выглядел бы зависшим. Проверка идёт один раз за процесс
  (`RootViewModel.resolveStart`, рядом с `BackendUrlStore.hydrate()`), решение
  живёт в памяти синглтона: в аргументах маршрута оно попало бы в
  `SavedStateHandle` и пережило бы смерть процесса — экран всплыл бы с данными
  проверки, которой в этом запуске не было.
- **Пока адрес бэкенда не введён, версию не спрашиваем** (issue #26): запрос
  ушёл бы на адрес из сборки, то есть не туда, куда пользователь как раз
  собирается направить приложение.
- **`storeUrl` проверяется до открытия** (`StoreLink.sanitize`, как
  `TelegramBotLink` в #46): только `market:` и `https:`. Ссылку присылает
  сервер, а адрес сервера в debug вводит пользователь — без проверки
  подменённый бэкенд запускал бы произвольный intent (`intent://`, чужой deep
  link, наш собственный `mahalla://`). Список магазинов при этом **не**
  ограничен: APK проекта вполне может лежать на своём же сервере, а открытая в
  браузере ссылка показывает человеку адрес, куда он идёт. `http` не проходит:
  установочный файл не должен ехать по каналу, где его подменят. Ссылки нет или
  она негодная — подставляется карточка **собственного** пакета в Play
  (`BuildConfig.APPLICATION_ID`, не строка сервера): блокирующий экран без
  единой рабочей кнопки был бы тупиком.
- **Экран** `feature/update/ui/` — один на оба режима (различаются они ровно
  одной кнопкой и заголовком), маршрут `UpdateRoute` вне обоих графов,
  стартовым его назначает `MainActivity` после адреса бэкенда. Каркас —
  `OnboardingStep` без `onBack`: возвращаться отсюда некуда. «Позже» на
  блокирующем экране не просто не нарисовано — событие отбрасывается и во
  ViewModel: полагаться на ненарисованную кнопку для выхода из блокировки
  нельзя.
- **«Позже» сообщается серверу** (`app/version/skip`), но результат ни на что
  не влияет: до входа приходит `401` — пропуски привязаны к пользователю, а его
  ещё нет. Бюджет 2 секунды, неудача игнорируется; в худшем случае предложение
  повторится при следующем запуске.
- **Магазин может не открыться** (`ActivityNotFoundException`): прошивки без
  сервисов Google в Узбекистане обычны, и `market://` там открывать нечем. Тап
  без последствий читался бы как сломанная кнопка, поэтому показывается текст
  «скачайте через браузер».

Проверено: `./gradlew testDebugUnitTest` — **973 теста в 103 классах, 0
падений, 0 ошибок**; `lintDebug` — `No issues found`; `assembleDebug` и
`assembleRelease` — BUILD SUCCESSFUL. Новые тесты: `AppUpdateTest` и
`StoreLinkTest` (правила решения, разбор политик, отказ чужим схемам и
cleartext), `AppVersionRepositoryTest` (MockWebServer: тело запроса, пустой
ответ стенда, обязательное и мягкое обновление, подстановка своей карточки
вместо негодной ссылки, пустые строки, 2xx с `success:false`, тело и путь
`skip`, 401 без сессии), `AppUpdateGateTest` (отказ и медленный ответ не
запирают, один запрос за запуск, пропуск), `AppUpdateViewModelTest` (оба
режима, «Позже» мимо кнопки, второй тап, экран без решения не тупик), три
новых случая в `RootViewModelTest`; фейк `FakeAppVersionRepository`.

**Не сделано / риски:**

- **На устройстве не проверено** (эмулятора в CI нет) и **успешный путь не
  проверен на живых данных**: реестр версий на стенде пуст — `check` отвечает
  `updateRequired: false` и `null` во всех полях на любую версию, включая
  `currentVersionCode: 1`. Значит и блокирующий экран, и мягкое предложение
  проверены только тестами. **Первое, что надо сделать: завести версию в
  админке бэкенда (`POST admin/app-versions`) и посмотреть оба режима руками.**
- **`storeUrl` бэкенд сейчас не отдаёт вовсе**, то есть кнопка ведёт на
  карточку `uz.mahalla` в Play — а в Play приложения ещё нет. Пока канал
  распространения не выбран, это единственный безопасный фоллбэк; когда он
  появится, ссылку должен присылать сервер.
- **До входа пропуск не засчитывается** (`skip` отвечает `401`): мягкое
  предложение будет повторяться при каждом запуске, пока человек не вошёл.
  Внутри одного процесса «Позже» его гасит. Правильное место для починки —
  бэкенд: либо считать пропуски по `deviceId`, либо разрешить анонимный `skip`.
- **`versionCode` сборки равен `1`** (`app/build.gradle.kts` не менялся с
  каркаса). Пока он не начнёт расти с каждым релизом, реестр версий на бэкенде
  не сможет отличить старую сборку от новой — это отдельная задача про выпуск
  релизов.
- **Блокирующий экран доверяет одному полю ответа.** Если бэкенд однажды
  выставит `updateRequired: true` ошибочно, приложение встанет у всех сразу.
  Клиент от этого защититься не может (в том и смысл force-update), но помнить
  об этом стоит — как и о том, что `check` анонимен, а значит его ответ зависит
  от адреса сервера, который в debug вводит пользователь.
- Скриншот-тестов по-прежнему нет: оба режима экрана проверялись глазами по
  `@ThemeLanguagePreviews`.

## Этап: центр уведомлений (issue #81, задача T11)

Контроллер `notification` бэкенд отдавал с самого начала, клиента не было
вовсе: человек не узнавал ни о статусе заказа, ни об акциях, пока сам не
откроет нужный экран.

**Контракт снят со стенда** (`https://189-74-96-232.nip.io/v3/api-docs` +
прямые curl'ы; дизайн-репо агенту по-прежнему недоступно — `gh api` на
`jack5505/mahalla` отвечает 404):

| эндпоинт | контракт | без токена |
|---|---|---|
| `GET notifications?page&size` | конверт + `PageResponseNotification` (`content`, `page`, `totalPages`, `first`, `last`) | `401 UNAUTHORIZED` |
| `GET notifications/unread-count` | конверт, `data` — **число** (`ApiResponseLong`) | `401` |
| `PUT notifications/read-all` | конверт без нагрузки, тела в запросе нет | `401` |

Две вещи, которые видно только живым запросом:

- **Гео-заголовки обязательны и здесь**: без `X-Geo-Lat`/`X-Geo-Lng` приходит
  `403 GEO_PERMISSION_REQUIRED` раньше, чем проверка токена. Их уже ставит
  `GeoHeaderInterceptor` на обоих клиентах (issue #53), так что вопрос закрыт
  сам собой.
- **Отметки отдельного уведомления у бэкенда нет.** T11 просила снять её
  curl'ом «если эндпоинт есть» — в контроллере ровно три операции выше, а
  перебором путей это не проверить: 401 приходит до маршрутизации, и
  несуществующий путь отвечает так же, как существующий. Поэтому «прочитано» в
  приложении меняется только целиком.

Реализация (`feature/notifications/`):

- **Домен**: `NotificationType` (13 значений схемы + `Unknown`),
  `AppNotification`, `NotificationPage`, `NotificationTarget` — чистая функция
  `of()` про переход. Правило одно: экран открывается только там, где известно,
  **чем именно** является `entityId`. Из контракта это следует лишь для
  `ORDER_PLACED`/`ORDER_STATUS_UPDATED` → `OrderStatusRoute`. Вертикалей
  «очередь» (`WALKIN_*`) и «бронь» (`APPOINTMENT_*`) в приложении ещё нет,
  экранов акций и подписок тоже, а у `REVIEW_ADDED` из схемы не понять, отзыв
  это или заведение, — такие уведомления остаются текстом в списке и **не
  притворяются кликабельными** (`isActionable`): нажатие без последствий
  читается как сломанный экран, а переход по чужому id — как «заказ не
  найден».
- **Данные**: `NotificationsApi` на **основном** Retrofit (все три ручки
  требуют Bearer), `NotificationsRepository`. Кэша нет намеренно: уведомление,
  прочитанное на другом устройстве, из Room пришло бы непрочитанным. Разбор
  мягкий — запись без `id` отбрасывается (дубликат ключа в `LazyColumn`), а
  запись без текста остаётся: бейдж считает сервер, и список короче счётчика
  читался бы как потеря. `isRead` принимается и под именем `read` (Jackson
  сериализует `Boolean isRead` то так, то так — ошибка здесь покрасила бы
  непрочитанным весь список). `hasMore` — по `last`, иначе по
  `page`/`totalPages`, а полное молчание о страницах останавливает догрузку.
- **Экран** `NotificationsScreen` (маршрут `NotificationsRoute` вне графа
  табов, как поиск и карта): непрочитанное отличается точкой и жирным
  начертанием — одним цветом фона разницу не видно ни при высокой яркости, ни
  в монохромном режиме; пагинация по достижению конца списка, pull-to-refresh,
  перечит на `ON_RESUME`, отказы текстом сервера (issue #34). Состояния
  разложены руками, а не через `ScreenStateHost`: тот рисует `ApiErrorState` с
  собственной прокруткой, а внутри `LazyColumn` вложенная прокрутка меряется
  бесконечной высотой.
- **«Прочитать всё»** — иконка в топбаре, видна только когда `unreadCount > 0`
  (неактивная иконка читается как сломанная). После успеха загруженные
  страницы помечаются прочитанными **на месте**, без перезапроса: сервер уже
  подтвердил успех, а перезагрузка сбросила бы догруженный хвост к первой
  странице. Отказ показывается над списком, а не вместо него.
- **Бейдж** — `NotificationsBadgeAction` + своя `NotificationsBadgeViewModel` в
  топбаре главной. Отдельно от `DiscoveryHomeViewModel`: счётчик к каталогу
  отношения не имеет, а обновляться обязан на каждом `ON_RESUME` — уведомление
  приходит, пока приложение в фоне, и «прочитать всё» гасит счётчик на соседнем
  экране. Отказ бэкенда бейдж не трогает: обнулить его из-за пропавшей сети
  значило бы соврать, что непрочитанного нет. Трёхзначное число сокращается до
  `99+` — точное количество ничего не решает, а на узком экране наезжает на
  соседнюю иконку.

Проверено: `./gradlew testDebugUnitTest` — **1005 тестов в 107 классах, 0
падений, 0 ошибок**; `lintDebug` — `No issues found`; `assembleDebug` и
`assembleRelease` — BUILD SUCCESSFUL. Новые тесты: `NotificationTargetTest`
(разбор типов, правила перехода, пустой `entityId`), `NotificationsRepositoryTest`
(MockWebServer: query, конверт, дата без зоны, оба имени флага «прочитано»,
отбрасывание записей, `last`/`totalPages`, число в `data`, отрицательный
счётчик, `PUT` без тела, 2xx с `success:false`, 401), `NotificationsViewModelTest`
(независимость списка и счётчика, перечит на возврате, догрузка и дедупликация,
провал догрузки, «прочитать всё» и его отказ, переход по заказу, молчание на
уведомлении без цели), `NotificationsBadgeViewModelTest`; новые случаи в
`GraphAssemblyTest` и `RoutesSerializationTest`; фейк
`FakeNotificationsRepository`.

**Не сделано / риски:**

- **На устройстве и на живом аккаунте не проверено** (эмулятора в CI нет, а
  успешный вход требует SMS-кода): список, бейдж и «прочитать всё» проверены
  только тестами. **Первое, что надо посмотреть руками — что именно лежит в
  `entityId` у `ORDER_*`**: если это не id заказа, переход приведёт на «заказ
  не найден».
- **Отметить одно уведомление прочитанным нельзя** — эндпоинта нет. Открытое
  уведомление остаётся непрочитанным, пока не нажать «прочитать всё»; это
  вопрос к `jack5505/mahalla` (`PUT notifications/{id}/read`).
- **Большая часть типов никуда не ведёт** — очередь, бронь, акции, подписки и
  ответ на отзыв станут переходами вместе со своими экранами.
- **Это только клиентская половина пушей**: FCM (эпик #15) не подключён,
  `DeviceDescriptor.fcmToken` по-прежнему `null`, и уведомление человек увидит,
  только открыв приложение.
- **Картинки уведомления (`imageUrl`) не показываются**: загрузчика изображений
  в проекте по-прежнему нет. Поле объявлено в DTO (там оно документирует
  контракт), но до домена не доезжает — показывать его пока нечем.
- Скриншот-тестов нет: список и бейдж проверялись глазами по
  `@ThemeLanguagePreviews`.

## Этап: две анкеты — покупатель и продавец (issue #84)

Приложение не спрашивало, кем человек им пользуется: и тот, кто заказывает
услуги, и тот, кто их оказывает, проходили один и тот же онбординг и попадали
на один и тот же каталог. Зарегистрировать своё заведение из приложения было
нельзя вовсе, хотя `POST /api/v1/places` бэкенд умеет с самого начала.

**Контракт снят со стенда** (`https://189-74-96-232.nip.io/v3/api-docs` +
прямые curl'ы; дизайн-репо агенту по-прежнему недоступно):

| эндпоинт | контракт | без токена |
|---|---|---|
| `POST places` | тело — см. ниже; ответ `Detail` (`id, ownerId, name, category, description, address, lat, lng, city, phone, website, status, logoUrl, …`) | `401 UNAUTHORIZED` |

- **Имена полей запроса выведены из ответа, а не из схемы.** Тело объявлено как
  `CreateRequest`, а это имя в `/v3/api-docs` перекрыто коллизией springdoc —
  под ним лежат пять разных запросов, и уцелел вариант отзыва (`placeId` +
  `rating`). Живым запросом форму тела тоже не проверить: `401` приходит **до**
  валидации (проверено). Поэтому поля названы так же, как в ответе `Detail`
  того же эндпоинта — то же решение, что принято для отзывов в issue #76.
  **Это первое, что надо проверить руками под токеном.**
- **`status` в ответе — `PENDING`**, то есть заявка уходит на модерацию.
  Значит успех формы это не «готово», а отдельный экран с объяснением: в
  каталоге заведение появится не сразу.
- **Профиля пользователя у бэкенда нет вовсе** (ни `GET`, ни `PUT /users/me` —
  issue #61), поэтому анкета покупателя никуда не отправляется: она нужна
  самому приложению.

Реализация — новая фича `feature/role/`:

- **Домен**: `UserRole` (`Customer`/`Provider`, `storedValue` для DataStore —
  переименование константы не должно стирать выбор человека; незнакомое
  значение читается как «не выбрано», а не как «покупатель»), `CustomerForm` +
  `CustomerFormValidator`, `ProviderForm` + `ProviderFormValidator`,
  `WebsiteLink`, `PlaceModerationStatus`, `RegisteredPlace`. Оба валидатора
  возвращают **все** ошибки сразу: форма длинная, и замечания по одному гоняли
  бы человека по экрану.
- **Выбор роли** (`RoleScreen`) — последний шаг онбординга (после гео) и строка
  «Моя анкета» в профиле. Один экран на два входа, различие ровно одно —
  «Пропустить»: в регистрации анкету можно отложить (упереться на последнем
  шаге в форму, которую человек пока не хочет заполнять, значит потерять его у
  самого входа), а из профиля отказываться не от чего. Выбор роли пишется
  **до** заполнения анкеты: форму можно закрыть на полпути, и спрашивать «кто
  вы» второй раз незачем.
- **Анкета покупателя**: имя, город, адрес по умолчанию. Всё уходит туда, где
  приложение это уже читает: имя — в `UserProfileStore` (тот же профиль, что
  показывает шапка; два разных имени у одного человека читались бы как ошибка),
  город — в `settings_city_id` (оттуда его берут координаты запросов), адрес —
  в новый `settings_delivery_address`, и **подставляется в оформление заказа**
  (`CheckoutViewModel.prefillAddress`, только в пустое поле: чтение
  асинхронное, набранное затирать нельзя). Отказ хранилища показывается
  словами, и экран не закрывается: анкета, которая «сохранилась» и пропала
  после перезапуска, хуже честного отказа.
- **Анкета продавца**: название, категория (`PlaceCategory.selectable` —
  значения перечисления бэкенда), город, адрес, телефон, описание, сайт.
  Телефон и город предзаполняются из того, что приложение уже знает (номер
  аккаунта, выбранный город) — набирать заново уже введённое самый быстрый
  способ получить брошенную форму. Незаполненная заявка в сеть не уходит
  (`ApiError.Business(PROVIDER_FORM_INVALID)`), отказ сервера показывается его
  текстом (issue #34) и **не стирает** набранное.
- **Координаты заведения**: измеренная позиция устройства, иначе центр города
  **из формы** (а не из настроек: заведение может стоять в другом городе),
  иначе Ташкент. Карты с выбором точки в форме нет — это записано в рисках.
- **Сайт проверяется до отправки** (`WebsiteLink.sanitize`): схема дописывается
  сама (`mahalla.uz` → `https://mahalla.uz`), но чужие схемы отклоняются, а не
  «чинятся» — ссылку из карточки открывает `Intent`, и `market://`,
  `intent://`, `mahalla://` в поле «сайт» это не сайт (то же правило, что у
  ссылки на бота в issue #46 и на магазин в issue #80).
- **Кит**: `MahallaChoiceCard` (`core/ui/components/Choice.kt`) — карточка-выбор
  с объяснением: у роли есть цена («вы регистрируете заведение, его проверит
  модерация»), и в чип она не умещается. Вся карточка — одна цель нажатия с
  ролью `RadioButton`, выбранность дублируется рамкой и `stateDescription`.
  Новая цель нажатия `choiceCard` в `MahallaComponentDefaults`.
- **Навигация**: `RoleRoute(onboarding)`, `CustomerFormRoute(onboarding)`,
  `ProviderFormRoute(onboarding)` — вне обоих графов, как экран адреса бэкенда.
  Флаг аргументом, а не отдельными маршрутами: два одинаковых экрана в графе
  разошлись бы при первой же правке. Выход из онбординга вынесен в
  `finishOnboarding()` — точек стало три (пропуск, анкета покупателя, заявка
  продавца), и `popUpTo(OnboardingGraph)` снимает заодно экраны анкет, которые
  лежат выше графа.
- `City.labelRes()` вынесен из `GeoScreen` в `feature/onboarding/ui/CityLabels.kt`:
  город теперь выбирают на трёх экранах, а три копии списка разъехались бы.

Проверено: `./gradlew testDebugUnitTest` — **1060 тестов в 113 классах, 0
падений, 0 ошибок**; `lintDebug` — `No issues found`; `assembleDebug` и
`assembleRelease` — BUILD SUCCESSFUL. Новые тесты: `RoleFormsTest` (правила
обоих валидаторов, разбор роли и статуса, ссылка на сайт — включая чужие схемы
и хост без точки), `ProviderRepositoryTest` (MockWebServer: тело и путь
запроса, координаты от устройства и по городу, пустые поля отсутствуют, а не
`null`, незаполненная форма не уходит в сеть, ответ без имени, 409 с текстом
бэкенда, 2xx с `success:false`, 401), `RoleRepositoryTest` (Robolectric +
DataStore: склейка двух хранилищ, имя не затирает id и номер, пустой адрес не
хранится), `RoleViewModelTest`, `CustomerFormViewModelTest`,
`ProviderFormViewModelTest`; два новых случая в `CheckoutViewModelTest`
(подстановка адреса и то, что набранное не затирается), новые случаи в
`RoutesSerializationTest`, `TouchTargetTest` и `GraphAssemblyTest`; фейки
`FakeRoleRepository` и `FakeProviderRepository`.

**Не сделано / риски:**

- **Тело `POST places` не подтверждено живым запросом** (нужен токен, а SMS-кода
  в CI нет): `401` приходит до валидации, а схема перекрыта коллизией. Не
  совпадут имена — заявка будет отвечать `VALIDATION_ERROR` с текстом сервера
  на экране. **Проверять это первым.**
- **На устройстве не проверено** (эмулятора в CI нет): оба экрана, шаг роли в
  конце онбординга, подстановка адреса в checkout.
- **Роль ничего не меняет в приложении, кроме анкеты**: бизнес-панель (эпик
  #16) с заказами заведения, `places/{id}` на редактирование, `staff` и
  `wallet/business` — отдельная задача. Права всё равно выдаёт бэкенд по
  `ownerId`, а не клиент по локальной настройке.
- **Судьбу заявки в приложении не видно**: после отправки статус не
  перечитывается (`GET places/{id}` требует id, а «мои заведения» у бэкенда
  нет вовсе — ни `places/my`, ни фильтра по владельцу). Модерация сообщит
  уведомлением, когда FCM подключат (эпик #15).
- **Фрилансер (`POST freelancers/me`) не сделан**: это второй, отдельный вид
  продавца (профиль мастера с `profession`/`hourlyRate` и своими услугами
  `freelancers/me/services`), и его тело в схеме перекрыто той же коллизией.
  Выбран `places`: заведение — основная сущность каталога.
- **Категорий в форме шесть** (`PlaceCategory.selectable`), а бэкенд знает
  тринадцать (`BAKERY`, `SHOP`, `MUSEUM`, `PARK`, `MOSQUE`, `FASHION`,
  `FREELANCER`): зарегистрировать музей или магазин через приложение пока
  нельзя. Расширять — вместе с иконками и строками ТЗ.
- **Логотип и обложку заведения не загрузить**: `logoUrl`/`coverUrl` бэкенд
  отдаёт, но загрузчика изображений в проекте по-прежнему нет (`media/upload`
  ждёт своей задачи).
- **Анкета покупателя переживает переустановку только вместе с DataStore**, а
  он исключён из бэкапа (там же токены) — как и профиль из issue #61.
- Скриншот-тестов по-прежнему нет: выбор роли, обе формы и подтверждение
  заявки проверялись глазами по `@ThemeLanguagePreviews`.

## Этап: вход больше не пускает в чужой аккаунт (issue #86)

Человек вводил в форму **любой** номер и оказывался в том аккаунте, под
которым это устройство когда-то входило через Telegram — приложение при этом
говорило «вы успешно авторизовались».

**Причина — контракт бэкенда.** Вход завершает не `verify-otp` (он токенов не
выдаёт, issue #51), а `auth/pin-login`, и его тело — `{pin, device, lat, lng}`:
ни номера, ни `otpToken`, ни `sessionId`. Пользователя сервер ищет **по
устройству**. Проверено на стенде (`/v3/api-docs` + curl'ы):

```
PinLoginRequest.required = [device, lat, lng, pin]
POST /api/v1/auth/pin-login с незнакомого устройства →
  {"success":false,"error":{"code":"DEVICE_UNKNOWN","message":"Bu qurilma tanilmadi. SMS bilan kiring."}}
```

Значит на телефоне, где раньше входил аккаунт A, шаг PIN возвращает **токены
A** — какой бы номер ни подтвердили секундой раньше. Токены настоящие и
рабочие, поэтому дальше всё выглядит как обычный успешный вход.

Клиент чинить это не может (номер в запросе просто не предусмотрен), но
обязан не выдавать чужой вход за свой. Сделано:

- **Сверка аккаунта** (`feature/auth/domain/PhoneIdentity.kt`): репозиторий
  помнит номер, под которым идёт вход (`pendingPhone`, в памяти процесса — как
  `pendingServerPin`), и сравнивает его с `user.phone` из ответа. Не совпало —
  **токены не сохраняются**, испытание выбрасывается, локальные следы прежнего
  владельца чистятся, наверх уходит `ApiError.Business("ACCOUNT_MISMATCH")`.
  Проверяются оба места, где приезжают токены: `verify-otp` и общий хвост
  `setup-pin`/`pin-login`. Сравниваются **значащие цифры** (последние девять):
  бэкенд отдаёт номер то как `+998901234567`, то как `998901234567`, а человек
  вводит национальную часть.
- **Неизвестный номер — не повод отказать**: ответ без `user.phone` проходит
  как прежде. Отказывать во входе на каждое отсутствующее поле значило бы
  сломать вход целиком ради случая, которого в ответе не видно. По той же
  причине Telegram-путь не затронут: там номер не вводят вовсе, сравнивать не
  с чем.
- **Новый вход отменяет прежний** (`requestCode`): успешно ушедший код под
  номером, который не совпадает с сохранённым профилем, стирает сессию,
  профиль и локальный PIN. Без этого прежний PIN открывал бы чужое приложение
  **вообще без единого запроса** — `PinViewModel` на шаге `Unlock` проверяет
  код локальным Keystore-хэшем и пускает в живую сессию. Чистка только на
  успехе запроса: опечатка в номере не должна стоить человеку аккаунта. Свой
  же номер (профиль совпал) сессию и PIN сохраняет.
- **Уборка не роняет вход**: три записи (`SessionStore`, `UserProfileStore`,
  Keystore) идут под `runCatchingCancellable` + `reportSwallowed` — это уборка
  по дороге, а не то, ради чего человек нажал кнопку.
- **Экраны объясняют**: `PinError.FOREIGN_ACCOUNT` и `OtpFailure.ForeignAccount`
  с общей строкой `onboarding_error_foreign_account` (uz + ru). На PIN-экране
  дальше — `AuthRestartRequired` (вход с начала): оставлять человека на шаге,
  где повтор кода даст тот же ответ, некуда. На экране кода ввод блокируется:
  код был верный, набирать его снова незачем.

Проверено: `./gradlew testDebugUnitTest` — **1079 тестов в 114 классах, 0
падений, 0 ошибок**; `lintDebug` — `No issues found`; `assembleDebug` и
`assembleRelease` — BUILD SUCCESSFUL. Новые тесты: `PhoneIdentityTest`
(нотации номера, разные абоненты, неизвестный номер с любой стороны, короткие
строки, код отказа), девять случаев в `AuthRepositoryTest` на MockWebServer
(чужой аккаунт на `pin-login` и на `verify-otp`, свой номер в другой записи,
ответ без телефона, Telegram-путь, сброс прежней личности и его отсутствие на
своём номере и на провале запроса, отказ Keystore), по случаю в
`PinViewModelTest`, `OtpViewModelTest` и `OtpFailureTest`.

**Не сделано / риски:**

- **Настоящий фикс — на бэкенде** (`jack5505/mahalla`): `pin-login` обязан
  быть привязан к только что подтверждённому номеру (`sessionId` из
  `verify-otp` либо `phone` в теле), иначе владельцем устройства навсегда
  остаётся тот, кто вошёл первым. Сейчас **новый человек на б/у телефоне
  войти не может вовсе**: клиент его больше не пускает в чужой аккаунт, но
  своего он не получит, пока сервер ищет по `deviceId`. Это ухудшение по
  сравнению с «пускало куда попало» только на вид: раньше он получал чужие
  деньги и чужие заказы.
- **Проверка держится на `user.phone` в ответе**: если бэкенд перестанет его
  отдавать, защита выключится молча (по построению — см. выше про неизвестный
  номер). Стоит закрепить поле в контракте.
- **Сообщение теряется на переходе**: `AuthRestartRequired` уводит на welcome
  сразу, и текст успевает мигнуть. Та же беда у давнего
  `PinError.TOO_MANY_ATTEMPTS` — лечится общим хостом снекбара, которого в
  приложении пока нет.
- **На устройстве не проверено** (эмулятора в CI нет, успешный вход требует
  SMS-кода): сценарий «телефон с прежним Telegram-аккаунтом + новый номер»
  прогонялся только тестами и curl'ами по стенду.

## Этап: «Еда» приведена к реальному контракту бэкенда (issue #9, второй круг)

Вертикаль была написана целиком (эпик 5, PR #29), но **по выдуманному
контракту** — это записанный тут же главный риск того этапа. На живом бэкенде
не работал ни один экран: `places/{id}/menu`, `POST orders`,
`orders/{id}/cancel` и `places/{id}/promo` у него не существуют вовсе, а
ответы приезжают в общем конверте `{success, data, error}` (issue #42), а не
голым JSON.

**Как снят контракт** (дизайн-репо агенту по-прежнему недоступно):
`https://189-74-96-232.nip.io/v3/api-docs` + прямые curl'ы по стенду.

| | было в приложении | реально на бэкенде |
|---|---|---|
| меню | `GET places/{id}/menu`, голый JSON | `GET food/places/{placeId}/menu` → конверт, `data: [MenuResponse]` |
| заказ | `POST orders` c ценами, модификаторами, комментарием и временем | `POST food/orders`, тело `{placeId, items[{itemId, quantity}], fulfillment, paymentMethod, deliveryAddress}` |
| статус | `GET orders/{id}` | `GET orders/{orderId}` → конверт, `OrderView` |
| отмена | `POST orders/{id}/cancel` | `POST food/orders/{orderId}/cancel` |
| статусы | `created`, `confirmed`, `ready_for_pickup`… | `NEW`, `ACCEPTED`, `PREPARING`, `READY`, `IN_DELIVERY`, `DELIVERED`, `CANCELLED`, `REFUNDED` |
| способ и оплата | `delivery`/`pickup`, `wallet`/`cash` | `DELIVERY`/`PICKUP`/`DINE_IN`, `WALLET`/`CASH` |

Что из этого следует для кода:

- **Меню анонимно** (проверено: `200` с `data: []` на любой `placeId`), а
  `data` — список «меню» заведения, где каждое работает как категория
  (`{id, name, description, items}`). Гео-заголовки обязательны и здесь, но
  их уже ставит `GeoHeaderInterceptor` (issue #53).
- **Читать заказ идём в общий `orders/{orderId}`**, а не в
  `food/orders/{orderId}`: у первого ответ описан схемой `OrderView` со всеми
  суммами (`itemsAmount`, `deliveryAmount`, `discountAmount`, `totalAmount`),
  у второго имя схемы `OrderResponse` перекрыто коллизией springdoc — под ним
  лежит заказ фрилансера, и имена полей оттуда взять нельзя. По той же
  причине **ответы создания и отмены не разбираются**: из создания берётся
  только `id` (`orderId` — второе допустимое имя), а новое состояние после
  отмены перечитывается `order()`'ом. Иначе неудачный разбор ответа выглядел
  бы как «отменить не удалось», хотя заказ уже отменён.
- **Стоп-лист принимается под двумя именами** (`isAvailable` и `available`):
  Jackson сериализует `boolean isAvailable` то так, то так, а ошибка здесь
  увела бы в стоп-лист всё меню (то же правило, что у `isRead` в issue #81).
- **Название заведения едет маршрутом** (`MenuRoute(placeId, placeName)`, из
  карточки места): в ответе меню его нет, а в ответах о заказе — тем более.
  Для экрана статуса имя подставляется из кэша заказов, куда его кладёт
  оформление (единственный, кто его знает, — корзина).
- **Дата заказа разбирается общим `parseServerInstant`** и стала nullable:
  Jackson отдаёт `LocalDateTime` без зоны, а прежний `Instant.ofEpochSecond`
  показал бы 1970 год.

**Что убрано из приложения, потому что бэкенду это нечем принять.** Поле,
которое некуда отправить, — обещание, которого никто не выполнит:

- **Промокод** (весь экран корзины, `PromoCode`, `PromoState`, `PromoFailure`).
  Проверить код бэкенд умеет (`GET promotions/check?code&placeId&orderAmount`
  → `{valid, discountAmount, finalAmount}`), но в `PlaceOrderRequest` поля под
  код **нет**, то есть скидка в счёт не попадёт. Показать «−20 %» в корзине и
  выставить полную сумму — врать про деньги.
- **Время заказа** (`DeliverySlots`, `CheckoutForm.asap`/`scheduledAt`,
  ошибки `TimeRequired`/`TimeTooSoon`) и **комментарий к заказу**: этих полей
  в теле заказа нет. Кухня о просьбе «не звонить в дверь» не узнала бы.
- **Стоимость доставки и минимальный заказ в меню**: контракт их не отдаёт.
  Корзина и checkout показывают только сумму позиций, а настоящая доставка
  приезжает в `OrderView` — на экране заказа. Прежний «+15 000» до оформления
  был выдумкой.
- **Оценка времени готовности** (`etaMinutes`) — того же рода.

**Модификаторы оставлены, но приезжают пустыми.** Групп в контракте нет ни в
меню, ни в заказе, поэтому `MenuItemDto` их не разбирает, а `MenuOptionRules`,
шторка выбора и ключ строки корзины (`lineId` = позиция + варианты) остались
готовыми: подключить их обратно — одно поле в DTO. Собирать группы на клиенте
нельзя — заказ всё равно уедет без них, и человек получит не то, что выбрал.

Заодно доделано то, что висело с прошлого круга: `MenuViewModel`,
`CheckoutViewModel` и `OrderStatusViewModel` переведены с `result.error` на
`result.failure`, то есть на экранах еды теперь виден **текст сервера**
(issue #34) — отказ оформления и отказ отмены показываются через
`OnboardingApiError` с раскрывающимися подробностями.

Проверено: `./gradlew testDebugUnitTest` — **1058 тестов в 113 классах, 0
падений, 0 ошибок**; `lintDebug` — `No issues found`; `assembleDebug` и
`assembleRelease` — BUILD SUCCESSFUL.

**Не сделано / риски:**

- **Успешный путь на живом стенде не проверен**: и заказы, и отмена требуют
  Bearer, а SMS-кода в CI нет (`401` приходит до валидации тела). Проверены
  анонимно только меню (`200`, `data: []`) и `promotions/check` (`404
  NOT_FOUND` на неизвестный код). Каталог стенда при этом пуст (issue #53) —
  заказать нечего, пока в нём нет заведений с меню.
  **Первое, что надо проверить руками: тело `POST food/orders` принимается
  бэкендом и в `id` ответа лежит идентификатор заказа.**
- **Заказ читается общим эндпоинтом на веру**: `OrderView.vertical` в схеме
  включает `FOOD`, то есть `GET orders/{orderId}` обязан отдавать и заказы
  еды, — но без токена это не проверить. Не отдаёт — экран статуса покажет
  ошибку сервера, и тогда придётся идти в `food/orders/{orderId}`, разобравшись
  с именами его полей.
- **Единица цены не подтверждена**: `price` у позиции меню и суммы `OrderView`
  приходят целыми числами без дробного близнеца (в кошельке пара
  `balance`/`balanceSom` есть — issue #62, здесь нет). Считаем, что это сумы.
  Если окажется, что тийины, суммы на экранах меню, корзины и заказа будут в
  сто раз больше.
- **Задачи бэкенду** (`jack5505/mahalla`), без них функциональность issue #9
  не восстановить: `promoCode` в `PlaceOrderRequest`; `comment` и время
  доставки там же; группы модификаторов у позиции меню и `optionIds` в строке
  заказа; стоимость доставки и минимальный заказ в ответе меню; название
  заведения в `OrderView`; развести коллизию springdoc у `OrderResponse`
  (сейчас имена полей ответа создания и отмены неизвестны).
- **`DINE_IN` в приложении не выбирается**: сегментированный контроль
  показывает доставку и самовывоз, а пришедший от сервера `DINE_IN` читается
  как самовывоз (адреса нет, курьера нет).
- **Столбец `cart_draft_items.deliverySum` остался неиспользованным** (пишется
  ноль): убирать его отдельной миграцией ради нуля незачем — уйдёт со
  следующим повышением версии схемы (issue #64).
- **`prepMinutes` и `isHalal` у позиции меню приходят, но не показываются**:
  в DTO они объявлены (документируют контракт), в домен не доезжают. Оба
  просятся на карточку блюда — отдельная задача с вёрсткой и строками.
- **`GET food/orders/my` и общий `GET orders?vertical=FOOD` не используются** —
  экрана «мои заказы» в приложении пока нет; заказ открывается только сразу
  после оформления.
- На устройстве по-прежнему ничего не проверено (эмулятора в CI нет),
  скриншот-тестов нет.

## Этап: точка заведения выбирается на карте (issue #90)

Анкета продавца (issue #84) спрашивала адрес словами, а координаты подставляла
сама: позиция устройства → центр города → центр Ташкента. То есть заявку
заполняют дома, а заведение уезжает на карту по домашнему адресу — и под полем
адреса об этом было честно написано: «Точку на карте выбрать нельзя». Ровно
это и просит issue.

**Скриншот в issue не догрузился** (`![Uploading …]()`), поэтому экран определён
по коду: это единственное место, где приложение отправляет координаты
**заведения** и при этом не даёт их выбрать (строка `role_field_place_address_note`).

- **Полотно уже было** (эпик 4.2, подключено в issue #65) — новый экран
  `feature/map/ui/picker/` собран из него: `MapCanvas` без маркеров, метка
  неподвижно в центре, карта ездит под ней. Выбранная точка — это всегда центр
  камеры, отдельного поля в состоянии нет. Тапом по карте точка не ставится
  намеренно: под пальцем её не видно, а `InputListener` пришлось бы добавлять в
  общее полотно, где на JVM его не проверить.
- **Начальная позиция** по убыванию точности: точка, выбранная в прошлый раз
  (аргумент маршрута) → `RequestLocationProvider` (последняя известная позиция →
  центр города из настроек → Ташкент). Свежие координаты у MapKit на входе не
  спрашиваются — это ожидание фикса на экране, который открыли, чтобы двигать
  карту руками; для этого есть кнопка «моё местоположение». Пока позиция ищется,
  **кнопка подтверждения выключена**: иначе человек подтвердил бы центр
  Ташкента, которого не выбирал. Жест пользователя при этом старше поиска —
  приехавший вторым ответ карту из-под пальца не уводит.
- **`MapPoint`** (`feature/map/domain/`) — точка с проверкой на входе (диапазон
  Земли, `NaN`) и парой `encode`/`decode`. Едет строкой `"41.311081,69.240562"`:
  типизированные маршруты кладут аргументы в `Bundle`, и ради пары дробных
  чисел понадобился бы свой `NavType` (то же решение, что у канала доставки кода
  в `OtpRoute.channel`). Формат — `Locale.ROOT`: на русской локали `%f` дал бы
  `41,311081`, и разделитель полей совпал бы с разделителем дробной части (та же
  грабля, что у `GeoHeaderInterceptor`, issue #53).
- **Результат возвращается через `SavedStateHandle` предыдущей записи стека**, а
  не коллбэком: экран выбора не знает, кто его позвал, а живой ссылки на
  вызвавший экран у графа нет — тот мог пережить смерть процесса. Ключ гасится
  сразу после применения, иначе точка возвращалась бы в форму при каждом
  возврате на неё.
- **В заявке выбранная точка старше позиции устройства**
  (`DefaultProviderRepository`): человек показал место сам, а где он в этот
  момент сидит — не важно. Когда точка выбрана, `LocationManager` не
  опрашивается вовсе. Остальная лестница прежняя, точка необязательна: без неё
  всё работает как раньше, и это прямо написано под кнопкой.
- **Общая надстройка над картой** вынесена в `feature/map/ui/MapOverlay.kt`
  (плашка, кнопки масштаба и «моё местоположение», проверка разрешения): два
  экрана рисуют поверх карты одно и то же, а две копии разошлись бы при первой
  правке.
- Строки: `map_picker_*` и `role_place_point_*` в обеих локалях;
  `role_field_place_address_note` переписана — она перестала быть правдой.

Проверено: `./gradlew testDebugUnitTest` — **1085 тестов в 115 классах, 0
падений, 0 ошибок**; `lintDebug` — `No issues found`; `assembleDebug` и
`assembleRelease` — BUILD SUCCESSFUL. Новые тесты: `MapPointTest` (кодирование
на русской локали, мусор в аргументе, координаты вне планеты, точность),
`MapPickerViewModelTest` (старт из аргумента и из позиции устройства, жест
старше поиска старта, подтверждение отдаёт центр камеры, клампинг зума, все
четыре исхода «моего местоположения», защита от второго запроса), новые случаи
в `ProviderFormViewModelTest` (карта открывается с уже выбранной точки, точка
доезжает до заявки), `ProviderRepositoryTest` (точка бьёт позицию устройства и
устройство при этом не опрашивается) и `RoutesSerializationTest`.

**Не сделано / риски:**

- **На устройстве не проверено** (эмулятора и ключа MapKit в CI нет): сам выбор
  точки, метка в центре, «моё местоположение». Без секрета `MAPKIT_API_KEY` на
  месте карты будет объяснение — то же, что на экране карты (issue #65).
- **Адрес по точке не подставляется**: MapKit взят в варианте `lite`, геокодера
  в нём нет. Поэтому координаты показаны цифрами, а адрес человек по-прежнему
  пишет словами — сверить одно с другим может только он сам.
- **Обратной проверки нет**: приложение не сравнивает выбранную точку с
  написанным адресом и с выбранным городом. Точка в Самарканде при городе
  «Ташкент» уедет на бэкенд как есть — это работа модерации.
- **Только анкета продавца.** Адрес доставки в анкете покупателя и в оформлении
  заказа остался текстовым: координат бэкенд там не принимает (`PlaceOrderRequest`
  — только `deliveryAddress`, issue #9), а без геокодера точка на карте не
  превращается в адрес. Экран выбора для этого готов — не хватает поля в
  контракте.
- **Выбранная точка не переживает смерть процесса**: она живёт в состоянии
  ViewModel анкеты, как и остальные поля формы.

## Этап: картинки — Coil и MahallaAsyncImage (issue #60, задача T1)

Загрузчика изображений в проекте не было вовсе (`grep -i 'coil\|glide'` по
каталогу версий — пусто), при этом данные приезжали и выбрасывались:
`logoUrl`/`coverUrl` разбирались, складывались в `PlaceDetails.photos` — и
дальше от них оставался только `.size`. Галерея на карточке места рисовала
скелетоны **по числу фотографий**, то есть считала снимки и показывала вместо
них пустые прямоугольники. Это и есть главная причина, по которой приложение
выглядело пустым.

Спецификация T1 живёт в `docs/TASKS-BACKLOG.md` на невлитой ветке
`claude/issue-59-20260830-0152` — в `main` этого файла нет, ссылка из issue
битая; текст снят `gh api` с той ветки.

- **Библиотека**: `io.coil-kt:coil-compose` 2.7.0. Не третья ветка: она тянет
  собственный сетевой модуль и Compose Multiplatform, а здесь нужен ровно
  Android + OkHttp, который в проекте уже настроен.
- **`core/image/MahallaImageLoader`** — сборка `ImageLoader` вне DI-модуля (по
  той же причине, что и `NetworkFactory`: конфигурацию проверяет обычный тест,
  а не его копия). Crossfade 200 мс, память 20 %, диск 64 МБ,
  `respectCacheHeaders(false)` — бэкенд отдаёт медиа без заголовков
  кэширования, а фото заведения между двумя открытиями экрана не меняется;
  без этого дисковый кэш не использовался бы вовсе.
- **Свой OkHttp для картинок**: тот же пул соединений и то же доверие
  сертификату стенда (issue #32), но интерцепторы очищены и `Authenticator.NONE`.
  Снято три вещи, и каждая по делу: `Authorization` — ссылка на картинку ведёт
  куда угодно, и Bearer не должен уезжать на чужой хост; подстановка адреса и
  гео-заголовки (issue #53) — ссылка уже абсолютная; инспектор трафика
  (issue #30) — лента из сотни картинок вытеснила бы из Chucker'а настоящие
  запросы. `TokenAuthenticator` тоже снят: 401 на картинку не повод обновлять
  сессию.
- **Загрузчик создаётся лениво**: `MahallaApplication` реализует
  `ImageLoaderFactory` (Coil берёт синглтон именно из `Application`, другого
  места объявить его нет), а сам `ImageLoader` приезжает из графа через
  `Provider`, клиент внутри — через `dagger.Lazy`. Сетевой стек поднимается под
  держащимся splash'ем, и тянуть его ради ещё не запрошенной картинки незачем.
- **Относительные ссылки**: бэкенд отдаёт то `https://…`, то `/media/…`, то
  `media/…`, а адрес API задаёт пользователь (issue #26). Поэтому хост
  подставляется **в момент загрузки** — `ImageUrl.resolve` + Coil-интерцептор
  `BackendImageUrlInterceptor`, а не преобразование в маппере: ссылка к этому
  времени уже лежит в Room и в состоянии экрана, и после переезда стенда
  картинки обязаны поехать туда же, куда запросы. Схемы ограничены
  `http`/`https`/`data`: строку присылает сервер, а `file://`/`content://`
  показали бы в списке мест кусок локального хранилища (то же правило, что у
  ссылки на бота в issue #46 и на магазин в issue #80).
- **Кит** `core/ui/components/AsyncImages.kt`: `MahallaAsyncImage` (скелетон →
  фото с проявлением → фоллбэк-иконка; `contentDescription` — обязательный
  параметр, `null` означает «декоративная»), `MahallaThumbnail` (64 dp),
  `MahallaAvatar` (круг, вместо фото — первая буква имени: в списке отзывов она
  различает авторов, а силуэт делает их одинаковыми). Три состояния, и ни одно
  не пустое: пустая дыра читается как «экран сломался». `SkeletonBox` принял
  `height: Dp?` — заглушка на месте фотографии занимает столько, сколько дал
  родитель.
- **Куда подключено**: `PlaceCard` (главная, поиск, карта), галерея карточки
  места (`LazyRow` 240×160 dp — край следующего фото виден, значит листается;
  подпись для TalkBack одна на весь блок), аватар автора отзыва, шапка профиля
  (`avatarUrl` приезжал в ответе на вход и хранился с issue #61, но экран
  рисовал только инициалы), строка меню.
- **Фото блюда — на вырост**: у `ItemResponse` в схеме стенда картинки **нет**
  вовсе. Поле объявлено под тремя вероятными именами (`imageUrl` бэкенд уже
  использует у `CartItemResponse`), а миниатюра в строке меню рисуется только
  когда ссылка приехала: ряд одинаковых фоллбэк-иконок был бы шумом, а не
  вёрсткой.
- **Правила ProGuard** для Coil положены заранее (`app/proguard-rules.pro`):
  минификация пока выключена, но включение R8 (задача про размер APK) не должно
  начинаться с разбора падений в рантайме.

Проверено: `./gradlew testDebugUnitTest` — **1103 теста в 117 классах, 0
падений, 0 ошибок**; `lintDebug` — `No issues found`; `assembleDebug` и
`assembleRelease` — BUILD SUCCESSFUL. Новые тесты: `ImageUrlTest` (абсолютные,
корневые и относительные ссылки, протокол-относительная, `data:`, отказ
локальным схемам, битый URL, база без завершающего слэша) и
`MahallaImageLoaderTest` (Robolectric: клиент без интерцепторов и с общим
пулом, интерцептор в `ImageLoader`, подстановка текущего адреса, абсолютная
ссылка и не-строка проходят нетронутыми).

**Не сделано / риски:**

- **На устройстве не проверено** (эмулятора в CI нет), и **настоящая картинка
  в этом прогоне не загружалась ни разу**: каталог стенда пуст (issue #53) —
  `nearby`/`search` отвечают `200` с `data: []`. Проверять после наполнения
  каталога, в первую очередь относительные ссылки и http-медиа в release: его
  запретит `network_security_config` (issue #26), и это правильно.
- **Имя поля с аватаром автора отзыва угадано**: `userAvatarUrl` с алиасами
  `avatarUrl` и `userAvatar`. Схема `Response` в `/v3/api-docs` перекрыта
  коллизией springdoc — свериться, когда её разведут (там же issue #76).
- **Фотографий у отзыва нет**: ни поля в контракте, ни загрузки своих файлов.
  `POST media/upload` и `GET media/entity/{entityId}` не трогал — это отдельная
  задача, и вместе с ней приедут настоящие галереи заведений (сейчас в галерее
  ровно то, что отдаёт карточка: обложка и логотип).
- **Задачи бэкенду** (`jack5505/mahalla`): картинка у позиции меню
  (`ItemResponse.imageUrl`) и аватар автора в ответе отзыва — без них два из
  четырёх мест из формулировки issue показывать нечем.
- Скриншот-тестов по-прежнему нет: фоллбэки и скелетоны смотрел глазами по
  `@ThemeLanguagePreviews` (`MahallaImagesPreview`).

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
4. Карта собрана целиком (issue #65): `MapScreen` рисует `MapCanvas`, сеточный
   `MarkerClusterer` удалён. Осталось действие пользователя — секрет
   `MAPKIT_API_KEY` в Actions и проброс его в env шагов сборки; без ключа на
   месте карты объяснение, а не тайлы.
4a. Отчёты о падениях подключены (issue #74) — осталось действие пользователя:
   секрет `SENTRY_DSN` в Actions и проброс его в env шагов сборки. Без него
   сбор выключен, и падения у пользователей по-прежнему невидимы.
5. Вертикаль «Еда» (эпик 5) сделана — сверить контракт `FoodApi` с реальным
   бэкендом и вёрстку с `TZ-ANDROID.md`; дальше остальные вертикали (очередь,
   бронь) и кошелёк (эпик 8), от которого зависит настоящая оплата заказа.
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
