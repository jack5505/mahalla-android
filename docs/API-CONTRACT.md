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

**Деньги — в тийинах**, 1 сум = 100 тийин; делить на 100 перед показом.
Это не вывод, а прямая цитата из `info.description` живого `/v3/api-docs`
(сверено 2026-09-10):

> Barcha butun sonli pul maydonlari **tiyin**da uzatiladi: 1 so'm = 100 tiyin.
> Ko'rsatishdan oldin 100 ga bo'lish kerak: `5000000` → `50 000 so'm`.

Это закрывает вопрос, который висел открытым по всем вертикалям: «Еда»,
бронирование, гейминг, акции считали сумами, то есть **рисуют суммы в сто раз
больше настоящих**. Вопрос был заведён как issue #149 — там же ответ и разбор,
какие экраны править. Правка сквозная и в скоуп одной вертикали не влезает.

Заметьте: `WalletAmounts.scaleOf` общим делителем **не является** — он
*выводит* масштаб из пары «целое + дробный близнец» и может вернуть `1`
(сумы), что теперь противоречит документированному правилу. Приводить его к
жёсткой сотне — часть той же задачи #149, наугад тут менять нечего.

**Коллизия springdoc разведена** (сверено 2026-09-10). Раньше одно имя схемы
(`BookRequest`, `ServiceResponse`, `Response`) занимали сразу несколько
вертикалей, побеждала одна, и половина DTO в клиенте была **выведена**, а не
прочитана (issue #76, #84, #97). Теперь имена уникальны — 255 схем, ни одного
`BookRequest`/`ServiceResponse`/`Response` без префикса вертикали:
`AppointmentBookRequest` / `GamingBookRequest` / `HospitalBookRequest`,
`AppointmentServiceResponse` / `FreelancerServiceResponse`. Значит **любое
«имена выведены из схемы» ниже теперь можно проверить чтением** — где это
уже сделано, отмечено датой.

---

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
=======
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

**`price` — тийины**, как и все целые денежные поля (см. «Общее для всех
запросов»). Стенд отдаёт за стрижку `5000000`, за подравнивание бороды
`3000000` — это 50 000 и 30 000 сум. Клиент считает сумами и рисует
«5 000 000 so'm»; вопрос закрыт бэкендом, правка — в сквозной задаче #149.

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

**Аватара автора отзыва у сервера нет вовсе** (сверено 2026-09-10, после
развода коллизии; раньше схема `Response` была перекрыта, issue #60/#76).
`ReviewResponse {id, placeId, userId, rating, text, isVerified, helpfulCount,
ownerReply, createdAt}` — ни фото, ни имени, только `userId`. Три имени
(`userAvatarUrl`, `avatarUrl`, `userAvatar`), под которыми `ReviewDto` ищет
поле, ни одному ничего не соответствует — как и трём именам автора. Экран не
ломается: пустое имя подменяется словом «аноним», и в аватаре видна его первая
буква. Но настоящего автора у отзыва на экране нет и не будет, пока бэкенд не
скажет, чем его называть — живой баг, issue #192.

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

**`freelancers/{id}/services` отдаёт НЕ ту схему, которой её разбирают**
(сверено 2026-09-10). Здесь `FreelancerServiceResponse {id, freelancerId,
title, description, priceAmount, durationMinutes, isActive}`, а клиент
разбирает ответ барберским `ServiceDto` (`name`, `price`) — у каждой услуги
мастера будет пустое название и цена 0. До развода коллизии обе ручки
выглядели как одна схема `ServiceResponse`, отсюда и ошибка; не всплыла она
только потому, что каталог мастеров на стенде пуст. Живой баг, issue #216.

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
медицинский вариант, поэтому поля считались выведенными. Сам ответ под токеном
всё ещё не проверен — кандидат на пробу `contract/gaming.sh`.
=======
**Тело `POST gaming/bookings` подтверждено схемой** (2026-09-10, issue #167).
Раньше оно было объявлено как `BookRequest` — имя делили три пути (коллизия
springdoc), и поля были названы по ответу того же эндпоинта. Теперь у пути своя
`GamingBookRequest`, и догадка совпала: `{zoneId, startTime, durationHours}`,
обязательны `zoneId` и `durationHours`, `durationHours` — целое **от 1 до 24**.
Что бэкенд с запросом сделает, всё ещё не проверено: кандидат на пробу
`contract/gaming.sh`, как только появится токен.

Отмены брони у бэкенда нет: в `gaming-controller` пять путей, `cancel` среди
них не значится, а в общем `orders` для `GAMING` только `GET`.

`startTime` уходит зоне-менее в UTC (`2026-09-05T13:00:00`) — согласовано с
`parseServerInstant`, который читает зоне-менее время сервера как UTC.

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
