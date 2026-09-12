# Контракт бэкенда

Что клиент реально вызывает — извлечено из `*Api.kt` в коде (2026-09-08).
Базовый путь: `https://api.mahalla.uz/api/v1/` (release),
`https://189-74-96-232.nip.io/api/v1/` (debug) — `BuildConfig.API_BASE_URL`.

**Зачем файл.** Выдуманный контракт дважды приводил к переделке целой
вертикали: сначала OTP, потом «Еда». Перед правкой любого `*Api.kt` сверяйся
отсюда; расходится с задачей — не угадывай, пиши в отчёт. Подтвердил или
изменил эндпоинт — обнови этот файл в том же PR.

## Статус

| Значок | Смысл |
|---|---|
| ✅ | сверен с реальным бэкендом, ссылка на issue рядом |
| ⚠️ | **не сверен** — написан по описанию задачи, может расходиться |

## Общее для всех запросов

**Конверт ответа** (`data/network/ApiResponse.kt`) — один на весь API,
все поля необязательные:

```json
{"success": false,
 "error": {"code": "VALIDATION_ERROR", "message": "Joylashuv ruxsatini yoqing"},
 "timestamp": "2026-08-28T18:23:03Z"}
```

Полезная нагрузка — в `data`, причина отказа — в `error.code` (машинный:
`VALIDATION_ERROR`, `OTP_EXPIRED`, `TOKEN_INVALID`) и `error.message` (текст
для человека, язык выбирает бэкенд).

**Геолокация обязательна.** `GeoHeaderInterceptor` шлёт `X-Geo-Lat` /
`X-Geo-Lng` на каждый запрос. Без них бэкенд отвечает
`403 GEO_PERMISSION_REQUIRED`, с мусором в значениях —
`403 GEO_INVALID_COORDINATES`. Координаты в query-параметрах не дублировать.

