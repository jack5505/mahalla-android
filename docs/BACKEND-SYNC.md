# Сверка бэкенда и Android (issue #92)

Снимок: **2026-09-09**. Контракт снят со стенда
`https://189-74-96-232.nip.io/v3/api-docs` и проверен прямыми curl'ами
(дизайн-репозиторий агенту в CI по-прежнему недоступен — `DESIGN_REPO_PAT`
не задан).

**В схеме 180 эндпоинтов в 32 контроллерах. Приложение зовёт 77 — 43 %.**

Снимок берётся из `*Api.kt`: 77 уникальных пар «метод + путь» в Retrofit-
аннотациях. Совпадение считается по методу и пути с нормализованными
`{параметрами}`.

## Динамика

| Снимок | Ручек у бэкенда | Зовёт приложение | Покрытие |
|---|---|---|---|
| 2026-08-30 (`UI-INVENTORY.md`, #57) | 129 | 12 | 9 % |
| 2026-09-04 (первый прогон #92) | 160 | 30 | 19 % |
| **2026-09-09 (этот)** | **180** | **77** | **43 %** |

**Разрыв впервые сокращается.** За пять дней бэкенд вырос на 20 ручек,
приложение подключило 47. Из 16 задач первого прогона #92 (#93–#108) закрыто
14 — вместе с ними доехали восемь вертикалей. Открытыми остаются **#102**
(серверный PIN и app-lock) и **#105** (соцфункции, частично блокировано #88):
это ровно те два контроллера, что стоят в таблице ниже с нулевым покрытием.

Выдуманных путей по-прежнему нет: **все 77 путей, которые зовёт приложение,
есть в схеме.**

> Первый прогон #92 (4 сентября) документ подготовил, но PR из ветки
> `claude/issue-92-20260904-0805` не открывали, и в `main` он не попал. Этот
> файл написан заново по свежему снимку, а не дополняет тот.

---

## 1. Из чего состоят оставшиеся 103 ручки

Не всё непокрытое — работа для этого приложения. Разбивка:

| Группа | Ручек | В скоупе Android |
|---|---|---|
| **Пользовательские** | **35** | да — это и есть задачи ниже |
| Заказы вертикалей, закрытые общим `GET /orders` | 4 | нет — уже работает |
| Бизнес-панель (эпик #16) | 45 | да, но отдельным эпиком |
| Админка (`admin/*`, `auth/admin/*`) | 16 | нет |
| Колбэки платёжных провайдеров | 2 | нет — сервер-сервер |
| `POST /auth/otp/request` (алиас) | 1 | нет — приложение зовёт `auth/send-otp` |

Четыре ручки во второй строке — `food/orders/my`, `food/orders/{orderId}`,
`fashion/orders/my`, `fashion/orders/{orderId}`. Формально не зовутся, но
дырой не являются: приложение берёт заказы всех вертикалей одним списком
через `GET /orders` (`order-controller` 2/2) — так сделано осознанно, см.
`feature/fashion/data/FashionApi.kt`. Подключать вертикальные дубликаты
незачем, пока не выяснится, что общий список чего-то не отдаёт (#148).

Главный вывод изменился по сравнению с прошлым снимком. Тогда дырой были
целые вертикали; теперь **вертикали подключены, а не хватает второго слоя**:
слотов, деталей, денег в корзине и бизнес-панели.

---

## 2. Покрытие по контроллерам

| Контроллер | Покрытие | Что осталось |
|---|---|---|
| notification | 4/4 | — |
| order | 2/2 | — |
| search | 1/1 | — |
| subscription | 7/7 | — |
| bank-auth | 9/11 | `pin-resume`, `session/check` → #102 |
| review | 3/4 | ответ заведения (бизнес) |
| wallet | 3/4 | `wallet/business` (бизнес) |
| fashion | 9/15 | «мои заказы» и карточка — дубли `GET /orders`; остальное бизнес |
| place | 5/8 | `places?ids=`, `map-bounds` (#168); `PUT places/{id}` бизнес |
| appointment | 5/8 | карточка записи; остальное бизнес |
| gaming | 3/5 | обе — бизнес |
| media | 1/3 | галерея сущности, удаление файла |
| promotion | 2/4 | `promotions/check`; создание акции — бизнес |
| auth (telegram) | 2/4 | обе — админ |
| app-version | 2/6 | четыре — админ |
| cinema | 5/11 | карточка фильма и билета; остальное бизнес |
| hospital | 3/10 | **карточка врача и слоты** (#181), карточка приёма (#183), отмена (#167) |
| pharmacy | 1/3 | обе — бизнес |
| food | 3/12 | **`delivery-fee`**; «мои заказы» и карточка — дубли `GET /orders`; остальное бизнес |
| freelancer | 5/13 | восемь — кабинет мастера (бизнес) |
| walk-in | 2/7 | пять — панель мастера (бизнес) |
| **pin-code** | **0/7** | целиком → #102 |
| **social** | **0/7** | целиком → #105, частично блокировано #88 |
| **user** | **0/2** | `GET/PUT users/me` → #170 |
| **payment** | **0/5** | история платежей и оплата подписки; 2 из 5 — колбэки |
| **place-staff** | **0/4** | целиком — бизнес |
| analytics | 0/2 | `track` → #169; дашборд — бизнес, блокирован #163 |
| admin-* (4 контроллера) | 0/10 | админка, вне скоупа |
| otp-request-alias | 0/1 | алиас, не нужен |

---

## 3. Что изменилось в контракте с прошлого снимка

**Исправлено бэкендом:**

- **Коллизии имён схем в springdoc разведены — все, а не только `BookRequest`.**
  В схеме больше нет ни одного из голых имён, на которые жаловались #9, #76 и
  прошлый прогон #92: `Response`, `CreateRequest`, `OrderResponse`,
  `BookRequest` отсутствуют как таковые. Вместо них:

  | Было склеено | Стало |
  |---|---|
  | `BookRequest` (3 пути) | `GamingBookRequest {zoneId, startTime, durationHours}`, `AppointmentBookRequest {placeId, serviceId, serviceName, date, startTime}`, `HospitalBookRequest {doctorId, date, startTime, complaint}` |
  | `Response` | `ReviewResponse`, `DoctorResponse`, … |
  | `CreateRequest` | `ReviewCreateRequest {placeId, rating, text, appointmentId}` |
  | `OrderResponse` | `FoodOrderResponse`, `FashionOrderResponse` |

  Замечание №2 прошлого прогона снято, и угадывать имена полей вертикалей
  больше не нужно.

- **Разведённый `ReviewResponse` вскрыл живой баг → #192.** Раз коллизии нет,
  видно, что угаданные под ней имена неверны:

  ```
  ReviewResponse = {id, placeId, userId, rating, text,
                    isVerified, ownerReply, helpfulCount, createdAt}
  ```

  Полей `userName`, `author`, `authorName`, `userAvatarUrl`, `avatarUrl`,
  `userAvatar` нет ни одного, а `ReviewDto` в
  `feature/discovery/data/CatalogApi.kt` разбирает именно их — под шестью
  именами сразу. У обоих полей дефолты, поэтому разбор не падает: **имя автора
  в списке отзывов всегда пустое, аватар всегда `null`**. Заодно не
  разбираются три новых поля: `isVerified`, `ownerReply` (ответ заведения),
  `helpfulCount`.

  Урок общий: алиасы `@JsonNames`, поставленные «на случай коллизии», ошибку не
  чинят, а прячут — при разведении схемы их надо снимать сверкой, а не ждать
  жалобы с экрана.

**Появилось нового и уже закрыто приложением:**

- Гейт `403 GEO_PERMISSION_REQUIRED` на публичных ручках. Без заголовков
  `X-Geo-Lat` / `X-Geo-Lng` не отвечает даже `GET /cinema/movies`. В
  приложении это `GeoHeaderInterceptor` (issue #53) — проверено, дыры нет.

**Появилось нового и не подключено:**

- `GET /food/delivery-fee?itemsAmount=` — стоимость доставки до оформления.
  Проверено: `200 {"deliveryAmount": 10000}` при `itemsAmount=50000`.
- `GET /places?ids=` — заведения пачкой, ответ `Summary` с `name`.
- `GET /hospitals/doctors/{id}/slots?date=` — свободные слоты врача,
  ответ `data: ["09:00", ...]` (список строк).
- `hospital-controller` вырос с 4 до 10 ручек, `food` — с 9 до 12.

**Не исправлено (замечания прошлого прогона в силе):**

1. `GET /places/my` без токена по-прежнему отвечает `500 INTERNAL_ERROR`, а не
   `401` — воспроизведено сегодня, неотличимо от падения сервиса.
2. Тело `POST /cinema/sessions/{sessionId}/buy` по-прежнему не описано
   (`additionalProperties: string`) — из схемы не следует ни как выбрать
   места, ни сколько билетов.
3. Новое: `POST /food/orders` **не принимает `promoCode`**, хотя
   `POST /fashion/orders` принимает. Промокод в «Еде» приложить к заказу
   нечем — комментарий в `feature/food/domain/Cart.kt` про это остаётся верным.

---

## 4. Проверенные контракты для заведённых задач

Сняты со схемы и, где ручка публичная, подтверждены curl'ом с гео-заголовками.

```
GET /api/v1/food/delivery-fee?itemsAmount=50000
    → 200 {"success":true,"data":{"deliveryAmount":10000}}
    ответ — Map<String,Long>, не объект с фиксированной формой

GET /api/v1/promotions/check?code=&placeId=&orderAmount=   (все три обязательны)
    → CheckResponse {valid, discountAmount, finalAmount, promoCode}

GET /api/v1/hospitals/doctors/{id}/slots?date=   (date обязателен)
    → data: List<String> — время в строках
GET /api/v1/hospitals/doctors/{id}
    → DoctorResponse {id, name, specialty, bio, consultationPrice}

GET /api/v1/places?ids=<uuid>&ids=<uuid>   (401 без токена)
    → List<Summary> {id, name, category, address, lat, lng, isAvailable,
      ratingAvg, ratingCount, distanceMeters, logoUrl, subscriptionPlan}

GET /api/v1/users/me → MeResponse {id, phone, fullName, avatarUrl, language,
      role, verificationStatus, accountStatus, telegramLinked, lastLoginAt}
PUT /api/v1/users/me ← UpdateMeRequest {fullName, avatarUrl}

GET  /api/v1/pin/status?deviceId=   → PinStatusResponse {pinSet,
       biometricEnabled, lockedSecondsRemaining, pinChangedAt, lastUsedAt}
POST /api/v1/pin/set     ← {pin, otpToken, otpCode, deviceId}
POST /api/v1/pin/verify  ← {pin, deviceId, purpose}
       → {success, actionToken, remainingAttempts, lockedSecondsRemaining, message}
PUT  /api/v1/pin/change  ← {currentPin, newPin, deviceId}
PUT  /api/v1/pin/biometric ← {enabled, deviceId, pin}
POST /api/v1/pin/reset   ← {newPin, otpToken, otpCode, deviceId}
DELETE /api/v1/pin?deviceId=

GET /api/v1/payments/transactions?page&size
    → PageResponse<PaymentTransaction {id, userId, provider, externalOrderId,
      amount, status, purpose, purposeId, errorMessage,
      createdAt, updatedAt, createdBy, updatedBy}>

GET /api/v1/media/entity/{entityId}
    → List<MediaFile {id, ownerId, entityId, entityType, url, thumbnailUrl,
      type, fileSize, originalName, isPublic,
      createdAt, updatedAt, createdBy, updatedBy}>

ReviewResponse {id, placeId, userId, rating, text, isVerified, ownerReply,
      helpfulCount, createdAt}          — имени и аватара автора нет, см. #192
DELETE /api/v1/media/{id}
```

**`placeId` везде `uuid`**, не число. Все защищённые ручки без токена отдают
`401 UNAUTHORIZED` — кроме `places/my` (см. §3).

---

## 5. Заведённые задачи

Метка `ai-task`, порядок — предлагаемый приоритет.

**Пользовательское: экран уже есть, ручка не подключена**

| | Задача |
|---|---|
| #179 | «Еда»: стоимость доставки в корзине (`food/delivery-fee`) — сейчас ноль |
| #180 | «Одежда»: промокод в чекауте (`promotions/check` + `promoCode` в заказе) |
| #181 | «Больницы»: карточка врача и свободные слоты — запись уходит вслепую |
| #182 | Названия заведений пачкой (`places?ids=`) — снимает клиентскую часть #150 |
| #183 | Экраны деталей: фильм, билет, запись, приём |
| #184 | Кошелёк: история платежей и оплата подписки (`payments/*`) |
| #185 | Медиа: галерея сущности и удаление файла (`media/entity`, `DELETE media`) |
| #192 | **Баг:** имя и аватар автора отзыва не приезжают — `ReviewDto` читает несуществующие поля |

Плюс уже открытые: #102 серверный PIN, #105 соцфункции, #167 отмена приёма,
#168 `map-bounds`, #169 `analytics/track`, #170 `users/me`.

**Бизнес-панель (эпик #16)** — 45 ручек, ни одной подключённой:

| | Задача |
|---|---|
| #186 | Панель мастера: очередь walk-in (дашборд, принять/отклонить/начать/завершить) — блокировано #163 |
| #187 | Заказы заведения: приём и смена статуса (еда и одежда) |
| #188 | Витрина заведения: меню, товары аптеки, товары магазина, карточка места |
| #189 | Сотрудники заведения (`places/{placeId}/staff`) — самая маленькая, разумный первый шаг |
| #190 | Кабинет мастера (`freelancers/me/*`) |

**Бэкенду** — #191 (NEEDS-PARTNER): `places/my` отдаёт 500 вместо 401, тело
`cinema/sessions/{id}/buy` не описано, `food/orders` не принимает промокод.

Вне списка остаётся админка (`admin/*`, `auth/admin/*`, 16 ручек) — в скоуп
Android не входит.
