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

## BookingApi ⚠️

`app/src/main/java/uz/mahalla/feature/booking/data/BookingApi.kt` — НЕ СВЕРЕН: писался по описанию задачи — проверить перед правкой.

| Метод | Путь |
|---|---|
| GET | `barber-services/places/{placeId}` |
| GET | `barber-services/places/{placeId}/slots` |
| POST | `appointments` |
| GET | `appointments/my` |
| POST | `appointments/{id}/cancel` |

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

`app/src/main/java/uz/mahalla/feature/discovery/data/CatalogApi.kt` — сверен: issue #53 — реальные эндпоинты и координаты.

| Метод | Путь |
|---|---|
| GET | `places/nearby` |
| GET | `search` |
| GET | `places/{id}` |
| GET | `reviews/places/{placeId}` |
| POST | `reviews` |
| DELETE | `reviews/{id}` |

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

## FoodApi ✅

`app/src/main/java/uz/mahalla/feature/food/data/FoodApi.kt` — сверен: issue #9, второй круг.

| Метод | Путь |
|---|---|
| GET | `food/places/{placeId}/menu` |
| POST | `food/orders` |
| GET | `orders/{orderId}` |
| POST | `food/orders/{orderId}/cancel` |

## FreelancerApi ⚠️

`app/src/main/java/uz/mahalla/feature/freelancer/data/FreelancerApi.kt` — НЕ СВЕРЕН: писался по описанию задачи — проверить перед правкой.

| Метод | Путь |
|---|---|
| GET | `freelancers` |
| GET | `freelancers/{id}` |
| GET | `freelancers/{id}/services` |
| POST | `freelancers/{id}/orders` |
| GET | `freelancers/orders/my` |

## GamingApi ⚠️

`app/src/main/java/uz/mahalla/feature/gaming/data/GamingApi.kt` — пути сверены curl'ами по стенду (issue #98), **тело `POST gaming/bookings` не подтверждено**: в схеме оно объявлено как `BookRequest`, а на это имя ссылаются три пути (коллизия springdoc), и `401` приходит до валидации. Поля названы по ответу того же эндпоинта — `{zoneId, startTime, durationHours}`.

| Метод | Путь |
|---|---|
| GET | `gaming/places/{placeId}/zones` |
| POST | `gaming/bookings` |
| GET | `gaming/bookings/my` |

Отмены брони у бэкенда нет: в `gaming-controller` пять путей, `cancel` среди них не значится, а в общем `orders` для `GAMING` только `GET`.

`startTime` уходит зоне-менее в UTC (`2026-09-05T13:00:00`) — согласовано с `parseServerInstant`, который читает зоне-менее время сервера как UTC.

## HospitalApi ⚠️

`app/src/main/java/uz/mahalla/feature/hospital/data/HospitalApi.kt` — НЕ СВЕРЕН: писался по описанию задачи — проверить перед правкой.

| Метод | Путь |
|---|---|
| GET | `hospitals/places/{placeId}/doctors` |
| POST | `hospitals/appointments` |
| GET | `hospitals/appointments/my` |
| POST | `appointments/{id}/cancel` |

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

1. Поднять бэкенд: в `claude-dev.yml` он уже на `http://localhost:8080`, если
   задан `BACKEND_IMAGE`. Локально — образ из `MAHALLA-IMPLEMENTATION.md`
   дизайн-репозитория.
2. `curl` с заголовками `X-Geo-Lat` / `X-Geo-Lng` и `Authorization`.
3. Расхождение — в issue и в этот файл, статус меняется на ✅ со ссылкой.

Источник истины по бэкенду — `MAHALLA-IMPLEMENTATION.md` и
`PROJECT-STATUS-*.md` в `jack5505/mahalla` (в CI: `design-repo/`).
