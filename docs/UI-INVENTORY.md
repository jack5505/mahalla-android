# Инвентаризация UI и план развития (issue #57)

Снимок: 2026-08-30. Ветка `claude/issue-57-*`. Кода не менялось — это документ
для нарезки задач.

Контракт бэкенда снят со стенда: `https://189-74-96-232.nip.io/v3/api-docs`
(дизайн-репозиторий агенту в CI по-прежнему недоступен — `DESIGN_REPO_PAT` не
задан). В схеме **129 эндпоинтов в 26 контроллерах**. Приложение использует
**12** — и это главный вывод инвентаризации: не «в приложении мало экранов», а
«бэкенд уехал вперёд на порядок».

---

## 1. Что видит пользователь сейчас

Нижняя навигация — 4 таба (`navigation/BottomNavItem.kt`). Из них **три —
заглушки**:

| Таб | Файл | Состояние |
|---|---|---|
| Главная | `feature/discovery/ui/home/DiscoveryHomeScreen.kt` | **рабочий**: поиск, плитка 6 категорий, «рядом», «рекомендуем», pull-to-refresh |
| Заказы | `feature/orders/ui/OrdersScreen.kt` (16 строк) | **заглушка**: `ScreenSkeleton(title, subtitle)`, ни одного запроса |
| Кошелёк | `feature/wallet/ui/WalletScreen.kt` (32 строки) | **заглушка, показывающая выдуманное число**: `DEMO_BALANCE_SUM = 1_284_500` зашит в код |
| Профиль | `feature/profile/ui/ProfileScreen.kt` | **полузаглушка**: язык, тема, адрес сервера, Chucker. Нет имени, номера, аватара, **и нет кнопки «Выйти»** |

Дальше первого экрана путей всего три: карточка места, поиск, карта.

### Экраны, которые есть в коде и работают

- **Онбординг (6 экранов + 2)**: welcome, телефон, OTP, Telegram-вход, PIN,
  биометрия, геолокация, адрес бэкенда. Самая проработанная часть приложения.
- **Discovery**: главная, поиск с фильтрами и историей, карточка места
  (галерея-скелетон, описание, часы, контакты, действия, отзывы), карта.
- **Еда (5 экранов)**: меню → шторка модификаторов → корзина → checkout →
  статус заказа. Логика полная, **но ходит по несуществующим путям** (см. §3).

### Почему приложение «выглядит очень просто» — четыре причины, по порядку вклада

1. **3 из 4 табов ничего не делают.** Это 75% нижней навигации.
2. **Нет ни одной картинки.** Загрузчика изображений в проекте нет вовсе
   (`grep coil|glide` по `libs.versions.toml` — пусто). Вместо фото заведений,
   блюд, афиш и аватаров — серые скелетоны. Один этот пункт меняет
   восприятие сильнее, чем любой новый экран.
