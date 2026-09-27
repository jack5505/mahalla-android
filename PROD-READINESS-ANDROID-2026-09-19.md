# MAHALLA Android — готовность к продакшену (снимок на 2026-09-19)

Оценка по коду `main` (HEAD `52689bb`, merge PR #330), а не по документам. Все пути — относительно
корня репозитория, строки проверены `grep -n`/`sed -n`. Прогоны Gradle выполнены локально 19.09.2026
(JDK 17, AGP 8.7.3, Gradle 8.11.1). Что не удалось проверить — сказано прямо в каждом разделе.

Скоуп релиза: заказы, брони, живая очередь, все девять вертикалей. **Без подписок и без оплаты через
кошелёк, заказы только `paymentMethod=CASH`.** Даты — от бэкенда (jack5505/mahalla#289,
`PROD-READINESS-2026-09-19.md`): закрытый пилот **09–16.10.2026**, публичный запуск **30.10–06.11.2026**.

Трекер: epic jack5505/mahalla-android#353, лейбл `release`, задачи #333–#352 плюс существующие
#17 #148 #160 #165 #174 #175 #176 #177 #191 #197 #213 #230 #232 #287 #305 #309 #318 #319.

---

## 1. Вердикт

| | |
|---|---|
| Можно выкатывать сейчас | **Нет.** Release-сборка неподписанная и без R8; единственный «релизный» workflow публикует debug-APK; кошелёк — таб нижней навигации, оплата по умолчанию `WALLET`; «Заказать» у еды недостижимо из карточки места; нет политики конфиденциальности и удаления аккаунта (требования Play) |
| Готовность под этот скоуп | **~60 %** (средневзвешенно по 11 направлениям, §4). Логика приложения и тесты — 75–85 %, релизная инженерия — 40 %, срез скоупа не начат — 35 % |
| Что снимает сужение скоупа | Из-под удара уходят: оплата кошельком в «Одежде» без PIN и без идемпотентности (`FashionCheckoutViewModel.kt:215-240`), `PaymentConfirmationPolicy`/`WalletPaymentFlow` целиком, тарифы и автопродление подписки, пополнение через Payme/Click/Uzum (`TopUpSheet.kt:82`), закрытые ручки бэкенда #275. Остаются: все девять вертикалей, «Мои активности», бизнес-панель, push, PIN/app-lock |
| Прогон 19.09 | `testDebugUnitTest`: **2853 теста в 245 классах, 0 падений, 0 ошибок, 4 пропуска** (контрактные пробы `assumeTrue`, фикстуры не сняты). `assembleDebug` ✓ (132,8 МБ). `assembleRelease` ✓ → `app-release-unsigned.apk` 121,8 МБ. `bundleRelease` ✓ 62,6 МБ. `lintDebug` ✓ 0 замечаний при `warningsAsErrors` |
| Даты | Пилот 16.10 достижим, если этапы A–B (§8) закрыты к 02.10 и бэкенд закрывает #137/#51/#275 в свои сроки. Публичный запуск 06.11 зависит от закрытого тестирования Play (§9, риск №2) |

---

## 2. Блокеры для этого скоупа

| # | Что | Где | Задача |
|---|---|---|---|
| B1 | **Нет подписи release.** `signingConfigs` отсутствует; `assembleRelease` даёт `app-release-unsigned.apk` (подтверждено прогоном). `docs/RELEASE.md`, на который ссылается `.gitignore:27`, в репозитории нет | `app/build.gradle.kts:137-181` | #336 |
| B2 | **«Релизный» workflow выкладывает debug-APK.** `assembleDebug` в `:64` и `:80`, публикация в GitHub Releases `:87-98`. Debug несёт cleartext на любой хост и доверие user-CA (`app/src/debug/res/xml/network_security_config.xml:23-29`), Chucker, BODY-логи HTTP, экран смены бэкенда, стенд `189-74-96-232.nip.io` по умолчанию. `SENTRY_DSN` не передаётся (`:79-82`). `versionCode = 1` захардкожен, бампа нет | `.github/workflows/release-internal.yml:60-98`, `app/build.gradle.kts:123-124` | #338 |
| B3 | **Кошелёк и подписка не срезаны.** Таб `Wallet` в нижней навигации; `composable<WalletRoute>`; подписка из профиля, кошелька, deep link `mahalla://subscription` и пуша `SUBSCRIPTION_EXPIRES`. Ни одного флага, который бы это выключал | `navigation/BottomNavItem.kt:21`, `navigation/MahallaNavHost.kt:278-284, 519-525, 339, 555`, `feature/profile/ui/ProfileScreen.kt:378-382`, `navigation/DeepLinks.kt:36`, `feature/notifications/domain/NotificationTarget.kt:50` | #333 |
| B4 | **Оплата по умолчанию `WALLET`.** `CheckoutForm(payment = PaymentMethod.Wallet)` в еде и одежде; переключатель «Кошелёк / Наличные» виден пользователю. После #275 у бэкенда такой заказ будет отклонён | `feature/food/domain/Checkout.kt:56, 91-94`, `feature/fashion/ui/checkout/FashionCheckoutContract.kt:36`, `feature/food/ui/checkout/CheckoutScreen.kt:172-184`, `feature/fashion/ui/checkout/FashionCheckoutScreen.kt:198-209` | #334 |
| B5 | **«Заказать» у еды недостижимо.** `PlaceCapabilities.of(category)` ни для одной категории не ставит `ordering = true` → `PlaceActions.resolve` не добавляет `PlaceAction.Order` → переход в `MenuRoute` из карточки (`MahallaNavHost.kt:599`) мёртв. В меню можно попасть только через «повторить заказ» из «Моих активностей». Тест `PlaceActionsTest.kt:75-85` закрепляет это | `feature/place/domain/PlaceDetails.kt:143-151`, `feature/place/domain/PlaceActions.kt:25` | #335 |
| B6 | **Требования Google Play.** Ссылки на политику конфиденциальности в приложении нет (0 совпадений `privacy|конфиденциальн|maxfiylik` в `values*/strings.xml` и коде). Удаления аккаунта нет ни в UI, ни в контракте (`ProfileApi.kt:47-50`, `docs/API-CONTRACT.md:194-205`) | `feature/profile/**`, `docs/API-CONTRACT.md` | #339, #340, #351 |
| B7 | **R8 выключен.** `isMinifyEnabled = false`, `shrinkResources` не задан, `proguard-rules.pro` — только Coil. 53 МБ dex в release; для пилота допустимо, для публичного запуска — нет | `app/build.gradle.kts:164` | #337 |

Иконка лаунчера и splash — временная заглушка «круг» (`res/drawable/ic_launcher_foreground.xml:2-3`,
`themes.xml:11-12`); в магазин с ней не пустят — #230 (замена лежит в незакоммиченной ветке ADR 0013).

---

## 3. Как клиент переживает известные дефекты бэкенда

| Дефект бэкенда | Поведение клиента | Оценка |
|---|---|---|
| #137 push о статусе заказа не приходит | Еда: опрос `GET orders/{id}` раз в 5 с, стоп на финальном статусе и при `ON_STOP` (`OrderStatusViewModel.kt:77-101`). Одежда, брони, кино, больница, игровые, мастера: обновление только на resume/pull-to-refresh. **Очередь: единственный канал — пуш**; без него человек не узнаёт решение мастера, пока не откроет центр уведомлений (`QueueViewModel.kt:24-31`, `WalkInTicketStore.kt:78-104`) | Деградирует корректно везде, кроме очереди — там **зависание в неизвестности**, #342 |
| #52 WebSocket без JWT | Клиент **не использует WebSocket вообще** (grep `WebSocket|newWebSocket|socket.io` по `app/src/main/java` пуст) | Не влияет |
| #51 нет `GET /walkin/my` | Клиент его не вызывает: `WalkInApi.kt:35-44` — только `send`/`cancel`. «Мой талон» живёт в DataStore устройства с TTL 2 мин на позицию / 12 ч на талон (`WalkIn.kt:103-123`). Очередь не входит в «Мои активности» (`Activity.kt:22-28`) | Не падает; талон теряется при смене устройства/переустановке, #342 #287 |
| #256 `places/my` → 500 вместо 401 | `apiCall` → `ApiError.Http(500)` → «ошибка сервера, повторить» (`ProviderRepository.kt:123-124`, `MyPlacesViewModel.kt:103-106`, `BusinessDashboardViewModel.kt:85-97`). Refresh не запускается — `TokenAuthenticator` реагирует только на 401 | Не падает, но истёкшая сессия выглядит как сломанный сервер, #344 |
| #254 формат времени разный | Один набор парсеров на 23 маппера: `ServerLocalTime.kt:24-54` принимает `"HH:mm[:ss]"` и объект `{hour,minute}`; `ServerInstant.kt:56-68` — ISO с `Z` и naive `LocalDateTime`, иначе `null` (элемент без даты). Смещение `+05:00` → `null` (#176). Отправка идёт четырьмя форматами (`ServerLocalTime.kt:57-62`, `HospitalRepository.kt:131`, `GamingMappers.kt:99-109`, `FreelancerMappers.kt:129`) | Не падает; при унификации бэкенда в `OffsetDateTime` даты исчезнут — #176 |
| #249 тело как `Map` | Только бизнес-ручки: `BusinessApi.kt:73, 89` (accept/decline — **пустой объект**), `:133` (ключ `status` выведен), `PharmacyApi.kt:88` (`stockQuantity` выведен, не подтверждён), `FashionApi.kt:155-160`. Клиентские потоки не затронуты | Риск 400 или молчаливой потери данных в бизнес-панели; проверить curl'ом под токеном владельца |

Общее: `apiCall` (`core/result/ApiResult.kt:53-89`) переводит `SocketTimeout`, envelope `success:false`,
`HttpException`, `SerializationException`, `IOException` в `ApiResult`; `CancellationException`
пробрасывается. Все DTO-поля nullable, `ignoreUnknownKeys=true` (`NetworkFactory.kt:60-65`), у всех
статус-enum есть `Unknown`. Крашей от несовпадения схемы в коде не найдено.

---

## 4. По направлениям

Проценты — оценка готовности направления под этот скоуп. Серьёзности: BLOCKER / HIGH / MEDIUM / LOW.

### 4.1. Auth-флоу — 70 %

Как устроено: welcome → телефон (+998, белый список кодов операторов, `PhoneNumberValidator.kt:36-67`) →
OTP (таймер с сервера, clamp 0..600 с, `OtpChallenge.kt:60,80-82`) → **серверный** 6-значный PIN
(`auth/setup-pin` / `auth/pin-login`, `AuthApi.kt:233-241`; токены выдаются только после PIN) →
биометрия (`BIOMETRIC_WEAK`, `BiometricAvailability.kt:62`) → гео/город. Локальная копия PIN: PBKDF2-SHA256
120k итераций + соль, шифрование AES-GCM ключом AndroidKeyStore, сравнение константное по времени
(`PinHasher.kt:20-39`, `PinCipher.kt:59-72`). App-lock: `ProcessLifecycleOwner`, grace 30 с, холодный
старт = lock, защита от перевода часов (`AppLockManager.kt:65-106`); 5 попыток с персистентным счётчиком →
logout (`PinAttemptStore.kt:52-59`, `AppLockViewModel.kt:201-281`). Refresh — OkHttp `Authenticator`
через отдельный `@RefreshClient` без интерсепторов, `synchronized`, `MAX_ATTEMPTS = 2`; сессия стирается
только при 401 на сам refresh или после трёх «неоднозначных» провалов с интервалом ≥10 с
(`TokenAuthenticator.kt:82-202, 231-257`). Истечение → `SessionExpiry` → `RootViewModel` →
`SessionExpiryEffect` → welcome с очисткой стека (`MahallaApp.kt:124-135`). Logout — `POST auth/logout` с
`X-Session-Id` (`AuthApi.kt:265-269`); отзыв чужих сессий — `auth/sessions/revoke`.

- **HIGH** `data/prefs/SessionStore.kt:78-83` (+ `PreferenceKeys.kt:83-88`, `DataStoreModule.kt:41-46`) — access/refresh-токены и `session_id` в Preferences DataStore **открытым текстом**, хотя рядом PIN-хэш шифруется Keystore. Из бэкапа файл исключён. Issue #319, открыт PR #326 — домержить.
- **MEDIUM** `feature/auth/data/AuthRepository.kt:525-546`, `TokenAuthenticator.kt:120-132` — logout и expiry не чистят Room (`clearAllTables` в проекте не вызывается), FCM-токен и (при expiry) профиль. #341.
- **MEDIUM** `feature/onboarding/ui/PinViewModel.kt:229-235` — счётчик попыток онбордингового PIN только в state ViewModel: убить процесс — снова 5 попыток. #346.
- **MEDIUM** `TokenAuthenticator.kt:88-96` — сценарий «N параллельных 401 → один refresh» не покрыт тестом (в `NetworkStackTest.kt` только одиночные цепочки и burst ambiguous).
- **LOW** `AppLockViewModel.kt:148-153`, `BiometricAvailability.kt:62` — `BIOMETRIC_WEAK` без `CryptoObject`. Issue #318, PR #325.
- **LOW** `navigation/Routes.kt:61-64` — `otpToken` и `phone` едут аргументами маршрута (SavedState). Токен короткоживущий.
- **LOW** `data/location/RequestLocationProvider.kt:63-65` + `AuthRepository.kt:140-149, 195-206, 278-289` — координаты (или fallback-город) уходят в send-otp/verify/pin-login **до** экрана гео-разрешения. Учесть в Data Safety (#339).
- **LOW** `SessionStore.kt:23` — `expiresAtEpochSeconds` сохраняется, но не используется для проактивного refresh.

Тесты: `NetworkStackTest` 30 (MockWebServer на production-конфиге), `AuthRepositoryTest` 51,
`PinViewModelTest` 25, `OtpViewModelTest` 21, `RootViewModelTest` 15, `KeystorePinStorageTest` 11,
`AppLockManagerTest` 13, `AppLockViewModelTest` 24, `SecurityRepositoryTest` 19, `SessionExpiryEffectTest`.

### 4.2. Сеть — 80 %

Конверт `ApiResponse` с опциональными полями, `payload()` кидает `ApiEnvelopeException` при
`!success || data == null` (`ApiResponse.kt:19-54`). Тело ошибки разбирается как JSON-дерево, HTML от
nginx/стектрейс/пустое тело не попадают в текст (`ServerErrorParser.kt:25-70, 105-108`). Гео-заголовки
`X-Geo-Lat/Lng` с кэшем 60 с и безусловным fallback на город → Ташкент (`GeoHeaderInterceptor.kt:51-62`,
`RequestLocationProvider.kt:58-69`). `Accept-Language` из `Locale.getDefault()`. Таймауты connect 15 /
read 30 / write 30 с (`NetworkFactory.kt:56-58`). Cleartext запрещён, исключения loopback/10.0.2.2
(`res/xml/network_security_config.xml:15-21`); debug перекрывает своим конфигом. TLS-пин — только под
`BACKEND_URL_OVERRIDE` и только после отказа платформы (`PinnedCertificateTls.kt:133-142`); настоящего
`CertificatePinner` на `api.mahalla.uz` нет. Chucker — `no-op` в release; экран адреса, сырое тело ошибки,
пин — за флагом `BACKEND_URL_OVERRIDE`, в release `false` (`build.gradle.kts:168-172`).

- **HIGH** `feature/discovery/data/CatalogRepository.kt:325` — `GONE_ERRORS = setOf(NotFound, Forbidden)`: любой 403 (гейт вертикали, `GEO_*`) удаляет место из Room-кэша (`:191-192, :219-220`). #344.
- **HIGH** `core/result/ApiError.kt:17-18`, `core/ui/ApiErrorMessages.kt:19, 42` — нет типа «вертикаль отключена»/`GEO_*`; код `GEO_PERMISSION_REQUIRED` не проверяется нигде. Пользователь видит «Нет доступа к этому действию» с бесполезным «повторить». #344.
- **HIGH** `feature/role/data/ProviderRepository.kt:123-124` — #256 не компенсируется (см. §3). #344.
- **MEDIUM** `BusinessApi.kt:73, 89, 133`, `PharmacyApi.kt:88` — тела-`Map` с выведенными или пустыми ключами (#249). Проверить curl'ом под `CONTRACT_REFRESH_TOKEN`.
- **MEDIUM** `NetworkFactory.kt:78-81` — нет `callTimeout`; медленный сервер, отдающий байты по чуть-чуть, держит запрос неограниченно. #345.
- **MEDIUM** `ApiResult.kt:85-86` — все `IOException` → `NoConnection`: ошибка TLS на проде выглядит как «нет сети» и не попадает в Sentry. #346.
- **MEDIUM** `ConnectivityManager` не используется — офлайн только для мест через Room (`CatalogRepository.kt:280-294`, плашка `state_offline_cache`); остальные экраны узнают об офлайне через 15 с. #350.
- **LOW** `NetworkFactory.kt:78` — `retryOnConnectionFailure` по умолчанию `true`; идемпотентные ключи только у еды (`OrderRepository.kt:42`). #345.
- **LOW** `ServerInstant.kt:49-51` — смещение `+05:00` → `null`. #176.
- **LOW** `GeoHeaderInterceptor.kt:76`, `TokenAuthenticator.kt:96-113` — `runBlocking` на потоке OkHttp (обосновано, кэш 60 с смягчает).
- **LOW** `AnalyticsApi.kt:31` — `lat/lng` из схемы не отправляются; не определено, обязательны ли они.

Тесты: `ApiResultTest` 10, `ServerErrorParserTest` 10, `ApiResponseTest` 5, `NetworkStackTest` 30,
`NetworkClientsTest` 10, `GeoHeaderInterceptorTest` 6, `LanguageHeaderInterceptorTest` 4,
`PinnedCertificateTlsTest` 10, `BackendUrl*Test`, `SecretBodyLoggingTest` 4, `ContractSampleTest` 5.
Нет тестов: гейт 403/`GEO_*`, `places/my` на 500, `ServerLocalTime` напрямую.

### 4.3. Экраны и навигация — 80 % (с учётом скоупа)

66 назначений графа, все — настоящие экраны; заглушек «в разработке»/TODO/stub нет (grep пуст).
Состояния — через `ScreenState`/`ScreenStateHost` (`core/ui/state/ScreenState.kt`,
`core/ui/components/ScreenStates.kt:206-224`), «пустой список вместо ошибки» невозможен по построению.
Табы: Discovery / «Активности» / **Wallet** / Profile (`BottomNavItem.kt:19-22`), переключение с
`saveState/restoreState` (`MahallaApp.kt:193-198`). Deep links: `mahalla://place/{id}`, `order/{id}`,
`notifications`, `subscription` (`MahallaNavHost.kt:591, 923, 547, 519`; манифест `:104-113`).
Logout и смерть сессии чистят стек до welcome; вход после PIN — `popUpTo(OnboardingGraph)`.

Полная таблица «маршрут → файл → статус → L/E/Err» — в приложении A. Итог: 60 экранов full, из них
3 недостижимы (меню/корзина/чекаут еды — B5), 2 вне скоупа (кошелёк, подписка), 0 каркасов, 0 отсутствующих
из графа. Расхождения с макетом B «Focus»: нет финального экрана онбординга «Готово» (после гео сразу
`RoleRoute`, `MahallaNavHost.kt:235`); нет кнопки «Панель бизнеса» в профиле (вход через «Мои заведения»).

- **BLOCKER** B5 — «Заказать» у еды. #335.
- **BLOCKER** B3 — таб кошелька и входы в подписку. #333.
- **HIGH** нет UI для 403 гейта сервисов (см. 4.2). #344.
- **MEDIUM** `MahallaNavHost.kt:923` — `OrderStatusRoute` на корневом уровне вне `MainGraph`: cold-start по deep link у невошедшего → 401 → `sessionExpired`; гейты `needsBackendUrl`/`showUpdate` обходятся (#160). #343.
- **MEDIUM** `MahallaNavHost.kt:920-933`, `NotificationTarget.kt:53-55` — `mahalla://order/{id}` всегда открывает экран еды, а `ORDER_STATUS_UPDATED` приходит и для CLOTHING/PHARMACY. #343.
- **LOW** `MovieScreen.kt:177`, `TicketScreen.kt:97`, `AppointmentScreen.kt:95`, `FreelancerProfileScreen.kt:174` — `is ScreenState.Empty -> Unit` (пустой экран без текста, если VM когда-нибудь отдаст Empty).
- **LOW** `FashionProductScreen.kt:104`, `DoctorBookingScreen.kt:260`, `BookingScreen.kt:357`, `FashionCheckoutScreen.kt:114` — пустое состояние голым `Text`, а не `EmptyState`.
- **LOW** `CartScreen.kt:79`, `NotificationSettingsScreen.kt` — нет error/loading (данные локальные, приемлемо).

### 4.4. Заказы, брони, живая очередь — 70 %

| Вертикаль | create | track | cancel | Итог |
|---|---|---|---|---|
| Еда | `POST food/orders` + `Idempotency-Key` (`FoodApi.kt:78-82, 144-152`); default **WALLET** | опрос 5 с (`OrderStatusViewModel.kt:77-101`) | `NEW`/`ACCEPTED`, подтверждение (`Order.kt:117-118`) | create+track+cancel, но **недостижима** из карточки (B5) |
| Одежда | `POST fashion/orders`, состав из серверной корзины (`FashionApi.kt:95-96`); default WALLET; **без** `Idempotency-Key` | список `GET orders?vertical=CLOTHING` на resume/refresh | `POST fashion/orders/{id}/cancel` | create+track(список)+cancel |
| Аптека | заказа нет — витрина (`PharmacyApi.kt:43-90`); в «Активностях» `PharmacyOrder` некликабелен (`ActivityMappers.kt:64-68`) | — | — | витрина |
| Мастера | `POST freelancers/{id}/orders`, без paymentMethod (`FreelancerApi.kt:85-89`) | список `freelancers/orders/my` | **нет** (статус меняет только исполнитель, `:118-122`) | create+track(список) |
| Бронь | `POST appointments` (`BookingApi.kt:52-53`) | `GET appointments/my` + карточка | cancel + перенос «new → cancel old» под `NonCancellable` (`BookingRepository.kt:178-209`) | полный |
| Больница | `POST hospitals/appointments` (`HospitalRepository.kt:126-135`) | список + карточка, имя врача дотягивается отдельно (`:181-201`) | `POST …/cancel` (`:216-235`) | полный |
| Кино | `POST cinema/sessions/{id}/buy` (`CinemaApi.kt:70-74`) | `GET cinema/tickets/my`, `/{id}` | `PUT cinema/tickets/{id}/cancel` | полный |
| Игровые зоны | `POST gaming/bookings` (`GamingApi.kt:60-61`) | список | **ручки нет** (`GamingApi.kt:29-73`) | create+track(список) |
| Очередь | `POST walkin/send` (`WalkInApi.kt:35-36`) | **нет** — DataStore с TTL | `POST walkin/{id}/cancel`, запрещено в `InChair` | create+cancel, статус только пушем |

«Мои активности»: пять источников параллельно (`ActivityRepository.kt:140-194`), отказ одного помечает
раздел с «повторить», `Error` только если упали все (`ActivityFeed.kt:41-42`, `ActivityViewModel.kt:112-135`).
Очередь и заказы мастеров в таб **не входят** (`Activity.kt:22-28`). Двойной тап защищён во всех чекаутах.

- **HIGH** `feature/fashion/ui/checkout/FashionCheckoutViewModel.kt:215-240` — `WALLET` без PIN и без идемпотентности. **Снимается срезом скоупа** (#334).
- **HIGH** `CheckoutViewModel.kt:272-275`, `WalletPaymentFlow.kt:250-253` — при `Timeout`/`NoConnection` после отправки нет «заказ мог быть создан»; `Idempotency-Key` бэкендом не подтверждён (`FoodApi.kt:69-76`). #345.
- **HIGH** `QueueViewModel.kt:24-31`, `WalkInApi.kt:24-30` — очередь без отслеживания (§3). #342.
- **MEDIUM** `Activity.kt:22-28` — «Активности» без заказов мастеров и талонов. #287.
- **MEDIUM** `GamingApi.kt:29-73`, `FreelancerApi.kt:118-122` — нет отмены у клиента (ручки бэкенда). В UI явно писать «отменить можно через заведение».
- **LOW** `OrderStatusViewModel.kt:81-99` — `OrderStatus.Unknown` не финальный → опрос каждые 5 с бесконечно, прогресс не рисуется.
- **LOW** `FashionOrderRepository.kt:97-103`, `GamingRepository.kt:92-97`, `FreelancerMappers.kt:99` — ответ создания без `id` считается успехом с пустым id; у еды — наоборот, отказ. Единообразия нет.
- Не удалось определить из кода: поддерживает ли стенд `Idempotency-Key`; реальную форму FCM-payload.

Тесты: `ActivityRepositoryTest` 32, `ActivityViewModelTest` 38, `WalkInRepositoryTest` 13,
`QueueViewModelTest` 17, `OrderStatusViewModelTest` 13, `CheckoutViewModelTest` 26,
`FashionCheckoutViewModelTest` 16, репозитории booking/hospital/cinema/gaming/freelancer.

### 4.5. Push (FCM) — 55 %

Отдельной ручки `POST/PUT notifications/device-token` **нет ни у клиента, ни у бэкенда**
(`PushTokenRegistrar.kt:24-43`, `docs/API-CONTRACT.md:822-827`): токен уходит полем `fcmToken` в
`AuthDeviceInfo` при send-otp/verify/pin-login/refresh (`DeviceInfoProvider.kt:60`,
`TokenAuthenticator.kt:102`). `onNewToken` → только DataStore (`PushTokenRegistrar.kt:61-65`).
`PUSH_ENABLED` — от наличия `google-services.json` (`build.gradle.kts:24-32`); без него `token()` → `null`,
NPE-путей не найдено. Каналы: Orders/Queue/Bookings/Payments `IMPORTANCE_DEFAULT`, Marketing/Other `LOW`
(`NotificationChannels.kt:146-156`). Payload `id/type/entityId/title/body` (`PushMessage.kt:69-73`),
цели: `ORDER_*` → `mahalla://order/{id}`, `SUBSCRIPTION_EXPIRES` → подписка, всё остальное (включая
`WALKIN_*`) → центр уведомлений (`NotificationTarget.kt:49-59`). `POST_NOTIFICATIONS` — не на старте, а
карточкой в центре/настройках, после отказа → системные настройки (`NotificationPermission.kt:60-129`).
Центр: `GET notifications` + `unread-count` на открытии и resume, polling нет; read с оптимистичным откатом.

- **HIGH** `AuthRepository.kt:525-546` — logout не удаляет FCM-токен ни локально, ни `deleteToken()`; следующий пользователь на устройстве получает чужие пуши до перепривязки. #341.
- **MEDIUM** `AndroidManifest.xml:73-78` — сообщения с блоком `notification` в фоне показывает SDK: канал `other` LOW, без `PushGate` и deep link. Договориться о data-only. #349.
- **MEDIUM** `PushTokenRegistrar.kt:61-65` — ротация токена доедет до сервера лишь с ближайшим refresh. #349.
- **MEDIUM** `NotificationChannels.kt:146-151` — канал Queue `IMPORTANCE_DEFAULT`, без heads-up «ваша очередь подошла». #342.
- **MEDIUM** deep link заказа не различает вертикаль и обходит гейты (см. 4.3). #343.
- **LOW** `NotificationsBadgeViewModel.kt:46-69` — бейдж только на init/resume; foreground-push не обновляет открытые экраны (`MahallaMessagingService.kt:58-70`).

Тесты: `PushTokenRegistrarTest` 6, `PushGateTest` 10, `PushMessageTest` 11, `NotificationTargetTest` 7,
`NotificationsViewModelTest` 21. Нет: `PushNotifier`, `NotificationChannels`, `MahallaMessagingService`,
`MainActivity.onNewIntent`, cold-start deep link.

### 4.6. Геолокация — 85 %

Только `ACCESS_COARSE/FINE_LOCATION`, без `BACKGROUND` (`AndroidManifest.xml:9-10`); фоновых сервисов и
WorkManager нет. Онбординг: объяснение → запрос обеих точностей → при отказе выбор города
(`GeoScreen.kt:54-76`, `GeoViewModel.kt:29-33`). Координаты — только `getLastKnownLocation`
(`RequestLocationProvider.kt:88-102`), без активных апдейтов; fallback город → Ташкент делает 403 `GEO_*`
практически недостижимым. MapKit — ленивая инициализация, без ключа `MissingApiKey`, `LinkageError` не
роняет экран (`MapKitInitializer.kt:78-121`). MapView — `onStart/onStop` по lifecycle (`YandexMapCanvas.kt:106-127`).

- **MEDIUM** `YandexMapCanvas.kt:123-126` — `onDispose` без `onStop()` при уходе навигацией. #348.
- **MEDIUM** `GeoScreen.kt:54-60`, `MapScreen.kt:73-92` — нет обработки «Больше не спрашивать», нет пути в настройки. #348.
- **MEDIUM** `RequestLocationProvider.kt:93-100` — `lastKnown` без ограничения возраста. #348.
- **LOW** `GeoHeaderInterceptor.kt:55` — провайдер упал + пустой кэш → запрос без заголовков → 403. #348.
- **LOW** `MapModule.kt:39-41` — локаль MapKit фиксируется при создании singleton.
- Не определено: точность `MapKit.requestSingleUpdate` — параметров у API нет.

Тесты: `RequestLocationProviderTest` 4, `GeoViewModelTest` 10, `MapKitInitializerTest` 7, `MapViewModelTest` 40.

### 4.7. Локализация uz/ru — 90 %

`values/strings.xml` 1143 / `values-ru/strings.xml` 1141 (разница — два `translatable="false"`); ключей,
отсутствующих в одной локали, нет; plurals 34/34 (uz `one/other`, ru `one/few/many/other`). Паритет закреплён
`StringResourceParityTest`. Пользовательских литералов в коде нет (только preview и `error()`). 95 вызовов
plurals, конкатенаций «N мин» нет. Даты `dd.MM.yyyy`/`HH:mm`, `Locale.ROOT`, `Asia/Tashkent`
(`DateTimeFormatters.kt:23-26`). Деньги: тийины → сумы один раз в мапперах (`Money.kt:38-41`, симметричное
округление, `multiplyExact`), формат `#,##0` с NBSP и `currency_uzs` (`MoneyFormatter.kt:28-41`), ноль и
отрицательные покрыты тестами, `TabularNums` на суммах. `MissingTranslation` не отключён.

- **LOW** `core/locale/LocaleContextWrapper.kt:18` — `Locale.setDefault` процесс-глобально; от неё зависят `LanguageHeaderInterceptor.kt:41` и `MapModule.kt:40`.
- **LOW** `MoneyFormatter.kt:41` — ASCII «-» вместо «−» из макета (актуально только с кошельком).
- **LOW** `NotificationSettings.kt:71` — `"%02d:%02d".format` без `Locale.ROOT`.
- **LOW** `CinemaPieces.kt:41` — `uz-Cyrl` тоже считается uz (вероятно, желаемое).
- Не проверено: приходят ли `serverMessage` бэкенда на языке `Accept-Language`.

### 4.8. Безопасность — 55 %

Гигиена образцовая: единственный `Log.d` под `BuildConfig.DEBUG` (`AnalyticsTracker.kt:69-71`);
`HttpLoggingInterceptor` только в debug, `Authorization` редактируется, тела auth/pin не логируются даже в
debug (`NetworkFactory.kt:43-54, 102-118`, `SecretBodyLoggingTest`); Chucker `no-op` в release с редактированием
`Authorization/Cookie`; секретов в git нет (`google-services.json`, `*.jks`, `local.properties` в
`.gitignore`, история по паттернам AIza/DSN чистая); Sentry `sendDefaultPii=false`, без скриншотов,
`beforeSend`/`beforeBreadcrumb` через `CrashScrubber` + `SecretScrubber` (JWT/Bearer/PIN/OTP), IP обнуляется
(`SentryCrashReporter.kt:48-68`, `SecretScrubber.kt:57-69`); auto-init выключен. Экспорт: только `MainActivity`,
FCM-сервис `exported=false`; `debuggable` не выставлен. WebView в проекте нет. Буфер обмена для OTP не используется.

- **BLOCKER** B2 — релизный workflow публикует debug-APK. #338.
- **HIGH** B1 — нет `signingConfigs`. #336.
- **HIGH** B7 — R8 выключен. #337.
- **HIGH** токены открытым текстом (см. 4.1). #319 / PR #326.
- **MEDIUM** `AndroidManifest.xml:46-48` — `allowBackup="true"`; из бэкапа исключён только `datastore/` (`backup_rules.xml:9-11`); Room-БД с заказами/корзиной уходит в облако. #346.
- **MEDIUM** `MainActivity.kt:161-169` — `FLAG_SECURE` только в `onPause`; скриншот в foreground на PIN/OTP разрешён; авторы сами отмечают, что порядок на устройстве не проверен (`:157-159`). #346.
- **LOW** `AndroidManifest.xml:104-113`, `DeepLinks.kt:17-36` — `mahalla://` без App Links/`autoVerify`; аргументы используются только как id в API. `PlaceRoute` достижим до логина (`MahallaNavHost.kt:78-79`) — UX-край, не уязвимость.
- **LOW** `NetworkModule.kt:91,127` — в release `certificatePin = null`, пиннинга на `api.mahalla.uz` нет.
- **LOW** `build.gradle.kts:130-131` — `MAPKIT_API_KEY`/`SENTRY_DSN` в `BuildConfig` (норма; ограничить ключ MapKit по package/SHA).
- **LOW** `BackendUrlScreen.kt:195-218`, `strings.xml:26` — адрес стенда в preview и placeholder.
- Не удалось проверить: порядок «snapshot Recents vs onPause» на реальных прошивках; достаточность consumer-rules при R8 — только сборкой.

### 4.9. Сборка и релиз — 40 %

`versionCode = 1`, `versionName = "0.1.0"` (`build.gradle.kts:123-124`). buildTypes: release `minify=false`,
debug без `applicationIdSuffix` (debug и release не встанут рядом). Flavors нет. `API_BASE_URL` debug
`https://189-74-96-232.nip.io/api/v1/`, release `https://api.mahalla.uz/api/v1/`. CI (`ci.yml:45`): на PR
и push в main `assembleDebug testDebugUnitTest lintDebug`, lint с `abortOnError`+`warningsAsErrors`; job
`emulator` для `connectedDebugAndroidTest` при **отсутствующем** `androidTest` (`ci.yml:61-93`).
`release-internal.yml` — источник тега `internal-0.1.0-f3054f2-26` (`:74`), собирает debug (B2). Sentry:
ручная инициализация (`MahallaApplication.kt:49`), `environment = BUILD_TYPE`, `release = uz.mahalla@0.1.0+1`
(`CrashModule.kt:29-34`), `tracesSampleRate = 0.0`. Аналитика — только свой бэкенд `POST analytics/track`,
Firebase Analytics не подключён. In-app update — своя проверка `POST app/version/check` с бюджетом 3 с
(`AppUpdateGate.kt:84`), переход в Play по `market://`. minSdk 26 / target 35 / compile 35, portrait,
Core SplashScreen + edge-to-edge. Иконка adaptive + monochrome, но заглушка. `bundle.language.enableSplit
= false` осознанно. Версии: AGP 8.7.3, Gradle 8.11.1, Kotlin 2.0.21, Compose BOM 2024.12.01, Hilt 2.52,
OkHttp 4.12, Retrofit 2.11, Room 2.6.1, Sentry 7.14.0, MapKit 4.42.0-lite, Firebase BOM 33.7.0.

Размеры (прогон 19.09, без R8): debug APK 132,8 МБ; release APK 121,8 МБ, из них `classes*.dex` 53 МБ и
`libmaps-mobile.so` для четырёх ABI 103 МБ (arm64 26,5 МБ); AAB 62,6 МБ. Загрузка на устройство из AAB —
ориентировочно 25 МБ natives + 53 МБ dex до R8. Лимит Play 200 МБ формально проходит.

- **BLOCKER** B1, B2, B6, B7 (см. §2). #336 #338 #339 #340 #337.
- **HIGH** `build.gradle.kts:123-124` — `versionCode` без автобампа. #338.
- **HIGH** `release-internal.yml:79-82` — `SENTRY_DSN` не передаётся. #338.
- **MEDIUM** `ic_launcher_foreground.xml:2-3`, `themes.xml:11-12` — иконка-заглушка. #230.
- **MEDIUM** `build.gradle.kts:140` — нет `applicationIdSuffix` у debug. #338.
- **MEDIUM** `themes.xml:4-8` — фон окна жёстко тёмный, `values-night` нет → тёмная вспышка в светлой теме. #346.
- **MEDIUM** `ci.yml:61-93` — job `emulator` без тестов. #347.
- **MEDIUM** `release-internal.yml:63-64` — дублирующий `assembleDebug`. #338.
- **LOW** Sentry 7.14.0 при 8.x; `GradleDependency` отключён, Dependabot нет.
- **LOW** `AnalyticsModule.kt:36-39` — аналитика с токеном пользователя без согласия: заполнить Data Safety честно (#339).
- Не удалось: измерить размер после R8 (не включён); проверить Play-аккаунт и листинг.

### 4.10. Тесты — 80 %

**Прогон 19.09.2026** (`./gradlew testDebugUnitTest assembleDebug`, 2 мин 14 с, BUILD SUCCESSFUL):

| Показатель | Значение |
|---|---|
| Классов с результатами | 245 |
| `@Test` выполнено | **2853** |
| Падений / ошибок | **0 / 0** |
| Пропущено | 4 — `SecurityContractTest`, `BookingContractTest`: `assumeTrue("проба не снята — запусти contract/*.sh")` |
| Чистый JUnit | 195 классов / ~2264 тестов |
| Robolectric (`sdk=34`) | 47 классов / 589 тестов |
| Compose UI (`createComposeRule`) | 4 файла / 26 тестов |
| Instrumentation (`androidTest`) | **0 файлов** |
| `@Ignore`, закомментированные, пустые, `assertTrue(true)` | **0** |
| MockWebServer / `runTest` / `MainDispatcherRule` | 65 / 126 / 60 файлов; все 61 `*ViewModelTest` под `MainDispatcherRule` |
| mockk / mockito | нет; 62 класса `Fake*` |

CI гоняет `testDebugUnitTest` на каждом PR и push в main без `ignoreFailures`. `AGENTS.md` говорит «2061 в
190 классах» — устарело.

Покрытие по пакетам (main / test / `@Test`): activity 10/5/100; auth 8/6/89; booking 17/8/107;
business 22/8/119; cinema 20/6/94; discovery 24/11/169; fashion 25/11/104; food 26/11/160;
freelancer 23/9/134; gaming 11/4/55; hospital 8/3/67; map 17/11/110; media 11/3/26;
notifications 23/10/90; onboarding 31/9/121; pharmacy 9/4/58; place 7/4/97; profile 10/5/62;
queue 9/4/57; role 29/13/142; security 14/7/93; subscription 13/7/112; wallet 15/6/82;
data/network 26/19/130; core/ui 30/9/55.

- **HIGH** `app/src/androidTest` отсутствует; Robolectric на sdk 34 не ловит targetSdk 35 (edge-to-edge, разрешения). #347.
- **MEDIUM** Compose UI 26 тестов на 60+ экранов; PIN, чекаут, app-lock без UI-тестов. #347.
- **MEDIUM** `AppLockObserver.kt:27-55`, `MahallaMessagingService.kt:40-70`, `DeviceInfoMapper`, `ActivityMappers`, `CartDraftDao` — без прямых тестов. #347.
- **LOW** `AnalyticsTrackerTest.kt:101-105` — единственный `Thread.sleep`, возможен флак.
- **LOW** `AGENTS.md` — цифры устарели.

### 4.11. Качество кода — 85 %

533 файла, ~79 000 строк. TODO/FIXME — **0**; `GlobalScope` — 0; пустых `catch` — 0;
`allowMainThreadQueries` — 0; `mutableStateOf` в ViewModel — 0 (весь стейт через `MutableStateFlow` в
`core/ui/Mvi.kt`); 125 `collectAsStateWithLifecycle`, 0 `collectAsState()`; 72 `LifecycleEventEffect`;
все 29 `items(` в `LazyColumn` с `key`; все 59 `*Route` зарегистрированы; `@Suppress` — 0, `@SuppressLint`
— 5 с обоснованием; lint без baseline; `Clock` через DI; ViewModel без `Context` (0).

- **HIGH** страничная ViewModel скопирована **16 раз** (`grep -rl "private var loadMoreJob"`); `GamingBookingsViewModel.kt:52-135` ≡ `MyFreelancerOrdersViewModel.kt:53-137` (74 из 111 строк дословно); в `core/paging/` только `PageBounds.kt`. Каждая правка догрузки — 16 правок (issue #53, #145, #209 уже были). #352, после релиза.
- **MEDIUM** `MainActivity.kt:205` — `runBlocking` DataStore в `attachBaseContext` на главном потоке; `runCatching` (`:200`) молча отдаёт дефолт. #346.
- **MEDIUM** `core/ui/components/TextFields.kt:130-135` — backwards write в композиции. #352.
- **MEDIUM** неленивые `forEach`-списки серверных данных: `DoctorBookingScreen.kt:174, 277`, `BookingScreen.kt:181, 380`, `MovieScreen.kt:303`, `FreelancerProfileScreen.kt:287, 393`, `MyServicesScreen.kt:353`, `BusinessMenuScreen.kt:207`. #352.
- **MEDIUM** 15 копий страничного DTO при существующем `PageDto<T>` (`CatalogApi.kt:134`). #352.
- **MEDIUM** `core/ui/components/ScreenSkeleton.kt` — мёртвый файл (0 ссылок в main и test). #352.
- **LOW** `Dispatchers.IO` захардкожен в 6 местах; свои `CoroutineScope` без отмены в `AppLockObserver.kt:35`, `AnalyticsModule.kt:59` (живут с процессом, документировано).
- **LOW** `Mvi.kt:37-38` + 46 экранов — коллектор эффектов не lifecycle-aware (`repeatOnLifecycle` был бы каноничнее).
- **LOW** `runCatchingCancellable` без `reportSwallowed`: `WalletPaymentFlow.kt:214, 271`, `WalkInTicketStore.kt:160-161` (битый JSON талона → «талонов нет» без телеметрии). #352.
- **LOW** `!!` — 5 мест в `feature/place` за инвариантом `isDayOff`; `valueOf` — 3 места на локальных значениях; 7 копий `fromApi`; 22 однотипных `*DataModule`.

---

## 5. Сделано хорошо

- **Сеть и ошибки.** Единая точка маппинга исключений с правильным порядком `catch` и пробросом отмены (`core/result/ApiResult.kt:53-89`); разбор тела ошибки как дерева с защитой от HTML/стектрейсов (`ServerErrorParser.kt`); текст сервера доезжает до пользователя, ресурсы — только fallback. Refresh: один поток, лимит попыток, стирание сессии только при явном 401, защита от подмены прокси и вспышек 401 — 30 тестов на MockWebServer.
- **Секреты и логи.** Ни `Timber`, ни `println`, ни `printStackTrace` в main; тела auth/pin не логируются даже в debug; Chucker разведён по `debugImplementation/releaseImplementation`; секретов в git и истории нет; Sentry с двумя скрабберами и без PII. Всё дебажное — за одним флагом `BACKEND_URL_OVERRIDE`, в release выключено.
- **PIN и app-lock.** Серверный PIN + локальная копия под PBKDF2 120k и Keystore AES-GCM, константное сравнение, персистентный счётчик попыток, защита от перевода часов, «холодный старт = lock».
- **Устойчивость к схеме.** Все DTO nullable, `ignoreUnknownKeys`, `Unknown` у всех enum, один набор мягких парсеров дат/времени на 23 маппера, `PageBounds` против зацикливания пагинации. Ни одного места, где несовпадение схемы уронит приложение.
- **UI-архитектура.** Единый MVI (`core/ui/Mvi.kt`), `ScreenState` с невозможным «пустой список вместо ошибки», 0 `collectAsState()`, все `LazyColumn` с ключами, типизированные маршруты с тестом на deep-link плейсхолдеры, корректная очистка стека при logout/expiry/входе.
- **«Мои активности».** Частичный отказ источников деградирует по разделам, догрузка пустой вкладки с потолком `MAX_DRAIN_PAGES` и дедупликацией (`ActivityViewModel.kt:221-283`).
- **Еда.** Polling со стопом на финале и в фоне, ошибка опроса не стирает заказ, отдельные ключи идемпотентности для кошелька и наличных, повтор после отказа с тем же ключом.
- **Бронь.** Перенос как «новая запись → `NonCancellable` отмена старой»: обрыв не оставляет без записи.
- **Гео.** Только foreground, `lastKnown` без активных апдейтов, `SecurityException` закрыт, безусловный fallback на город; MapKit переживает отсутствие ключа и `LinkageError`.
- **Локализация.** Паритет uz/ru и plurals закреплён тестом; деньги пересчитываются в одном месте, формат детерминирован (ROOT, NBSP, tnum).
- **Тесты.** 2853 без единого `@Ignore`/заглушки, без mock-фреймворков (62 фейка), MockWebServer в 65 классах, все ViewModel под `MainDispatcherRule`, контрактные пробы со стенда; lint с `warningsAsErrors` — гейт CI без baseline.

---

## 6. Что уходит за скоуп (не блокирует, но фиксируем)

- Кошелёк, пополнение, тарифы, подписка, автопродление — код остаётся, скрывается флагом (#333). После срезки исчезают HIGH-находки про оплату кошельком в «Одежде» без PIN и `PaymentConfirmationPolicy` с `BIOMETRIC_WEAK`.
- Бизнес-панель — в скоупе как чтение, но тела-`Map` (#249) требуют живой проверки (#294).
- Instrumentation-тесты, Baseline Profile, скриншот-тесты (#17) — после пилота.
- 16 копий страничной ViewModel и страничные DTO (#352) — ноябрь.

---

## 7. План до релиза

**Этап A. Скоуп релиза (неделя 1).** Скрыть кошелёк/подписку одним флагом (#333), default CASH и одна
карточка «Наличные» (#334), включить «Заказать» у еды (#335), домержить PR #326 (токены в Keystore) и
#325 (биометрия STRONG). Критерий: ни один вход в кошелёк/подписку не достижим, `POST food/orders` и
`fashion/orders` уходят с `CASH`, сквозной сценарий еды с карточки места проходит на стенде, тесты зелёные.

**Этап B. Релизный конвейер (неделя 2).** Подпись из секретов + `docs/RELEASE.md` (#336), R8 +
shrinkResources с прогоном на устройстве (#337), `release-play.yml`: `bundleRelease` → Play internal,
`versionCode` из CI, `SENTRY_DSN`, `applicationIdSuffix` (#338), `assembleRelease` в CI (#165), политика
конфиденциальности в приложении (#339), иконка (#230), старт чеклиста Play (#351), задача бэкенду на
`DELETE users/me` (#340). Критерий: подписанный AAB в internal track ставится с Play, Sentry получает
тестовое событие с `release=uz.mahalla@0.1.0+N`, размер AAB зафиксирован.

**Этап C. Устойчивость и сквозные сценарии (неделя 3).** Очистка Room/профиля/FCM при logout (#341), очередь
через уведомления до `walkin/my` (#342), deep link по вертикали и гейты cold-start (#343, #160), 403 гейта и
кэш (#344, #191), таймаут после отправки заказа и `callTimeout` (#345), точечные дыры release-сборки
(#346), баги «Активностей» (#177 #174 #175 #213), 401 на свежем токене (#197, #309), проверки под токеном
(#148, #232), смещения дат (#176). Критерий: на стенде с прод-конфигом бэкенда проходят все девять
сценариев create → track → cancel (где ручка есть); при выключенном пуше статусы еды и очереди видны без
ручного обновления; регресс release-сборки по чеклисту §10. **→ 09.10 / 16.10 закрытый пилот.**

**Этап D. Пилот и стабилизация (недели 4–5).** Smoke-тест и Compose UI-тесты критичных флоу (#347), гео
(#348), push сквозной после #137 бэкенда (#349), офлайн-баннер (#350), талон в «Активностях» после #51
(#287), закрытие остатков эпика 13 (#17), правки по обратной связи пилота, Data Safety и листинг (#351),
удаление аккаунта после ручки бэкенда (#340). Критерий: 0 крашей в Sentry на пилотных сборках за неделю,
закрытое тестирование Play запущено не позже 16.10.

**Этап E. Релиз-кандидат (неделя 6).** Финальный регресс на release, staged rollout 10 % → 50 % → 100 %.
**→ 30.10 / 06.11 публичный запуск.**

---

## 8. Прогноз сроков

Отсчёт от понедельника 21.09.2026. Один разработчик с ИИ-агентами, как ведётся проект сейчас. Бэкенд
идёт параллельно по своему плану (jack5505/mahalla#289); этапы C и D зависят от его этапа B (28.09–02.10).

| Неделя | Даты | Этап | Содержание | Критерий готовности |
|---|---|---|---|---|
| 1 | 21–25.09 | A | #333 #334 #335, PR #326 #325, #305 | Кошелёк/подписка недостижимы; заказы уходят с `CASH`; еда заказывается с карточки; `testDebugUnitTest`+`assembleDebug`+`lintDebug` зелёные |
| 2 | 28.09–02.10 | B | #336 #337 #338 #165 #339 #230 #351 (старт) #340 (задача бэкенду) | Подписанный AAB с R8 в Play internal track; Sentry видит событие с release; иконка не заглушка; политика открывается из приложения |
| 3 | 05–09.10 | C | #341 #342 #343 #344 #345 #346 #177 #174 #175 #213 #197 #309 #176 #148 #232 | Девять сквозных сценариев на стенде с прод-конфигом; статусы еды и очереди видны без пуша; регресс release по §10 без BLOCKER/HIGH |
| — | **09.10 / 16.10** | | **Закрытый пилот** — closed track, ограниченный круг заведений и пользователей | Сборка `0.1.0+N` из `release-play.yml`, тег `v0.1.0` |
| 4–5 | 12–23.10 | D | #347 #348 #349 #350 #287 #17 #340 (UI) #351 (Data Safety, листинг), правки пилота | Пуш статуса заказа доходит и открывает нужную вертикаль (после #137); удаление аккаунта работает; 0 крашей за неделю пилота; закрытое тестирование Play идёт с ≥12 тестерами (если требуется аккаунту) |
| 6 | 26–30.10 | E | Регресс, staged rollout, `v1.0.0` | Чеклист §10 закрыт; Data Safety и политика опубликованы; rollout 10 % без роста крашей |
| — | **30.10 / 06.11** | | **Публичный запуск** | |

### Риски, сдвигающие сроки

1. **Бэкенд.** Этап C клиента опирается на #137 (push), #51 (`walkin/my`), #256 (`places/my`), #275 (закрытие кошелька), Idempotency-Key. Если бэкенд сдвинет свой этап B, очередь и push уходят в пилот с известным ограничением «статус только вручную», публичный запуск не сдвигается, но UX очереди хуже.
2. **Закрытое тестирование Google Play.** Личные аккаунты разработчика, созданные после ноября 2023, обязаны провести закрытый тест с ≥12 тестерами ≥14 дней до production. Тип аккаунта из кода не виден. Если требование действует, тест должен стартовать **не позже 16.10**, иначе 06.11 недостижимо. Проверить в первый день (#351).
3. **Документы и секреты владельца.** Политика конфиденциальности (текст), веб-форма удаления аккаунта, keystore, `google-services.json` боевого проекта, боевые `MAPKIT_API_KEY` и `SENTRY_DSN`. Без keystore нет этапа B; без политики — публикации. Нужны к 28.09.
4. **R8.** Включение может сломать сериализацию DTO, Hilt, MapKit; заложен прогон на устройстве в неделе 2. При тяжёлом случае пилот допустим без R8 (AAB 62 МБ), включение — в неделе 4.
5. **Удаление аккаунта.** Зависит от новой ручки бэкенда, которой нет в его плане релиза. Для пилота не нужно; для 30.10 — обязательно. Задачу бэкенду ставить на неделе 2.
6. **Устройства.** Эмулятора нет ни в CI, ни в песочнице; прогон release, FLAG_SECURE, cold-start deep link, пуши проверяются только на реальном устройстве руками.

Рекомендация та же, что у бэкенда: **закрытый пилот 16.10, публичный запуск 06.11**. Пилот на пилотных
заведениях даёт две недели живых данных по очереди и пушам — двум частям, которые ни разу не проходили
сквозной тест с реальным бэкендом.

---

## 9. Задачи релиза в трекере

Epic: jack5505/mahalla-android#353. Лейбл `release`.

| Этап | Issues |
|---|---|
| A | #333 кошелёк/подписка · #334 CASH · #335 еда · #319/PR #326 токены · #318/PR #325 биометрия · #305 CI |
| B | #336 подпись · #337 R8 · #338 конвейер Play · #165 assembleRelease в CI · #339 политика · #340 удаление аккаунта · #230 иконка · #351 чеклист Play |
| C | #341 logout · #342 очередь · #343 deep link · #344 403/кэш (+#191) · #345 таймаут заказа · #346 дыры release · #177 #174 #175 #213 активности · #197 #309 401 · #176 даты · #148 #232 проверки под токеном · #160 deep link и стартовый экран |
| D | #347 smoke/UI-тесты · #348 гео · #349 push · #350 офлайн · #287 талон в активностях · #17 эпик 13 |
| После | #352 долг: страничные VM/DTO, неленивые списки |

---

## 10. Чеклист регресса release-сборки (перед каждым треком)

Собранная `bundleRelease` с подписью, на устройстве с Android 13+ и на Android 8–9 (minSdk 26):

1. Холодный старт в светлой и тёмной системной теме — нет тёмной вспышки, splash с брендовой иконкой.
2. Welcome → телефон → OTP (resend после таймера) → PIN → биометрия → гео (разрешить и отказать → город) → главная.
3. Убить процесс, вернуться → app-lock; 5 неверных PIN → выход; вход по биометрии.
4. Карточка кафе → «Заказать» → меню → корзина → чекаут: **только «Наличные»**, нет кошелька → заказ → статус обновляется опросом → отмена.
5. Одежда: каталог → товар → корзина → чекаут наличными → список заказов → отмена.
6. Бронь мастера: услуга → слот → запись → перенос → отмена. Больница, кино, игровые зоны — создать и увидеть в «Активностях».
7. Очередь: встать → талон → отмена; (после #137/#51) увидеть принятие без ручного обновления.
8. «Мои активности»: все пять источников; выключить сеть на одном — раздел с «повторить», остальные живы.
9. Нижняя навигация — **три таба**; в профиле нет «Подписки»; `adb shell am start -d mahalla://subscription` не открывает экран подписки.
10. Пуш (с боевым `google-services.json`): разрешение запрашивается из центра уведомлений; тап по пушу заказа открывает **нужную вертикаль**; холодный старт по пушу у вышедшего пользователя ведёт на welcome.
11. Выход из аккаунта → вход другим номером: нет чужих заказов/корзины, нет чужих пушей.
12. Смена языка uz ↔ ru — все экраны, plurals, суммы «so'm/сум».
13. Sentry: принудительный краш попадает в проект с `release=uz.mahalla@0.1.0+N`, без телефона/токена в событии.
14. Профиль → «Политика конфиденциальности» открывается; «Удалить аккаунт» (после #340) работает.
15. `apksigner verify` подписи; размер AAB и per-device download записаны в CHANGELOG.

---

## Приложение A. Назначения графа (`navigation/MahallaNavHost.kt`)

| Маршрут (строка) | Экран | Статус | Loading / Empty / Error(+retry) |
|---|---|---|---|
| BackendUrlRoute (112, только `BACKEND_URL_OVERRIDE`) | onboarding/ui/BackendUrlScreen.kt | full | форма |
| UpdateRoute (134) | update/ui/AppUpdateScreen.kt | full | статичный |
| WelcomeRoute (147) | onboarding/ui/WelcomeScreen.kt | full | — |
| PhoneRoute (159) | onboarding/ui/PhoneInputScreen.kt | full | форма, ошибка inline |
| TelegramRoute (176) | onboarding/ui/TelegramLoginScreen.kt | full | L / – / Err+retry |
| OtpRoute (202) | onboarding/ui/OtpScreen.kt | full | форма, ошибка inline |
| PinRoute (214) | onboarding/ui/PinScreen.kt | full | форма |
| BiometricRoute (228) | onboarding/ui/BiometricScreen.kt | full | — |
| GeoRoute (231) | onboarding/ui/GeoScreen.kt | full | — |
| DiscoveryRoute (240) | discovery/ui/home/DiscoveryHomeScreen.kt | full | L/E/Err ×3 секции |
| OrdersRoute (258) | activity/ui/ActivityScreen.kt | full | L/E/Err + частичные отказы |
| WalletRoute (278) | wallet/ui/WalletScreen.kt | full, **вне скоупа** | L/E/Err |
| ProfileRoute (285) | profile/ui/ProfileScreen.kt | full | L/–/Err |
| RoleRoute (364) | role/ui/RoleScreen.kt | full | форма |
| CustomerFormRoute (385) | role/ui/CustomerFormScreen.kt | full | форма |
| ProviderFormRoute (399) | role/ui/ProviderFormScreen.kt | full | форма |
| SecurityRoute (435) | security/ui/SecurityScreen.kt | full | –/–/Err+retry |
| ChangePinRoute (442) | security/ui/pin/ChangePinScreen.kt | full | форма |
| MyPlacesRoute (448) | role/ui/places/MyPlacesScreen.kt | full | L/E/Err |
| BusinessRoute (481) | business/ui/dashboard/BusinessDashboardScreen.kt | full | L/E/Err |
| BusinessQueueRoute (496) | business/ui/queue/BusinessQueueScreen.kt | full | L/E/Err |
| BusinessOrdersRoute (500) | business/ui/orders/BusinessOrdersScreen.kt | full | L/E/Err |
| BusinessMenuRoute (504) | business/ui/menu/BusinessMenuScreen.kt | full | L/E/Err |
| PlaceStaffRoute (510) | role/ui/staff/PlaceStaffScreen.kt | full | L/E/Err |
| SubscriptionRoute (519, deep link) | subscription/ui/SubscriptionScreen.kt | full, **вне скоупа** | L/E/Err |
| SearchRoute (527) | discovery/ui/search/SearchScreen.kt | full | L/E/Err |
| SavedPlacesRoute (535) | social/ui/saved/SavedPlacesScreen.kt | full | L/E/Err |
| NotificationsRoute (547, deep link) | notifications/ui/NotificationsScreen.kt | full | L/E/Err |
| NotificationSettingsRoute (564) | notifications/ui/settings/NotificationSettingsScreen.kt | full | локальные |
| MapRoute (568) | map/ui/MapScreen.kt | full | L/E/Err баннерами |
| MapPickerRoute (579) | map/ui/picker/MapPickerScreen.kt | full | локальный |
| PlaceRoute (591, deep link) | place/ui/PlaceDetailsScreen.kt | full | L/–/Err; комментарии L/E/Err+retry |
| CinemaRoute (642) | cinema/ui/poster/CinemaScreen.kt | full | L/E/Err |
| MovieRoute (658) | cinema/ui/movie/MovieScreen.kt | full | L/–(Unit)/Err; сеансы L/E/Err |
| MyTicketsRoute (671) | cinema/ui/tickets/MyTicketsScreen.kt | full | L/E/Err |
| TicketRoute (677) | cinema/ui/ticket/TicketScreen.kt | full | L/–(Unit)/Err |
| DoctorBookingRoute (683) | hospital/ui/DoctorBookingScreen.kt | full | L/E/Err |
| PharmacyRoute (701) | pharmacy/ui/PharmacyScreen.kt | full (витрина) | L/E/Err |
| BookingRoute (708) | booking/ui/BookingScreen.kt | full | L/E/Err |
| MyAppointmentsRoute (731) | booking/ui/appointments/MyAppointmentsScreen.kt | full | L/E/Err |
| AppointmentRoute (764) | booking/ui/appointment/AppointmentScreen.kt | full | L/–(Unit)/Err |
| FreelancersRoute (771) | freelancer/ui/catalog/FreelancersScreen.kt | full | L/E/Err |
| FreelancerRoute (780) | freelancer/ui/profile/FreelancerProfileScreen.kt | full | L/–/Err; услуги L/E/Err |
| MyFreelancerOrdersRoute (794) | freelancer/ui/orders/MyFreelancerOrdersScreen.kt | full | L/E/Err |
| MyServicesRoute (800) | freelancer/ui/me/MyServicesScreen.kt | full | L/E/Err |
| MyFreelancerIncomingOrdersRoute (806) | freelancer/ui/orders/MyFreelancerIncomingOrdersScreen.kt | full | L/E/Err |
| QueueRoute (813) | queue/ui/QueueScreen.kt | full | L (локальный store), ошибки submit/cancel inline |
| GamingRoute (823) | gaming/ui/zones/GamingZonesScreen.kt | full | L/E/Err |
| GamingBookingsRoute (830) | gaming/ui/bookings/GamingBookingsScreen.kt | full | L/E/Err |
| MenuRoute (835) | food/ui/menu/MenuScreen.kt | full, **недостижим** (B5) | L/E/Err |
| CartRoute (842) | food/ui/cart/CartScreen.kt | full, **недостижим** | –/E/– (локальная корзина) |
| CheckoutRoute (859) | food/ui/checkout/CheckoutScreen.kt | full, **недостижим** | ошибка submit inline |
| FashionCatalogRoute (877) | fashion/ui/catalog/FashionCatalogScreen.kt | full | L/E/Err |
| FashionProductRoute (887) | fashion/ui/product/FashionProductScreen.kt | full | L/E(Text)/Err |
| FashionCartRoute (894) | fashion/ui/cart/FashionCartScreen.kt | full | L/E/Err |
| FashionCheckoutRoute (901) | fashion/ui/checkout/FashionCheckoutScreen.kt | full | L/E(Text)/Err+retry |
| FashionOrdersRoute (916) | fashion/ui/orders/FashionOrdersScreen.kt | full | L/E/Err |
| OrderStatusRoute (923, deep link) | food/ui/order/OrderStatusScreen.kt | full | L/–/Err |

Вне графа, но есть: `security/ui/lock/AppLockScreen.kt` (поверх, из `MainActivity`).

## Приложение B. Как воспроизвести прогон

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
export GRADLE_USER_HOME=/tmp/gradle-home ANDROID_USER_HOME=/tmp/android-home
./gradlew testDebugUnitTest assembleDebug --continue     # 2853 тестов, 0 падений, 4 skipped; 2 мин 14 с
./gradlew assembleRelease bundleRelease lintDebug        # unsigned APK 121,8 МБ, AAB 62,6 МБ, lint 0; 2 мин 2 с
```
