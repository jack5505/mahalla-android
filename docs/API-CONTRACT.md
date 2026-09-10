# Контракт бэкенда

Что клиент реально вызывает — извлечено из `*Api.kt` в коде (2026-09-06).
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
Сами эндпоинты `auth/*` ходят на `@RefreshClient` — клиент без authenticator'а.

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

## BookingApi ⚠️ частично

`app/src/main/java/uz/mahalla/feature/booking/data/BookingApi.kt` — сверен пробой `contract/booking.sh` (2026-09-08), но только анонимная половина: всё под токеном требует `CONTRACT_REFRESH_TOKEN`, а его пока нет.

| Метод | Путь | |
|---|---|---|
| GET | `barber-services/places/{placeId}` | ✅ |
| GET | `barber-services/places/{placeId}/slots` | ✅ |
| POST | `appointments` | ⚠️ не проверено — нужен токен |
| GET | `appointments/my` | ⚠️ не проверено — нужен токен |
| POST | `appointments/{id}/cancel` | ⚠️ не проверено — нужен токен |

**`ServiceResponse` — имена были угаданы неверно.** Стенд отдаёт
`{id, name, colorHex, price, durationMinutes}`, а клиент до 2026-09-08 ждал
`title` и `priceAmount`: у каждой услуги на экране записи пропадали название
и цена. Исправлено вместе с этой пробой; фикстура —
`app/src/test/resources/contract/booking/services.json`.

`description` и `freelancerId` стенд не шлёт вовсе — из DTO убраны.
`isActive`/`active` в пробе не встретились ни разу, но пара оставлена: две
активные услуги одного заведения — не доказательство, что флага не бывает.

Слоты подтверждены как **массив строк** вида `"09:00"` — не объекты.