3. **Каталог стенда пуст**: `places/nearby`, `search` и `map-bounds` отвечают
   `200` с `data: []` на любой радиус (проверено в issue #53). То есть даже
   рабочая главная показывает пустой список — и это ответ сервера, а не баг
   клиента.
4а. **На карточке места не было ни одной кнопки вертикали** (флаги
   `hasQueue`/`hasBooking`/`hasOrdering` бэкенд не отдаёт, из категории они не
   выводились). Починено в issue #71: `PlaceCapabilities.forCategory`.

4. **Карта — заглушка со списком маркеров.** Полотно `MapCanvas` (Yandex
   MapKit) написано и покрыто тестами в эпике 4.2, но `MapScreen.kt:94`
   по-прежнему рисует `MapCanvasPlaceholder`.

---

## 2. Что умеет бэкенд, а приложение — нет

Полный разбор по контроллерам. «Пользовательское» = нужно в этом приложении,
«бизнес/админ» = панель заведения (отдельный скоуп, ТЗ его упоминает).

| Контроллер | Ключевые эндпоинты | В приложении | Что это даёт UI |
|---|---|---|---|
| `wallet` | `GET wallet`, `GET wallet/transactions`, `POST wallet/top-up` | ✗ | настоящий кошелёк вместо зашитого числа |
| `notification` | `GET notifications`, `unread-count`, `PUT read-all` | ✗ | центр уведомлений, бейдж на главной |
| `social` | `POST places/{id}/like`, `/save`, `/comments`, `GET places/{id}/status`, `GET saved-places` | ✗ | лайки, «Избранное», комментарии |
| `review` | `POST reviews`, `POST reviews/{id}/reply`, `DELETE` | только `GET` | оставить отзыв (сейчас только чтение) |
| `promotion` | `GET promotions/platform`, `promotions/places/{id}`, `promotions/check` | ✗ | баннеры акций на главной, скидки в чеке |
| `media` | `POST media/upload`, `GET media/entity/{id}` | ✗ | фото в отзывах и профиле |
| `food` | `GET food/places/{id}/menu`, `POST food/orders`, `GET food/orders/my` | **пути расходятся** | см. §3 — вертикаль не работает на живом бэкенде |
| `gaming` | `GET gaming/places/{id}/zones`, `POST gaming/bookings`, `GET gaming/bookings/my` | ✗ | вертикаль «игровые зоны» (категория в приложении уже есть) |
| `appointment` | `GET barber-services/places/{id}`, `.../slots`, `POST appointments`, `GET appointments/my` | ✗ | вертикаль «мастер/барбер» + запись на слот |
| `walk-in` | `POST walkin/send`, `PUT walkin/{id}/accept|decline|start|complete` | ✗ | вызов мастера «сейчас» — живая очередь из ТЗ |
| `cinema` | `GET cinema/movies`, `cinema/places/{id}/schedule`, `POST cinema/sessions/{id}/buy`, `GET cinema/tickets/my` | ✗ | вертикаль «кино» + билет с QR (`CinemaTicket.qrCode`) |
| `hospital` | `GET hospitals/places/{id}/doctors`, `POST hospitals/appointments` | ✗ | вертикаль «больницы» |
| `pharmacy` | `GET pharmacy/places/{id}/products` | ✗ | вертикаль «аптеки» (`requiresPrescription`, `stockQuantity`) |
| `fashion` | `GET fashion/categories`, `stores/{id}/catalog`, `products/{id}`, корзина (`cart`, `cart/add`, `PUT`, `DELETE`), `POST fashion/orders` | ✗ | целая вторая вертикаль-магазин с серверной корзиной |
| `freelancer` | `GET freelancers`, `GET/POST freelancers/me`, `PUT me/toggle-availability` | ✗ | каталог мастеров + «стать исполнителем» |
| `subscription` | `GET subscriptions/plans`, `current`, `POST subscribe`, `trial`, `cancel`, `PUT auto-renew` | ✗ | подписки (в ТЗ есть); `PlanResponse` уже с `nameUz`, `trialDays`, `isPopular` |
| `payment` | `GET payments/subscription`, `payments/transactions`, callbacks Click/Payme | ✗ | реальная оплата |
| `pin-code` | `GET pin/status`, `POST pin/set`, `verify`, `reset`, `PUT change`, `PUT biometric`, `DELETE pin` | ✗ (используется только `auth/setup-pin`, `auth/pin-login`) | смена PIN из профиля, app-lock |
| `bank-auth` | `GET auth/sessions`, `POST auth/sessions/revoke`, `sessions/{id}/trust`, `auth/session/check`, `auth/pin-resume` | частично | «мои устройства», отзыв сессии, замок при возврате |
| `app-version` | `POST app/version/check`, `POST app/version/skip` | ✗ | экран обязательного обновления (`updateRequired`, `remainingSkips`, `storeUrl`) |
| `analytics` | `POST analytics/track` | ✗ | продуктовая аналитика |
| `place` | `GET places/nearby`, `search`, `places/{id}` | **есть** | — |
| `place`(бизнес) | `POST places`, `PUT places/{id}`, `PUT availability` | ✗ | бизнес-панель |
| `place-staff` | `GET/POST/PUT/DELETE places/{id}/staff` | ✗ | бизнес-панель |
| `analytics`(бизнес) | `GET analytics/places/{id}/dashboard` | ✗ | бизнес-панель |
| `auth`(админ) | `block`/`unblock` пользователя, админ версий | ✗ | не для этого приложения |

---

## 3. Блокеры контракта (чинить до новых экранов)

### 3.1 Вертикаль «Еда» ходит по путям, которых нет

`feature/food/data/FoodApi.kt` написан по здравому смыслу (эпик 5,
дизайн-репо был недоступен). Реальность:

| В приложении | На бэкенде |
|---|---|
| `GET places/{id}/menu` | `GET food/places/{placeId}/menu` |
| `POST orders` | `POST food/orders` |
| `GET orders/{id}` | **нет** — есть только `GET food/orders/my` (страница) |
| `POST orders/{id}/cancel` | **нет** (есть только бизнес-переход статуса `PUT food/places/{placeId}/orders/{orderId}/status`) |
| `POST places/{id}/promo` | **нет** — промокод проверяет `GET promotions/check` |
| `GET wallet/balance` | `GET wallet` |

Плюс модель уже: `MenuResponse` = категория с `items`, `ItemResponse` без
модификаторов вовсе (`description, id, isAvailable, isHalal, menuId, name,
prepMinutes, price`), `PlaceOrderRequest` = `placeId, items[{itemId,
quantity}], fulfillment, paymentMethod, deliveryAddress` — **ни модификаторов,
ни времени доставки, ни комментария, ни промокода**. То есть шторка
модификаторов и слоты времени из эпика 5 бэкенду сейчас нечем отправить.

Это одновременно и задача для Android (переписать `FoodApi` под реальность,
как в issue #53 для каталога), и запрос к `jack5505/mahalla` (§5).

### 3.2 Схемы в OpenAPI перекрыты коллизиями springdoc

Один и тот же `#/components/schemas/BookRequest` объявлен телом сразу трёх
разных эндпоинтов — `POST gaming/bookings`, `POST appointments`,
`POST hospitals/appointments` — а его поля (`complaint, date, doctorId,
startTime`) явно от больницы. Для игровой зоны нужны `zoneId`/`durationHours`
(они видны в `GamingBooking`), для барбера — `serviceId`. То же у
`CreateRequest` (отзывы) и `CheckRequest` (версия), и то же было с `Response` в
issue #53.

**Практический вывод: вертикали gaming/barber/hospital нельзя писать «по
схеме» — тела запросов придётся снимать curl'ами или ждать починки springdoc
(`springdoc.use-fqn=true`).** Это делает их дороже, чем кажется, и поэтому они
не в первой волне задач.

---

## 4. Предлагаемый список задач

Порядок — по отношению «заметность для пользователя ÷ стоимость». Каждый пункт
рассчитан на отдельный issue.

### Волна 0 — приложение перестаёт выглядеть пустым (4 задачи)

**A1. Картинки: Coil + фото мест, блюд, афиш, аватаров.**
Добавить `io.coil-kt:coil-compose`, компонент кита `MahallaAsyncImage`
(скелетон → фото → фоллбэк-иконка, кэш, `crossfade`), подключить в
`PlaceCard`, галерею карточки места, меню, отзывы. Эндпоинт для загрузки своих
файлов — `POST media/upload` (multipart, `entityType`/`entityId`), чтение —
`GET media/entity/{entityId}`; у мест URL уже приезжает полем.
*Самая дешёвая задача с самым большим визуальным эффектом. Делать первой.*

**A2. Кошелёк вместо зашитого числа.**
`GET wallet` (`balance`, `bonusBalance`, `heldAmount`, `availableBalance`,
`currency`, `status`), `GET wallet/transactions?page&size` (список с
`type`/`direction`/`amount`/`balanceAfter`/`createdAt`, группировка по дням),
`POST wallet/top-up` (`amount`, `provider`). Убрать `DEMO_BALANCE_SUM` —
сейчас экран показывает выдуманные 1 284 500 сум, и это хуже пустого экрана.

**A3. Таб «Заказы» → «Мои активности».**
Один список из пяти источников: `GET food/orders/my`,
`GET gaming/bookings/my`, `GET appointments/my`,
`GET hospitals/appointments/my`, `GET cinema/tickets/my`. Фильтр
«активные/история», переход на статус заказа. Пока вертикали не сделаны,
источники подключаются по мере готовности — но каркас списка нужен сразу,
иначе таб остаётся пустым.

**A4. Профиль: живой профиль + выход.**
Имя, номер, аватар (данные приходят в `user` из `verify-otp`/`pin-login`),
кнопка **«Выйти»** (`AuthRepository.logout()` уже написан и из UI не вызывается
нигде), «Мои устройства» (`GET auth/sessions` → `deviceName`, `platform`,
`lastActivityAt`, `lastIp`, `trustedDevice`; `POST auth/sessions/revoke`,
`POST auth/sessions/{id}/trust`), смена PIN (`PUT pin/change`), переключатель
биометрии (`PUT pin/biometric`). Сейчас из приложения нельзя выйти вообще.

### Волна 1 — вовлечение, дешёвые задачи (4 задачи)

**B1. Карта по-настоящему.** Заменить `MapCanvasPlaceholder` на готовый
`MapCanvas`, подключить `GET places/map-bounds` при движении камеры, решить
судьбу сеточного `MarkerClusterer` (MapKit кластеризует сам). Нужен секрет
`MAPKIT_API_KEY` в Actions — **действие пользователя**.

**B2. Лайк, «Избранное», комментарии на карточке места.**
`GET places/{id}/status` (`liked`, `saved`, `totalLikes`),
`POST places/{id}/like`, `POST places/{id}/save`,
`GET/POST places/{id}/comments`, раздел «Избранное» из `GET saved-places`.
Внимание: `saved-places` отдаёт **только UUID'ы** (`PageResponseUUID`) — без
изменения на бэкенде экран избранного потребует запрос на каждое место (§5).

**B3. Оставить отзыв.** `POST reviews` (+ фото через A1/`media/upload`),
удаление своего (`DELETE reviews/{id}`). Сейчас отзывы только читаются, при
этом рейтинг — главный сигнал в выдаче.

**B4. Акции и промо.** `GET promotions/platform` — карусель баннеров на
главной (`bannerUrl`, `title`, `discountPercent`, `validUntil`),
`GET promotions/places/{id}` — плашка на карточке, `GET promotions/check` —
проверка промокода в чеке (заменяет выдуманный `places/{id}/promo`).

**B5. Центр уведомлений.** `GET notifications?page&size`,
`GET notifications/unread-count` (бейдж на иконке в топбаре главной),
`PUT notifications/read-all`. Переход по `type` + `entityId`.

### Волна 2 — вертикали (5 задач, каждая крупная)

Перед каждой — снять реальные тела запросов curl'ами (§3.2).

**C1. Еда под реальный контракт.** Переписать `FoodApi` на `food/*`, убрать
несуществующие вызовы, решить, что делать с модификаторами и слотами доставки,
которых нет в `PlaceOrderRequest`. Блокер работоспособности эпика 5 — по
приоритету это волна 0, по объёму — волна 2.

**C2. Игровые зоны (GAMING).** `GET gaming/places/{id}/zones` (`zoneType`,
`totalSeats`, `pricePerHour`, `isAvailable`) → выбор времени и длительности →
`POST gaming/bookings` → `GET gaming/bookings/my`.

> **Частично сделано в issue #71**: `POST walkin/send` подключён — с карточки
> мастера открывается форма заказа услуги, а ответ показывается состоянием
> заявки. Осталось: слоты (`barber-services/…/slots`, `POST appointments`),
> перечитывание заявки (у бэкенда нет `GET walkin/{id}`) и статусы мастера.

**C3. Мастер/барбер + вызов «сейчас».** `GET barber-services/places/{id}`,
`GET .../slots` (сетка слотов), `POST appointments`, `GET appointments/my`.
Отдельно — `POST walkin/send` и статусы `accept/decline/start/complete`: это
живая очередь из ТЗ, экран с ожиданием ответа мастера.

**C4. Кино.** `GET cinema/movies` (`posterUrl`, `trailerUrl`, `genre`,
`durationMinutes`, `titleUz`), `GET cinema/places/{id}/schedule`
(`hallName`, `startTime`, `ticketPrice`, `availableSeats`),
`POST cinema/sessions/{id}/buy` → билет с QR (`CinemaTicket.qrCode`) в
«Активностях». Нужна отрисовка QR (библиотека или своя матрица).

**C5. Больницы и аптеки.** `GET hospitals/places/{id}/doctors`
(`specialty`, `consultationPrice`, `bio`) + `POST hospitals/appointments`;
`GET pharmacy/places/{id}/products` (`requiresPrescription`, `stockQuantity`,
`dosageForm`, `strength`) — поиск по препарату, наличие.

**C6. Fashion-магазин.** `GET fashion/categories`, `stores/{id}/catalog`,
`products/{id}` (варианты: размер/цвет), **серверная корзина**
(`GET/POST/PUT/DELETE fashion/cart*`), `POST fashion/orders`. Отличается от
еды тем, что корзина живёт на сервере — переиспользовать `CartCalculator`
не получится, и это надо учесть в оценке.

> **Частично сделано в issue #71**: «стать исполнителем» есть —
> `GET/POST freelancers/me` и `PUT freelancers/me/toggle-availability` за
> формой в профиле («Мои услуги»). Осталось: каталог `GET freelancers` с
> фильтрами по профессии и городу.

**C7. Фрилансеры.** `GET freelancers` (каталог с фильтрами) и «стать
исполнителем» (`GET/POST freelancers/me`,
`PUT freelancers/me/toggle-availability`).

### Волна 3 — платформа (5 задач)

**D1. Подписки.** `GET subscriptions/plans?audience`, `GET current`,
`POST trial` (`trialDays`), `POST subscribe`, `POST cancel`,
`PUT auto-renew`. `PlanResponse` уже несёт `nameUz`, `isPopular`,
`yearlyDiscountPercent` — экран тарифов рисуется прямо по нему.

**D2. Оплата Click/Payme.** `payments/*` + `wallet/top-up`, возврат из
внешнего приложения, идемпотентность. Без этого «оплата кошельком» в еде —
только проверка баланса.

**D3. Обязательное обновление.** `POST app/version/check` →
`updateRequired`/`updateAvailable`/`policy`/`remainingSkips`/`storeUrl`,
`POST app/version/skip`. Блокирующий экран при `updateRequired`. Дёшево и
снимает будущую боль с несовместимыми версиями API.

**D4. Push-уведомления (FCM).** `DeviceDescriptor.fcmToken` уже объявлен и
всегда `null` — поле ждёт FCM. Токен отправляется вместе с устройством в
`send-otp`/`refresh`. Связать с B5.

**D5. App-lock.** PIN/биометрия при возврате в приложение:
`POST auth/session/check`, `POST auth/pin-resume`, `GET pin/status`. Флаги
`biometricEnabled` и PIN уже сохраняются с эпика 3, но замка нет.

**D6. Аналитика.** `POST analytics/track` — экраны, поиски, воронка заказа.

### Волна 4 — бизнес-панель (отдельный скоуп, обсудить с продуктом)

Заведение из приложения: `POST/PUT places`, `PUT places/{id}/availability`,
меню (`POST food/places/{id}/items`, `PUT food/items/{id}/toggle`),
статусы заказов, персонал (`places/{id}/staff`), дашборд
(`GET analytics/places/{id}/dashboard`), бизнес-кошелёк
(`GET wallet/business`), бизнес-подписка. Отвечать на отзывы
(`POST reviews/{id}/reply`). Это по объёму сравнимо со всем клиентским
приложением — стоит решить, отдельное это приложение или раздел в профиле.

### Волна 5 — UI-долг и качество (уже частично в AGENTS.md)

**E1. Навигация: 4 таба мало?** Сейчас Главная/Заказы/Кошелёк/Профиль. С
появлением A3 и B5 стоит обсудить пятый таб («Избранное» или «Акции») либо
оставить 4 и обогатить главную. Решение продукта, не техническое.
**E2. Compose UI-тесты** (`ui-test-junit4` + Robolectric): в проекте нет ни
одного — цели нажатия, состояния и семантика проверяются глазами по превью.
**E3. Скриншот-тесты** и сверка с `TZ-ANDROID.md`/`SCREENS.md` — соответствие
макету ни разу не проверялось машинно (дизайн-репо агенту недоступно).
**E4. Пустые состояния с действием**: при пустом каталоге экран должен
предлагать расширить радиус/сменить город, а не просто «ничего не найдено».
**E5. Мелкий долг ревью** — уже перечислен в AGENTS.md (plurals для
`otp_input_description`/`pin_input_description`, `TextFieldValue` в
`MahallaPhoneField`, `disabledContainerColor`, двойной инсет в `Sheets.kt`,
`SearchEvent.QueryCleared` без отправителя и т.д.).

---

## 5. Вопросы и просьбы к `jack5505/mahalla` (не блокеры этого issue)

1. **Коллизии имён в OpenAPI** (`BookRequest`, `CreateRequest`,
   `CheckRequest`, `Response`): включить `springdoc.use-fqn=true`. Сейчас по
   схеме нельзя понять тело трёх разных эндпоинтов бронирования — каждая
   вертикаль начинается с обратной разработки curl'ами.
2. **Нет `GET /users/me` и обновления профиля**: данные пользователя приходят
   только в ответе на вход, аватар/имя менять нечем.
3. **`PlaceOrderRequest` беднее корзины приложения**: нет модификаторов
   позиций, промокода, времени доставки и комментария. Либо расширить, либо
   мы выкидываем эти шаги из UI еды.
4. **Нет `GET food/orders/{id}` и отмены заказа пользователем**: экран статуса
   вынужден искать свой заказ в странице `orders/my`, а кнопка «Отменить»
   ведёт в никуда.
5. **`GET saved-places` отдаёт только UUID'ы** — экран «Избранное» получается
   N+1 запросом. Просьба вернуть карточки места.
6. **У места нет расписания работы** (`openingHours` отсутствует, issue #53):
   фильтр «открыто сейчас» опирается на единственный флаг `isAvailable`.
7. **Очередь из ТЗ**: похоже, это `walk-in` — подтвердить, что других
   эндпоинтов очереди/талонов не планируется (в приложении уже есть
   `TicketCard` и `TicketFormatter` под талоны `A-042`).
8. **Каталог стенда пуст** — до наполнения ни один экран discovery проверить
   на живых данных нельзя.

---

## 6. Порядок, который я предлагаю

1. `A1` картинки → `A2` кошелёк → `A4` профиль с выходом → `A3` каркас
   активностей. Четыре задачи, после которых приложение перестаёт выглядеть
   демо.
2. `C1` еда под реальный контракт (иначе единственная готовая вертикаль
   мертва) + `B1` карта.
3. `B2`–`B5` — дёшево и сильно оживляет.
4. Вертикали `C2`–`C7` по одной, каждая после снятия контракта curl'ами.
5. `D1`–`D6`, потом бизнес-панель.