**Авторизация.** `Authorization: Bearer <access>` вешает `AuthInterceptor`.
На 401 `TokenAuthenticator` делает один refresh и повторяет запрос.
Сессию заканчивает **только 401 на refresh** — стираются токены, приложение
уходит на экран входа (issue #138, #239). Так бэкенд отвечает на каждый
случай мёртвой сессии (`BankAuthService.refreshToken`, `JwtService`):
`TOKEN_EXPIRED`, `TOKEN_INVALID` (подпись, тип, токен уже заменён ротацией),
`TOKEN_HIJACK` (другой отпечаток `deviceId|platform|osVersion` — сессия
отозвана). Всё прочее токены **не** стирает: 403 (`GEO_*`, блокировка),
400 (форма запроса), 429, 404, 5xx, обрыв, таймаут, 2xx без токенов,
`success: false` при 2xx, неразбираемое тело. Сроки по `application.yml`
бэкенда (`jwt.*-expiry-seconds`): access — 15 минут, refresh — 30 дней.
Сами эндпоинты `auth/*` ходят на `@RefreshClient` — клиент без authenticator'а.

**Коллизия springdoc разведена** (сверено 2026-09-10). Раньше одно имя схемы
(`BookRequest`, `ServiceResponse`, `Response`) занимали сразу несколько
вертикалей, побеждала одна, и половина DTO в клиенте была **выведена**, а не
прочитана (issue #76, #84, #97). Теперь имена уникальны — 255 схем, ни одного
`BookRequest`/`ServiceResponse`/`Response` без префикса вертикали:
`AppointmentBookRequest` / `GamingBookRequest` / `HospitalBookRequest`,
`AppointmentServiceResponse` / `FreelancerServiceResponse`. Значит всё, что в
клиенте помечено «имена выведены из схемы», **теперь можно проверить чтением**.
По чтению перепроверена пока только вертикаль записи (`BookingApi`,
`GamingApi.book`, `ReviewDto`, `FreelancerApi.services`) — где сделано,
отмечено датой. Остальные KDoc и тесты, которые считают коллизию действующей
(`CreateRequest` у `ProviderApi` и `POST reviews`, `OrderResponse` у еды /
одежды / мастеров, `Response` у walk-in), не перепроверялись — сквозной
проход вынесен в issue #235.

**Страничные ответы** — один конверт `PageResponse…` на все списки:
`content` / `page` / `size` / `totalElements` / `totalPages` / `first` /
`last`. Есть ли следующая страница, решает общая функция
`core/paging/hasMorePages` (issue #142): приоритет у `last`, без него —
`page`/`totalPages`, при полном молчании сервера догрузка останавливается.
На неё переведены fashion, freelancer, wallet и «Мои активности»; в семи
мапперах ещё лежит дословная копия того же правила — issue #231.

**Деньги — в тийинах.** Все целые денежные поля во всех ответах и телах
запросов (`price`, `totalAmount`, `balance`, `amount`, `monthlyPrice`,
`totalPrice`, `consultationPrice`, `hourlyRate`, …) — **тийины**, 1 сум = 100
тийинов. Это документировано самим бэкендом в `info.description` живого
`/v3/api-docs` (снято 2026-09-10, issue #149):

> Barcha butun sonli pul maydonlari **tiyin**da uzatiladi: 1 so'm = 100 tiyin.
> Ko'rsatishdan oldin 100 ga bo'lish kerak: `5000000` → `50 000 so'm`. Kasr son
> yoki so'mdagi qiymat qabul qilinmaydi.

Клиент живёт в целых сумах: домен, экраны и Room хранят сумы, а пересчёт делает
`core/format/Money` ровно один раз — в маппере DTO → домен (`tiyinToSom`) и при
сборке тела запроса (`somToTiyin`, сейчас это только `POST wallet/top-up`).
Дробные близнецы `balanceSom`, `amountSom`, `monthlyPriceSom`, `pricePaidSom`
— то же число в сумах для чтения ответа глазами; клиент их **игнорирует**, а не
выводит из них единицу, как делал раньше `WalletAmounts`. Проценты
(`discountPercent`, `yearlyDiscountPercent`) деньгами не являются и не делятся.

---

## «Мои активности» — своего `*Api.kt` нет

`feature/activity/` (issue #73) не объявляет ни одной ручки и ни одного DTO:
пять источников читаются интерфейсами вертикалей, которым принадлежат.
Ходить в них мимо этих интерфейсов не надо — копия контракта уже разошлась с
оригиналом один раз (issue #142).

| Источник | Через что | Путь |
|---|---|---|
| Заказы всех вертикалей | `FashionApi.myOrders(vertical = null)` | `GET orders` |
| Брони игровых зон | `GamingApi.myBookings` | `GET gaming/bookings/my` |
| Записи к мастеру | `BookingApi.myAppointments` | `GET appointments/my` |
| Записи к врачу | `HospitalApi.myAppointments` | `GET hospitals/appointments/my` |
| Билеты в кино | `CinemaApi.myTickets` | `GET cinema/tickets/my` |

---

## AnalyticsApi ⚠️ частично

`app/src/main/java/uz/mahalla/data/network/analytics/AnalyticsApi.kt` — путь,
схема запроса и требование токена сверены со стендом 2026-09-10 (`/v3/api-docs`
+ curl'ы, issue #169); успешный ответ под токеном — нет, `CONTRACT_REFRESH_TOKEN`
всё ещё не задан.

| Метод | Путь | |
|---|---|---|
| POST | `analytics/track` | ✅ путь и схема есть, `401` без токена; тело успеха не проверено |

```json
TrackEventRequest: {
  "placeId": "uuid",       // обязателен
  "eventType": "VIEW",     // обязателен, закрытое перечисление
  "lat": 41.31, "lng": 69.24,
  "metadata": {}           // свободный объект
}
```

`eventType` — ровно девять значений: `VIEW`, `LIKE`, `SAVE`, `SHARE`, `CALL`,
`NAVIGATE`, `BOOK`, `ORDER`, `REVIEW`. Ответ — `ApiResponseVoid`, то есть
конверт без полезной нагрузки.

**Это счётчик взаимодействий с заведением, а не продуктовая аналитика.**
Из-за обязательного `placeId` и закрытого перечисления отправить нечем:
открытие экрана без заведения (профиль, кошелёк, «мои активности»), поисковый
запрос и отказ бэкенда. Своего вида события под них клиент выдумать не может —
нужна ручка на стороне бэкенда (issue #226). Поэтому `BOOK` и
`ORDER` в приложении шлются на все вертикали сразу, а различает их
`metadata.vertical` (`food`, `fashion`, `queue`, `booking`, `hospital`,
`gaming`, `cinema`) — **содержимое `metadata` не сверено**: схема объявляет его
свободным объектом, но что бэкенд с ним делает, из схемы не следует.

Три факта, из которых следует устройство отправки в клиенте:

- **токен обязателен.** Аноним с гео-заголовками получает
  `401 UNAUTHORIZED` (`{"success":false,"error":{"code":"UNAUTHORIZED"}}`),
  без гео-заголовков — `403 GEO_PERMISSION_REQUIRED`, то есть гео проверяется
  раньше. Каталог смотрят и до входа, поэтому без сессии клиент запрос **не
  делает**: он заведомо окажется отказом.
- **батчинга нет.** Под `analytics` у бэкенда ровно два пути: этот `track` и
  `places/{placeId}/dashboard` (бизнес-панель, эпик #16, клиентом не
  используется). Одно событие — один запрос.
- **поля времени события в запросе нет.** Момент события — это момент, когда
  запрос доехал до сервера. Отсюда решение не копить события в офлайне:
  `docs/adr/0006-analitika-bez-seti.md`.

`lat`/`lng` в теле объявлены, но клиент их **не заполняет**: координаты уже
уходят в `X-Geo-Lat`/`X-Geo-Lng`. **Это допущение**, а не проверенный факт:
общее правило «координаты не дублировать» выше написано про query-параметры, а
берёт ли бэкенд гео события из заголовков — из схемы не следует. Не берёт —
события приедут без координат, и гео-половина дашборда заведения останется
пустой, причём молча. Открытый вопрос к бэкенду, issue #226.

## AuthApi ✅

`app/src/main/java/uz/mahalla/data/network/auth/AuthApi.kt` — сверен: issue #42 (регистрация), #51 (PIN-шаг), #46/#49/#54 (Telegram).

| Метод | Путь |
|---|---|
| POST | `auth/send-otp` |
| POST | `auth/verify-otp` |
| POST | `auth/setup-pin` |
| POST | `auth/pin-login` |
| POST | `auth/telegram/init` |
| POST | `auth/telegram/check` |
| POST | `auth/refresh` |
| POST | `auth/logout` |

`AuthDeviceInfo` (поле `device` у всех, кроме `setup-pin` и `logout`):
`deviceId` и `platform` (`ANDROID|IOS|WEB`) обязательны, `deviceName` ≤ 200,
`osVersion` ≤ 50, `appVersion` ≤ 20, **`fcmToken` ≤ 500**. Последнее — это
единственный способ отдать бэкенду токен пушей: отдельной ручки регистрации
устройства у него нет (эпик 11, см. раздел NotificationsApi).

## users/me ✅ — своего `*Api.kt` ещё нет

Профиль на сервере. Снято чтением живого `/v3/api-docs` 2026-09-10 (issue #237); приложение эти ручки пока **не зовёт** — issue #170. Девять KDoc в коде утверждали, что их у бэкенда нет вовсе; там, где правки касались файла, KDoc исправлен, остальные — по мере работы.

| Метод | Путь |
|---|---|
| GET | `users/me` → `MeResponse` |
| PUT | `users/me` ← `UpdateMeRequest` → `MeResponse` |

`MeResponse`: `id`, `phone`, `fullName`, `avatarUrl`, `language` (`UZ`/`RU`), `role`, `verificationStatus` (`UNVERIFIED`/`SMS_VERIFIED`/`FULL_VERIFIED`), `accountStatus` (`ACTIVE`/`TEMP_BLOCKED`/`PERM_BLOCKED`/`SUSPENDED`/`DELETED`), `telegramLinked`, `lastLoginAt`.

`role` — четырнадцать значений: `USER`, `BARBER`, `BAKER`, `SHOP_OWNER`, `FOOD_OWNER`, `GAMING_OWNER`, `MUSEUM_OWNER`, `PARK_OWNER`, `MOSQUE_OWNER`, `PHARMACY_OWNER`, `HOSPITAL_OWNER`, `CINEMA_OWNER`, `FREELANCER`, `ADMIN`. Тот же набор и в `UserInfo` — блоке `user` ответа на вход, откуда приложение и берёт роль сейчас.

**`PUT` принимает ровно два поля**, и это PATCH по смыслу (так написано в самом описании ручки):

```json
{"fullName": "Yangi ism", "avatarUrl": "https://.../a.png"}
```

- поля нет или `null` — **не меняется**; пустая строка — **очищается** (`{"avatarUrl": ""}` снимает аватар);
- `fullName` ≤ 200 символов, `avatarUrl` ≤ 500 и по маске `^$|^https?://.+` — то есть адрес из `media/upload`.

**Ни `language`, ни `role` отправить нечем** (сверено 2026-09-10, issue #237):

- слово `language` встречается во всей схеме **один раз** — в `MeResponse`. Ни одно тело запроса его не принимает. Про `Accept-Language` схема молчит, и это ничего не значит: в ней не объявлены ни `X-Geo-*`, ни `Authorization` — springdoc заголовки из фильтров не показывает (единственный объявленный header во всём контракте — `X-Session-Id` у `auth/logout`), так что понимает бэкенд этот заголовок или нет, надо проверять пробой (issue #242). Как бы то ни было, в тела запросов язык не положить, поэтому язык приложения ведёт клиент (`SettingsDataStore`), а это поле **не разбирается**;
- `role` меняет только админ через `PUT admin/users/{id}/role`. Значит по правам главный сервер, а локальный `settings.roleId` (`UserRole`) — вообще про другое: про анкету. Подробно — `docs/adr/0007-yazyk-i-rol-istochnik-istiny.md`.

## BookingApi ⚠️ частично

`app/src/main/java/uz/mahalla/feature/booking/data/BookingApi.kt` — сверен пробой `contract/booking.sh` (2026-09-08), но только анонимная половина: всё под токеном требует `CONTRACT_REFRESH_TOKEN`, а его пока нет.

| Метод | Путь | |
|---|---|---|
| GET | `barber-services/places/{placeId}` | ✅ |
| GET | `barber-services/places/{placeId}/slots` | ✅ |
| POST | `appointments` | ✅ тело сверено схемой (2026-09-10), ответ — нужен токен |
| GET | `appointments/my` | ⚠️ не проверено — нужен токен |
| POST | `appointments/{id}/cancel` | ⚠️ не проверено — нужен токен |

**Тело `POST appointments` сверено чтением** (2026-09-10, после развода
коллизии): `AppointmentBookRequest {placeId, serviceId, serviceName, date,
startTime}`, обязательные — `date`, `placeId`, `startTime`. Клиент шлёт
`{placeId, serviceId, date, startTime}` — совпало, выведенные имена оказались
верны. `serviceName` необязателен и не отправляется: имя услуги у сервера уже
есть по `serviceId`, дублировать его с клиента незачем.

Ответ — `AppointmentBookingResponse {id, placeId, userId, serviceId,
serviceName, price, apptDate, startTime, endTime, status, createdAt}`,
`status` — enum `PENDING | CONFIRMED | CANCELLED | COMPLETED | NO_SHOW`.

**`AppointmentServiceResponse` — имена были угаданы неверно.** Стенд отдаёт
`{id, name, colorHex, price, durationMinutes}`, а клиент до 2026-09-08 ждал
`title` и `priceAmount`: у каждой услуги на экране записи пропадали название
и цена. Исправлено вместе с этой пробой; фикстура —
`app/src/test/resources/contract/booking/services.json`.

`description` и `freelancerId` из DTO убраны, и теперь понятно, почему стенд их
не шлёт: **их нет в самой схеме** (сверено 2026-09-10). Оба поля есть у
`FreelancerServiceResponse`, то есть у другой вертикали: раньше они видны были
здесь только из-за склейки имён. `isActive`/`active` в схеме барбершопа тоже
нет, но пара в DTO оставлена: разбор мягкий, лишнее известное поле дешевле
пропавшего списка услуг.

Слоты подтверждены как **массив строк** вида `"09:00"` — не объекты.

### Три пробела контракта (issue #154)

Перепроверено по живому `/v3/api-docs` **2026-09-10** — за сутки не изменилось
ничего, все три пункта эпика #11 по-прежнему упираются в бэкенд.

**1. Своей ручки переноса записи нет.** Под `appointments` ровно пять путей:
`POST`, `my`, `{id}` (только `GET`), `{id}/cancel`, `{id}/status`; ни
`reschedule`, ни `PUT appointments/{id}`. Поиск по всему документу на
`resched|transfer|move` — пусто. Поэтому перенос в приложении собран из двух
уже сверенных ручек — `POST appointments` плюс `POST appointments/{id}/cancel`,
**в этом порядке** (см. `BookingRepository.reschedule`). Открыто:

- **даёт ли сервер создать вторую запись в том же заведении, пока висит
  первая?** Проверить нечем: всё под `appointments` требует Bearer, а
  `CONTRACT_REFRESH_TOKEN` в CI нет. Если не даёт — перенос сейчас отказывает
  сообщением сервера, и порядок надо менять не наугад. Обратный порядок
  (отмена → запись) молча терял бы запись, если слот к этому моменту ушёл, и
  потому не выбран;
- **нужна атомарная ручка** (`PUT appointments/{id}` или
  `POST appointments/{id}/reschedule` с новыми `date`/`startTime`). Между двумя
  запросами есть окно, в котором у человека две записи. Клиент закрывает его
  как может — отмена идёт в `NonCancellable`, на экране предупреждение, — но
  смерть процесса ровно между запросами молча оставит лишнюю запись. Закрыть
  это может только сервер.

**2. Мастера выбрать нечем.** У `AppointmentServiceResponse` нет `freelancerId`
**в самой схеме** (см. выше), а у `barber-services/places/{placeId}/slots`
ровно три параметра — `placeId`, `serviceId`, `date`, фильтра по сотруднику
нет. Слоты приходят на заведение целиком. Нужно либо `freelancerId` у услуги
плюс параметр сотрудника у слотов, либо явное «мастера в барбершопе не
выбирают» — тогда пункт 7.1 неприменим.

**3. Предоплаты у записи нет.** Ни в `AppointmentBookRequest`, ни в
`AppointmentBookingResponse` нет `prepayment`, `paid` или суммы к оплате
(поиск по документу на `prepa` даёт единственное совпадение `PREPARING` — и то
статус заказа). Платежи остались подписочными:

```
/api/v1/payments/subscription
/api/v1/payments/subscription/activate
/api/v1/payments/transactions
/api/v1/payments/click/callback
/api/v1/payments/payme/callback
```

`PaymentTransaction {id, userId, amount, provider: PAYME|CLICK|UZUM|CASH,
status: PENDING|PAID|FAILED|CANCELLED|REFUNDED, purpose, purposeId,
externalOrderId, errorMessage, createdAt, updatedAt}` устроена обобщённо, и
пара **`purpose` + `purposeId`** (`purpose` — свободная строка, не enum)
выглядит тем местом, куда запись могла бы лечь. Но это догадка, не контракт:
экран предоплаты без ответа бэкенда был бы выдумкой.

**Своей ручки переноса записи у бэкенда нет** (сверено по живому
`/v3/api-docs` 2026-09-08, эпик #11). Под `appointments` есть ровно пять
путей: сам `POST`, `my`, `{id}`, `{id}/cancel` и `{id}/status`; ни
`reschedule`, ни `PUT appointments/{id}` среди них нет. Поэтому перенос в
приложении собран из двух уже сверенных ручек — `POST appointments` плюс
`POST appointments/{id}/cancel`, **в этом порядке** (см.
`BookingRepository.reschedule`). Отсюда два открытых вопроса к бэкенду:

- **даёт ли он создать вторую запись в том же заведении, пока висит первая?**
  Если нет, перенос будет отказывать сообщением сервера, и правильный порядок
  придётся выяснять уже с бэкендом — обратный (отмена → запись) молча терял бы
  запись, если слот к этому моменту ушёл, и потому не выбран;
- **не понадобится ли атомарная ручка.** Между двумя запросами есть окно, в
  котором у человека две записи; клиент это окно закрывает как может, но
  честнее закрыть его на сервере.

`GET appointments/{id}` приложение по-прежнему не использует (своего экрана у
одной записи нет), `PUT appointments/{id}/status` — бизнес-панель, эпик #16.

`price` услуги и записи — в тийинах (см. «Общее для всех запросов»): стенд
отдаёт за стрижку `5000000`, это 50 000 сум, и так их показывает экран
(issue #149, PR #233; фикстура
`app/src/test/resources/contract/booking/services.json`). За подравнивание
бороды стенд отдаёт `3000000` — 30 000 сум.

## CinemaApi ⚠️

`app/src/main/java/uz/mahalla/feature/cinema/data/CinemaApi.kt` — НЕ СВЕРЕН: писался по описанию задачи — проверить перед правкой.

| Метод | Путь |
|---|---|
| GET | `cinema/movies` |
| GET | `cinema/places/{placeId}/schedule` |
| POST | `cinema/sessions/{sessionId}/buy` |
| GET | `cinema/tickets/my` |
| PUT | `cinema/tickets/{id}/cancel` |

## CatalogApi ✅

`app/src/main/java/uz/mahalla/feature/discovery/data/CatalogApi.kt` — сверен: issue #53 — реальные эндпоинты и координаты; issue #168 — `places/map-bounds`.

| Метод | Путь |
|---|---|
| GET | `places/nearby` |
| GET | `places/map-bounds` |
| GET | `places` (`ids=`) |
| GET | `search` |
| GET | `places/{id}` |
| GET | `reviews/places/{placeId}` |
| POST | `reviews` |
| DELETE | `reviews/{id}` |

**`GET places?ids=`** — заведения пачкой по id (issue #182, снимает
клиентскую часть #150), снят со схемы при сверке issue #92:

```
GET /api/v1/places?ids=<uuid>&ids=<uuid>…   (401 без токена)
→ List<Summary> {id, name, category, address, lat, lng, isAvailable,
    ratingAvg, ratingCount, distanceMeters, logoUrl, subscriptionPlan}
```

`ids` обязателен и повторяемый. Ответ разбирается тем же `PlaceSummaryDto`,
что у `nearby`/`map-bounds` — полей достаточно, `subscriptionPlan` клиенту не
нужен и не разбирается. Лимита на число `ids` в схеме нет; клиент режет
список на пачки по 50 сам (`PlaceNameResolver`), чтобы не упереться в
ограничение длины запроса на сервере — это не подтверждено ручкой, только
предосторожность.

**`GET places/map-bounds`** — маркеры для видимой области карты (issue #168),
снят со стенда 2026-09-10 (`/v3/api-docs`, `operationId: mapBounds`, + живой
запрос):

| Параметр | Обязателен | Смысл |
|---|---|---|
| `minLat`, `minLng` | да | юго-западный угол, `double` |
| `maxLat`, `maxLng` | да | северо-восточный угол, `double` |
| `category` | нет | одно значение перечисления (`FOOD`, `PHARMACY`, …) |

Ответ — `ApiResponse<List<PlaceSummary>>`, тот же DTO, что у `nearby`, включая
`distanceMeters`: расстояние сервер считает по заголовкам `X-Geo-*`, а не по
прямоугольнику, поэтому оно совпадает с расстоянием в списке. Пагинации нет —
область целиком одним списком. Вывернутый прямоугольник (`min > max`) отвечает
`200` с пустым `data`, а не ошибкой, — на клиенте область проверяется до
запроса (`MapBounds.isValid`), иначе пустая карта читалась бы как «рядом
ничего нет».

Радиусный `places/nearby` карта зовёт только для первого кадра, пока области
ещё нет (в том числе когда MapKit не поднялся и кадра не будет вовсе).

**Аватара и имени автора отзыва у сервера нет вовсе** (сверено 2026-09-10,
после развода коллизии; раньше схема `Response` была перекрыта, issue
#60/#76). `ReviewResponse {id, placeId, userId, rating, text, isVerified,
helpfulCount, ownerReply, createdAt}` — ни фото, ни имени, только `userId`.
`ReviewDto` больше не гадает алиасы `userName`/`userAvatarUrl` — их снесли.
Заодно добавлены поля `isVerified`, `helpfulCount`, `ownerReply` — они есть в
`ReviewResponse`, но раньше в DTO не были описаны вовсе, поэтому молча
отбрасывались (issue #192, закрыт отрицательным ответом бэкенда про
имя/аватар). Экран
показывает отзыв без имени (плейсхолдер «гость», первая буква в аватаре — от
него), а не угаданное и всегда пустое поле. Ответ заведения (`ownerReply`)
теперь разбирается и выводится под текстом отзыва.

## FashionApi ⚠️

`app/src/main/java/uz/mahalla/feature/fashion/data/FashionApi.kt` — НЕ СВЕРЕН: писался по описанию задачи — проверить перед правкой.

| Метод | Путь |
|---|---|
| GET | `fashion/categories` |
| GET | `fashion/stores/{storeId}/catalog` |
| GET | `fashion/products/{id}` |
| GET | `fashion/cart` |
| POST | `fashion/cart/add` |
| PUT | `fashion/cart/{variantId}` |
| DELETE | `fashion/cart/{variantId}` |
| POST | `fashion/orders` |
| GET | `orders` |
| GET | `orders/{orderId}` |
| POST | `fashion/orders/{orderId}/cancel` |

`GET orders` — **общая** ручка списка заказов, не фэшн-овая: `fashion/orders/my`
отдаёт то же самое, но в схеме `OrderResponse`, а это имя в `/v3/api-docs`
перекрыто коллизией springdoc. Параметр `vertical` необязателен: с ним
приезжают заказы одной вертикали (одежда), без него — **всех**
(`FOOD`, `CLOTHING`, `PHARMACY`, `CINEMA`, `GAMING`). Так его и зовут «Мои
активности» (issue #73) — см. раздел о них в начале файла.

**`POST fashion/orders` шлёт свою схему** (расхождение найдено при сверке
2026-09-10, issue #167; исправлено в issue #221). У пути свой
`FashionPlaceOrderRequest`, отдельный от `FoodPlaceOrderRequest` «Еды»:
обязателен **`storeId`** (а не `placeId`), поля `items` нет вовсе (состав
заказа сервер берёт из серверной корзины `fashion/cart*`, которую клиент уже
ведёт). Клиент отправляет `FashionPlaceOrderRequestDto` (`storeId`,
`fulfillment`, `paymentMethod`, `deliveryAddress`).

Схема допускает ещё `deliveryLat`/`deliveryLng` и `promoCode` — клиент их
**сознательно не шлёт**: на экране оформления нет ни выбора точки на карте,
ни поля промокода. Не проверено живым запросом (`401` до валидации тела,
`CONTRACT_REFRESH_TOKEN` в CI не задан) — тело закреплено тестом
(`FashionOrderRepositoryTest`) до первой проверки под токеном.

## FoodApi ✅

`app/src/main/java/uz/mahalla/feature/food/data/FoodApi.kt` — сверен: issue #9, второй круг.

| Метод | Путь |
|---|---|
| GET | `food/places/{placeId}/menu` |
| POST | `food/orders` |
| GET | `orders/{orderId}` |
| POST | `food/orders/{orderId}/cancel` |

**Картинки у позиции меню в схеме нет вовсе** (issue #60): у `ItemResponse` ни
одного поля со ссылкой. `MenuItemDto` объявляет его на вырост под тремя
именами — `imageUrl` (бэкенд уже использует это имя у `CartItemResponse`),
`photoUrl`, `image`. Пока поле не приедет, строка меню рисуется без фото.

## FreelancerApi ⚠️ частично

`app/src/main/java/uz/mahalla/feature/freelancer/data/FreelancerApi.kt` — пути
сверены curl'ами 2026-09-04 (issue #107, таблица проб — в KDoc файла), схемы
ответов прочитаны 2026-09-10. Под токеном (`orders/my`, создание заказа) ответы
не проверены: `401` приходит до валидации.

| Метод | Путь |
|---|---|
| GET | `freelancers` |
| GET | `freelancers/{id}` |
| GET | `freelancers/{id}/services` |
| POST | `freelancers/{id}/orders` |
| GET | `freelancers/orders/my` |

**`freelancers/{id}/services` отдавала не ту схему, которой её разбирали**
(сверено 2026-09-10, issue #216, исправлено). Здесь `FreelancerServiceResponse
{id, freelancerId, title, description, priceAmount, durationMinutes,
isActive}`, а клиент до исправления разбирал ответ барберским `ServiceDto`
(`name`, `price`) — у каждой услуги мастера было пустое название и цена 0. До
развода коллизии обе ручки выглядели как одна схема `ServiceResponse`, отсюда
и ошибка; не всплыла она на стенде только потому, что каталог мастеров там
пуст. Теперь ответ разбирает свой `FreelancerServiceDto`
(`FreelancerApi.kt`), домен — общий `BarberService` барбершопа: набор полей на
экране один и тот же, а `freelancerId` уже известен вызывающей стороне.

## GamingApi ⚠️ частично

`app/src/main/java/uz/mahalla/feature/gaming/data/GamingApi.kt` — пути сверены
curl'ами по стенду 2026-09-04 (issue #98), тела под токеном — нет: `401`
приходит до валидации, а `CONTRACT_REFRESH_TOKEN` пока нет.

| Метод | Путь | |
|---|---|---|
| GET | `gaming/places/{placeId}/zones` | ✅ ручка анонимна, отдала `data: []` |
| POST | `gaming/bookings` | ✅ тело сверено схемой (2026-09-10), ответ — нужен токен |
| GET | `gaming/bookings/my` | ⚠️ путь есть (`401`), схема не проверена |

**Тело `POST gaming/bookings` сверено чтением** (2026-09-10, после развода
коллизии): `GamingBookRequest {zoneId, startTime, durationHours}` — ровно то,
что клиент угадал по ответу того же эндпоинта. Раньше это имя занимал
медицинский вариант, поэтому поля считались выведенными. Обязательны `zoneId`
и `durationHours`, `durationHours` — целое **от 1 до 24** (issue #167). Сам
ответ под токеном всё ещё не проверен — кандидат на пробу
`contract/gaming.sh`.

Отмены брони у бэкенда нет: в `gaming-controller` пять путей, `cancel` среди
них не значится, а в общем `orders` для `GAMING` только `GET`.

`startTime` уходит зоне-менее в **местном ташкентском** времени
(`2026-09-05T13:00:00` = 13:00 по часам заведения) и читается обратно так же —
`parseServerSlotInstant`. Раньше здесь был UTC; переехали в issue #144, потому
что запись к мастеру (`apptDate` + `startTime`) местная по построению, и две
трактовки на один список «Моих активностей» расходились на пять часов.
Трактовка не подтверждена стендом: **это первое, что надо проверить**, когда
появится токен, — заодно с телом `POST gaming/bookings`. Отметки события
(`createdAt` и прочие) по-прежнему читаются как UTC (`parseServerInstant`).

## HospitalApi ⚠️ частично

`app/src/main/java/uz/mahalla/feature/hospital/data/HospitalApi.kt` — пути и схемы сверены с живым `/v3/api-docs` (2026-09-10, issue #167); поведение под токеном не проверялось — нужен `CONTRACT_REFRESH_TOKEN`.

| Метод | Путь | |
|---|---|---|
| GET | `hospitals/places/{placeId}/doctors` | ✅ путь и `DoctorResponse` |
| GET | `hospitals/doctors/{id}` | ✅ путь, та же `DoctorResponse`, что и в списке (issue #181); тем же путём «мои записи» дотягивают имя врача для больничной записи (issue #219) |
| GET | `hospitals/doctors/{id}/slots?date=` | ✅ путь; `data` — `ApiResponseListString` (issue #181) |
| POST | `hospitals/appointments` | ✅ путь и `HospitalBookRequest`; ответ под токеном не проверен |
| GET | `hospitals/appointments/my` | ✅ путь; ответ под токеном не проверен |
| GET | `hospitals/appointments/{id}` | ✅ путь объявлен (issue #181); разбирается `AppointmentDto` брони — `doctorId` и `complaint` теряются, как и у остальных ответов вертикали; экран, который эту ручку показывает, — отдельная задача (#183) |
| POST | `hospitals/appointments/{id}/cancel` | ✅ путь; ответ под токеном не проверен |

**Отмена переехала на свою ручку больниц** (issue #167). До 2026-09-09 её у
`hospital-controller` не было и клиент слал отмену в общую
`POST appointments/{id}/cancel`. В схеме от 2026-09-09 своя отмена есть, и
заодно рассосалась коллизия springdoc, из-за которой обе вертикали выглядели
одной сущностью: у больниц теперь свои `HospitalBookRequest` и
`HospitalAppointmentResponse` (`{id, doctorId, apptDate, startTime, complaint,
status, createdAt}`), у брони — `AppointmentBookRequest` и
`AppointmentBookingResponse` (`{id, placeId, userId, serviceId, serviceName,
price, apptDate, startTime, endTime, status, createdAt}`). Записи разные —
значит, общая ручка чужую отменить не может.

Живой пробой это не доказать: `401` приходит до маршрутизации, оба пути
отвечают им одинаково (проверено `curl` 2026-09-10), а `CONTRACT_REFRESH_TOKEN`
не задан. Как только токен появится — контрактная проба вертикали, по образцу
`contract/booking.sh`.

Клиент по-прежнему разбирает больничные ответы DTO брони (`AppointmentDto`):
общих полей хватает на всё, что показывает экран, а `complaint` теряется —
экран его не показывает. `serviceName` у больничной записи не приходит
никогда: `doctorId` в `AppointmentDto` теперь объявлен, и «мои записи»
дотягивают имя врача отдельным запросом `GET hospitals/doctors/{id}` на
карточки без него (issue #219, `DefaultHospitalRepository.withDoctorNames`).
Список «мои активности» (`ActivityRepository`, issue #73) этот запрос не
делает — карточка записи к врачу там остаётся без подписи (issue #266).

**Слоты (issue #181, закрывает и #220).** Экран записи к врачу спрашивает
`GET hospitals/doctors/{id}/slots?date=` на каждую пару «врач + день» и
показывает ответ сервера как есть — `DoctorSchedule`, клиентская сетка
времени, ушла вместе со своим тестом. `startTime` записи уходит той же
строкой, что пришла в слоте, без разбора в `LocalTime` и повторной сборки:
лишний шаг «разобрали → собрали заново» уже один раз стоил вертикали брони
пяти часов расхождения между UTC и Asia/Tashkent (issue #144).

Ручки больниц, которые клиент по-прежнему **не** объявляет — бизнес-панельные
`POST hospitals/places/{placeId}/doctors`,
`PUT hospitals/places/{placeId}/doctors/{id}` и
`PUT hospitals/places/{placeId}/appointments/{id}/status` (эпик #16).

## MediaApi ⚠️

`app/src/main/java/uz/mahalla/feature/media/data/MediaApi.kt` — `POST` сверен: issue #101 (схема + curl'ы по стенду, форма запроса под токеном не проверялась). `GET`/`DELETE` (issue #185) объявлены **по схеме из этого же issue и `MediaFile` из `POST`**, живым запросом на стенд не перепроверены — сверить при первом расхождении.

| Метод | Путь |
|---|---|
| POST | `media/upload` |
| GET | `media/entity/{entityId}` |
| DELETE | `media/{id}` |

`multipart/form-data`, часть называется **`file`**; `entityType` и `entityId` —
необязательные query-параметры. Ответ — `MediaFile` (`id`, `url`,
`thumbnailUrl`, `type`, `fileSize`, `originalName`, `entityId`, `entityType`,
`ownerId`, `isPublic`); ответ без `url` клиент считает отказом.

**Режет nginx, а не бэкенд.** `client_max_body_size` = **1 МиБ** (значение по
умолчанию): 1000 КБ тела доходят до приложения (`401`), 1024 КБ дают
`413 Request Entity Too Large` **HTML-страницей от прокси**, то есть без
конверта `{success, error}` — показать причину человеку нечем. Поэтому размер
проверяется на клиенте до отправки (`MediaUploadLimits`), а картинка
сжимается.

`GET media/entity/{entityId}` отдаёт `List<MediaFile>` той же схемы (плюс
`createdAt`, клиентом не используется); файл без `url` в списке пропускается,
а не роняет всю галерею. `DELETE media/{id}` отвечает пустым конвертом
(`ensureSuccess`); прав на удаление в схеме нет — экран показывает кнопку
только если `ownerId` файла совпал с вошедшим, а на отказ сервера (403 и
любой другой) отвечает текстом, а не молчанием.

## NotificationsApi ✅ пути

`app/src/main/java/uz/mahalla/feature/notifications/data/NotificationsApi.kt` — пути и набор значений `type` сверены по схеме стенда (`GET /v3/api-docs`, 2026-09-09, эпик 11). Тела под токеном не сверены: `401` приходит до валидации, а `CONTRACT_REFRESH_TOKEN` пока нет.

| Метод | Путь | |
|---|---|---|
| GET | `notifications` | `PageResponseNotificationResponse` |
| GET | `notifications/unread-count` | число в `data` |
| PUT | `notifications/read-all` | `ApiResponseVoid` |
| PUT | `notifications/{id}/read` | `ApiResponseVoid` |

`NotificationResponse.type` — ровно 13 значений: `WALKIN_REQUEST`,
`WALKIN_ACCEPTED`, `WALKIN_DECLINED`, `WALKIN_COUNTER`, `WALKIN_COMPLETE`,
`APPOINTMENT_BOOKED`, `APPOINTMENT_CONFIRMED`, `APPOINTMENT_REMINDER`,
`ORDER_PLACED`, `ORDER_STATUS_UPDATED`, `REVIEW_ADDED`, `PROMOTION_CREATED`,
`SUBSCRIPTION_EXPIRES`. `NotificationType.Unknown` при этом остаётся: список
открытый, и незнакомый тип показывается, а не прячется.

### Пуши (эпик 11) — чего в контракте НЕТ

Сверено по полной схеме 2026-09-09, это не догадка:

- **ручки регистрации устройства нет** — ни `devices`, ни `push/register`, ни
  чего-либо подобного. Единственное место, куда клиент может положить токен, —
  поле `fcmToken` внутри `AuthDeviceInfo`, то есть тела `auth/send-otp`,
  `auth/verify-otp`, `auth/pin-login`, `auth/refresh`, `auth/telegram/*`.
  Ограничение поля — 500 символов. Токен из-за этого уезжает не сразу, а с
  ближайшим продлением сессии (см. `PushTokenRegistrar`, ADR 0009);
- **серверных настроек уведомлений нет** — ни категорий, ни тихих часов.
  Настройки локальные, в DataStore;
- **схемы payload'а FCM нет.** Клиент читает `data` по именам полей
  `NotificationResponse` — `id`, `type`, `entityId`, `title`, `body`
  (`PushMessage.of`). Это имена самого бэкенда, но **не подтверждённые**:
  сверить, когда бэкенд начнёт слать пуши.

**Просьба к бэкенду:** слать **data-сообщения**. Сообщение с блоком
`notification` в фоне показывает сама библиотека Firebase, минуя
`MahallaMessagingService`, — тогда не работают ни каналы по категориям, ни
тихие часы, ни переход по deep link'у на нужный экран.

## PharmacyApi ✅

`app/src/main/java/uz/mahalla/feature/pharmacy/data/PharmacyApi.kt`. `GET
products` снят живыми curl'ами 2026-09-04 (заметка «⚠️ НЕ СВЕРЕН» здесь стояла
по ошибке — сам путь в KDoc файла отмечен как проверенный, файл её не
повторял). `POST products` и `PUT products/{id}/stock` (issue #252, владелец
правит витрину) сверены схемой `/v3/api-docs` 2026-09-11, но не живым
запросом — обе требуют Bearer владельца заведения, а `CONTRACT_REFRESH_TOKEN`
в песочнице не задан.

| Метод | Путь |
|---|---|
| GET | `pharmacy/places/{placeId}/products` |
| POST | `pharmacy/places/{placeId}/products` |
| PUT | `pharmacy/places/{placeId}/products/{id}/stock` |

**`POST products`** — тело `PharmacyCreateRequest`, имя в `/v3/api-docs`
коллизией springdoc не перекрыто (встречается только в этом контроллере).
Обязательны `name` (≤ 300) и `price` (тийины, issue #149); `manufacturer`,
`description`, `dosageForm`, `strength`, `stockQuantity`,
`requiresPrescription` необязательны и без ограничения длины в схеме. Ответ —
`ProductResponse`, клиент его не использует: список товаров перечитывается
отдельным запросом (тот же приём, что у `PUT places/{id}` выше).

**`PUT products/{id}/stock`** — тело в схеме объявлено безымянной картой
(`additionalProperties: integer`), тот же случай, что `walkin/accept`/
`walkin/decline` в PR #161 и `reviews/{id}/reply` в issue #188. Имени ключа
схема не называет — выведено из соседних схем того же контроллера: и
`ProductResponse`, и `PharmacyCreateRequest` называют это поле
`stockQuantity`. Отправляется как `{"stockQuantity": N}`. **Не проверено
живым запросом** (нужен Bearer владельца заведения, которого в песочнице
нет) — если бэкенд ждёт другой ключ, тело уйдёт с полем, которого он не
узнает, и обновление молча не подействует, а не ответит ошибкой; при
расхождении смотреть сюда в первую очередь и подтвердить настоящим curl'ом
до релиза.

## SessionsApi ⚠️

`app/src/main/java/uz/mahalla/feature/profile/data/SessionsApi.kt` — НЕ СВЕРЕН: писался по описанию задачи — проверить перед правкой.

| Метод | Путь |
|---|---|
| GET | `auth/sessions` |
| POST | `auth/sessions/revoke` |
| POST | `auth/sessions/{sessionId}/trust` |

## PromotionsApi ⚠️

`app/src/main/java/uz/mahalla/feature/promotions/data/PromotionsApi.kt` — НЕ СВЕРЕН: писался по описанию задачи — проверить перед правкой.

| Метод | Путь |
|---|---|
| GET | `promotions/platform` |
| GET | `promotions/places/{placeId}` |

## WalkInApi ⚠️

`app/src/main/java/uz/mahalla/feature/queue/data/WalkInApi.kt` — НЕ СВЕРЕН: писался по описанию задачи — проверить перед правкой.

| Метод | Путь |
|---|---|
| POST | `walkin/send` |
| POST | `walkin/{id}/cancel` |

## ProviderApi ⚠️

`app/src/main/java/uz/mahalla/feature/role/data/ProviderApi.kt` — НЕ СВЕРЕН: писался по описанию задачи — проверить перед правкой.

| Метод | Путь |
|---|---|
| POST | `places` |
| GET | `places/my` |
| PUT | `places/{id}/availability` |

## SocialApi ⚠️

`app/src/main/java/uz/mahalla/feature/social/data/SocialApi.kt` — пути и формы
ответов сняты со схемы стенда (`/v3/api-docs`) и curl'ами, issue #75, но
успешный путь не проверен: все семь ручек требуют Bearer, а входа в CI нет.
Тело нового комментария springdoc описал как `Map<String,String>` — имя ключа
(`text`) взято из `CommentResponse`, это предположение.

| Метод | Путь |
|---|---|
| GET | `places/{placeId}/status` |
| POST | `places/{placeId}/like` |
| POST | `places/{placeId}/save` |
| GET | `places/{placeId}/comments` |
| POST | `places/{placeId}/comments` |
| DELETE | `comments/{id}` |
| GET | `saved-places` |

## PlaceStaffApi ✅

`app/src/main/java/uz/mahalla/feature/role/data/PlaceStaffApi.kt` — сверен: issue #189 (`/v3/api-docs`, 2026-09-11).

| Метод | Путь |
|---|---|
| GET | `places/{placeId}/staff` |
| POST | `places/{placeId}/staff` |
| PUT | `places/{placeId}/staff/{staffUserId}` |
| DELETE | `places/{placeId}/staff/{staffUserId}` |

`role` — закрытое перечисление **`STAFF`/`MANAGER`/`OWNER`**, то же самое, что
уже приезжает в `Mine.role` у «моих заведений» (`ProviderApi.myPlaces`,
issue #94) — второй домен-тип под тот же смысл не заводился, клиент
переиспользует `PlaceStaffRole`. Схемы `PlaceStaffResponse`, `AddRequest`,
`PlaceStaffChangeRoleRequest` в `/v3/api-docs` встречаются по одному разу,
коллизии springdoc здесь нет.

`PUT`/`DELETE` адресуют сотрудника по `{staffUserId}` — это `userId`, а не
`id` записи `PlaceStaffResponse`; клиент `id` записи в домен не переводит,
им всё равно нечего было бы делать. Найти пользователя по телефону схема не
даёт (поиска по `users` нет) — ID в форму добавления вводится вручную.

`geoExempt` (`boolean`, необязательный и в запросе, и в ответе) разобран
DTO→домен, но в интерфейсе не показан: задача его не требовала.

## SubscriptionsApi ⚠️

`app/src/main/java/uz/mahalla/feature/subscription/data/SubscriptionsApi.kt` — пути и поля сверены со схемой стенда (`/v3/api-docs`, issue #103 от 2026-09-04, перепроверено 2026-09-08). **Успешные ответы под токеном не проверены**: все семь ручек требуют Bearer, а SMS-кода в CI нет — приходит `401` до валидации тела.

| Метод | Путь | Параметры |
|---|---|---|
| GET | `subscriptions/plans` | query `audience` (`USER`\|`BUSINESS`, дефолт `USER`) → `[PlanResponse]` |
| GET | `subscriptions/current` | → `UserSubscriptionResponse`; пустой `data`, `404` и код `*NOT_FOUND` = «подписки нет» |
| POST | `subscriptions/subscribe` | тело `{planCode, billingPeriod}` (`MONTHLY`\|`YEARLY`) |
| POST | `subscriptions/business/subscribe` | то же тело, своя ручка для `audience=BUSINESS` |
| POST | `subscriptions/trial` | **query** `planCode`, тела нет |
| POST | `subscriptions/cancel` | **query** `reason` (у сервера свой дефолт), тела нет |
| PUT | `subscriptions/auto-renew` | тело `{autoRenew}` |

## PaymentsApi ⚠️

`app/src/main/java/uz/mahalla/feature/subscription/data/PaymentsApi.kt` — сверен со схемой стенда 2026-09-08, живым ответом нет (ручка под Bearer).

| Метод | Путь | Параметры |
|---|---|---|
| GET | `payments/transactions` | query `page`/`size` (дефолт `0`/`20`) → `PageResponsePaymentTransaction` |

Что важно:

- **Фильтра по назначению у ручки нет** — приезжают все платежи человека, и
  списания за подписку (эпик 9.3) отбираются на клиенте по `purpose`.
- Отдаёт **сырую сущность** `PaymentTransaction` (`provider` из
  `PAYME|CLICK|UZUM|CASH`, `status` из `PENDING|PAID|FAILED|CANCELLED|REFUNDED`,
  `purpose`, `purposeId`, `errorMessage`) — **без пары `amountSom`**, поэтому
  единицу `amount` вывести нечем и она читается как тийины (у кошелька она
  выводится из пары, issue #62). **Проверить первым же живым ответом.**
- `GET payments/subscription` не используется: отдаёт строго меньше, чем
  `subscriptions/current` (`plan` перечислением, без `daysRemaining`,
  `isTrial` и грейс-периода). `POST payments/subscription/activate` принимает
  `Map<String,String>` — поля неизвестны, использовать нечем.

## AppVersionApi ⚠️

`app/src/main/java/uz/mahalla/feature/update/data/AppVersionApi.kt` — НЕ СВЕРЕН: писался по описанию задачи — проверить перед правкой.

| Метод | Путь |
|---|---|
| POST | `app/version/check` |
| POST | `app/version/skip` |

## WalletApi ⚠️

`app/src/main/java/uz/mahalla/feature/wallet/data/WalletApi.kt` — НЕ СВЕРЕН: писался по описанию задачи — проверить перед правкой.

| Метод | Путь |
|---|---|
| GET | `wallet` |
| GET | `wallet/transactions` |
| POST | `wallet/top-up` |

`TopUpRequest.amount` — в тийинах, минимум `100000` (1 000 сум); человек
вводит сумы, `Money.somToTiyin` переводит в репозитории (issue #149).

**Ручки «списать с кошелька» нет и не ожидается.** Кошелёк — это
`paymentMethod = WALLET` внутри запроса вертикали (`POST food/orders`,
`POST fashion/orders`, `POST cinema/sessions/{id}/buy`, `POST appointments`,
`POST subscriptions/subscribe`), деньги списывает сервер при создании заказа.
Клиентская часть 8.3 (эпик #12) — вокруг этого запроса: проверка «доступно»
из `GET wallet`, подтверждение PIN/биометрией и один запрос на одно
подтверждение (`feature/wallet/ui/pay/WalletPaymentFlow`).

**Коды отказа кошелька не сверены.** `WalletPaymentGuard` узнаёт
`INSUFFICIENT_FUNDS` / `INSUFFICIENT_BALANCE` / `WALLET_INSUFFICIENT_FUNDS` /
`NOT_ENOUGH_FUNDS` / `NOT_ENOUGH_BALANCE` и `WALLET_BLOCKED` / `WALLET_FROZEN` /
`WALLET_SUSPENDED` / `WALLET_INACTIVE` — набор написан по догадке. Незнакомый
код показывается текстом сервера, а не подменяется «пополните кошелёк»:
соврать про причину хуже, чем показать чужую формулировку.

**`Idempotency-Key` — клиентское дополнение, серверная поддержка не
подтверждена.** `POST food/orders` уходит с этим заголовком (один ключ на одну
оплату, тот же — на повтор после оборванного соединения); в `/v3/api-docs`
заголовка нет. Сервер, который его игнорирует, ведёт себя как раньше — защиту
от двойного списания сейчас держит клиент: второй запрос при неответившем
первом не отправляется, и после успеха — тоже. Нужен ответ бэкенда: читает ли
он заголовок и под каким именем.

---

## Как сверять

Руками не надо — есть харнесс. Пилот пока на одной вертикали (`booking`),
остальные добавляются по образцу.

```bash
CONTRACT_REFRESH_TOKEN=<refresh живого аккаунта> contract/booking.sh
```

Скрипт дёргает ручки вертикали по этому файлу и складывает ответы стенда
в `app/src/test/resources/contract/<вертикаль>/`. Дальше их разбирает
`*ContractTest` в обычном `testDebugUnitTest` — сверяет **имена полей** с DTO,
потому что разбор в проекте мягкий (`ignoreUnknownKeys`, всё nullable) и
простое «разобралось» молча пропустит и новое поле сервера, и пропавшее.
Фикстуры коммитятся: снятая один раз проба работает без стенда.

Отдельно от проб — сверка **множеств путей**: какие ручки вообще есть по обе
стороны. Токена не требует, `/v3/api-docs` отдаётся анонимно.

```bash
contract/paths.sh                 # сверка с живым стендом
contract/paths.sh --client-only   # только вызовы приложения, без сети
```

Скрипт разбирает аннотации Retrofit по `app/src/main/**/*Api.kt`, снимает
пути со схемы стенда, нормализует плейсхолдеры и печатает три числа:
совпало, «клиент зовёт, а у бэкенда нет» (это дефект — код возврата 1) и
«у бэкенда есть, клиент не зовёт» (это норма: бизнес-панель и `admin/*`).
Сбой отличается от находки: стенд недоступен или отдал не схему — код 2, не
разобрался ни один вызов клиента или скрипт вызван неверно — код 3. В режиме
`--client-only` stdout — чистый список путей, счётчики уходят в stderr. Путь в аннотации с ведущим `/`
считается дефектом: Retrofit шлёт его от корня хоста, мимо `/api/v1/`.
Замер 2026-09-10 на `main` после мержа #233 и #229: 81 объявление, 80
уникальных путей, 80 из 80 совпало, выдуманных ручек ноль, невостребованных
100. Чего он **не** ловит сам по себе — появление новой ручки: для этого
нужен регулярный прогон, см. `docs/TASKS-BACKLOG.md`, T17.

В CI то же самое — workflow **Contract Check** (Actions → Run workflow):
прогоняет пробы, затем отдаёт результат Claude, чтобы тот разобрал
расхождения, поправил DTO, обновил статусы здесь и открыл PR.

Что нужно от стенда:

- он должен быть жив (утром 2026-09-08 отдавал `502` на всё, к вечеру того же
  дня поднялся: `/actuator/health` и `/v3/api-docs` — `200`);
- каталог должен быть наполнен — иначе не найти заведения с услугами
  (обойти можно переменной `CONTRACT_BOOKING_PLACE_ID`). На 2026-09-08 вечером
  `places/nearby` по центру Ташкента отдаёт 25 заведений, услуги есть у двух
  (`Gentleman Barbershop`, `Style Cut Barber`) — то есть проба находит их сама;
- **секрет `CONTRACT_REFRESH_TOKEN`** — без него всё, что под токеном,
  пропускается, а это большая часть контракта. Идеально — тестовый номер
  с фиксированным OTP в dev-профиле, тогда харнесс сможет логиниться сам.

Альтернатива живому стенду — docker-образ: в `claude-dev.yml` бэкенд
поднимается на `http://localhost:8080`, если задан `BACKEND_IMAGE`.

Расхождение — в issue и в этот файл, статус меняется на ✅ со ссылкой.

Источник истины по бэкенду — `MAHALLA-IMPLEMENTATION.md` и
`PROJECT-STATUS-*.md` в `jack5505/mahalla` (в CI: `design-repo/`).