**Тело `POST appointments` подтверждено схемой** (2026-09-10, issue #167).
Коллизия springdoc вокруг имени `BookRequest` рассосалась, у пути появилась
своя `AppointmentBookRequest`: `{placeId, serviceId, serviceName, date,
startTime}`, обязательны `placeId`, `date`, `startTime`. Выведенные имена
`{placeId, serviceId, date, startTime}` совпали — правка `BookAppointmentRequest`
не нужна; необязательное `serviceName` в теле клиент не шлёт.

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
(issue #149; фикстура `app/src/test/resources/contract/booking/services.json`).

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
| GET | `search` |
| GET | `places/{id}` |
| GET | `reviews/places/{placeId}` |
| POST | `reviews` |
| DELETE | `reviews/{id}` |

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

**Аватар автора отзыва не сверен** (issue #60): схема `Response` в
`/v3/api-docs` перекрыта коллизией springdoc (issue #76), поэтому `ReviewDto`
разбирает поле под тремя именами — `userAvatarUrl`, `avatarUrl`, `userAvatar`.
Молчание сервера — первая буква имени вместо фото, экран не ломается.

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

**Тело `POST fashion/orders` расходится со схемой — заказ, вероятно, не
оформляется** (найдено при сверке 2026-09-10, issue #167; чинится в issue
#221). Клиент шлёт туда `PlaceOrderRequestDto` «Еды» (`{placeId, items,
fulfillment, paymentMethod, deliveryAddress}`), а путь ссылается на свой
`FashionPlaceOrderRequest`: обязателен **`storeId`**, поля `items` нет вовсе
(состав берётся из серверной корзины `fashion/cart*`), зато есть
`deliveryLat`, `deliveryLng` и `promoCode`. У «Еды» своя
`FoodPlaceOrderRequest` (`placeId` + `items` обязательны) — одной схемы на два
пути больше нет.

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

## FreelancerApi ⚠️

`app/src/main/java/uz/mahalla/feature/freelancer/data/FreelancerApi.kt` — НЕ СВЕРЕН: писался по описанию задачи — проверить перед правкой.

| Метод | Путь |
|---|---|
| GET | `freelancers` |
| GET | `freelancers/{id}` |
| GET | `freelancers/{id}/services` |
| POST | `freelancers/{id}/orders` |
| GET | `freelancers/orders/my` |

`freelancers/{id}/services` отдаёт ту же схему `ServiceResponse` и разбирается
тем же `ServiceDto`, что и `barber-services` — значит переехал на выверенные
`name`/`price`. Пробой именно этой ручки это пока не подтверждено.

## GamingApi ⚠️ частично

`app/src/main/java/uz/mahalla/feature/gaming/data/GamingApi.kt` — пути сверены
curl'ами по стенду 2026-09-04 (issue #98), тела под токеном — нет: `401`
приходит до валидации, а `CONTRACT_REFRESH_TOKEN` пока нет.

| Метод | Путь | |
|---|---|---|
| GET | `gaming/places/{placeId}/zones` | ✅ ручка анонимна, отдала `data: []` |
| POST | `gaming/bookings` | ⚠️ путь есть (`401`), тело не проверено |
| GET | `gaming/bookings/my` | ⚠️ путь есть (`401`), схема не проверена |

**Тело `POST gaming/bookings` подтверждено схемой** (2026-09-10, issue #167).
Раньше оно было объявлено как `BookRequest` — имя делили три пути (коллизия
springdoc), и поля были названы по ответу того же эндпоинта. Теперь у пути своя
`GamingBookRequest`, и догадка совпала: `{zoneId, startTime, durationHours}`,
обязательны `zoneId` и `durationHours`, `durationHours` — целое **от 1 до 24**.
Что бэкенд с запросом сделает, всё ещё не проверено: кандидат на пробу
`contract/gaming.sh`, как только появится токен.

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
| POST | `hospitals/appointments` | ✅ путь и `HospitalBookRequest`; ответ под токеном не проверен |
| GET | `hospitals/appointments/my` | ✅ путь; ответ под токеном не проверен |
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
общих полей хватает на всё, что показывает экран, а `doctorId` и `complaint`
теряются. Отсюда же следует, что `serviceName` у больничной записи не придёт
никогда — на экране «мои записи» она останется без имени врача (issue #219).

Ручки больниц, которые клиент **не** объявляет: `GET hospitals/doctors/{id}`,
`GET hospitals/doctors/{id}/slots?date=` (`ApiResponseListString` — реальные
свободные слоты; приложение вместо них рисует сетку времени из
`DoctorSchedule`, issue #220), `GET hospitals/appointments/{id}`, а также
бизнес-панельные
`POST hospitals/places/{placeId}/doctors`,
`PUT hospitals/places/{placeId}/doctors/{id}` и
`PUT hospitals/places/{placeId}/appointments/{id}/status` (эпик #16).

## MediaApi ✅

`app/src/main/java/uz/mahalla/feature/media/data/MediaApi.kt` — сверен: issue #101 (схема + curl'ы по стенду, форма запроса под токеном не проверялась).

| Метод | Путь |
|---|---|
| POST | `media/upload` |

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

`GET media/entity/{entityId}` и `DELETE media/{id}` у бэкенда есть, но клиентом
**не объявлены**: показывать и редактировать загруженное пока нечем.

## NotificationsApi ⚠️

`app/src/main/java/uz/mahalla/feature/notifications/data/NotificationsApi.kt` — НЕ СВЕРЕН: писался по описанию задачи — проверить перед правкой.

| Метод | Путь |
|---|---|
| GET | `notifications` |
| GET | `notifications/unread-count` |
| PUT | `notifications/read-all` |
| PUT | `notifications/{id}/read` |

## PharmacyApi ⚠️

`app/src/main/java/uz/mahalla/feature/pharmacy/data/PharmacyApi.kt` — НЕ СВЕРЕН: писался по описанию задачи — проверить перед правкой.

| Метод | Путь |
|---|---|
| GET | `pharmacy/places/{placeId}/products` |

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

## SubscriptionsApi ⚠️

`app/src/main/java/uz/mahalla/feature/subscription/data/SubscriptionsApi.kt` — НЕ СВЕРЕН: писался по описанию задачи — проверить перед правкой.

| Метод | Путь |
|---|---|
| GET | `subscriptions/plans` |
| GET | `subscriptions/current` |
| POST | `subscriptions/subscribe` |
| POST | `subscriptions/business/subscribe` |
| POST | `subscriptions/trial` |
| POST | `subscriptions/cancel` |
| PUT | `subscriptions/auto-renew` |

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
Замер 2026-09-10: 77 из 77 совпало, выдуманных ручек ноль, невостребованных
103. Чего он **не** ловит сам по себе — появление новой ручки: для этого
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
