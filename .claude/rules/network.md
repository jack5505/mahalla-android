---
paths:
  - "app/src/main/java/uz/mahalla/data/network/**"
  - "app/src/main/java/uz/mahalla/**/data/*Api.kt"
  - "app/src/main/java/uz/mahalla/**/data/*Repository.kt"
  - "app/src/main/java/uz/mahalla/core/analytics/**"
---

# Сетевой слой

## Контракт не выдумывать

**Перед правкой любого `*Api.kt` — открой `docs/API-CONTRACT.md`.**
Эндпоинта там нет или он расходится с задачей: не угадывай по здравому смыслу,
а зафиксируй расхождение в отчёте и спроси. Выдуманный контракт уже дважды
приводил к переделке целой вертикали (OTP, затем «Еда»).
Изменил или подтвердил эндпоинт — обнови `docs/API-CONTRACT.md` в том же PR.

## Как устроено

- Сборка стека — `NetworkFactory` (её же используют тесты), Hilt-обвязка в
  `data/network/di/NetworkModule`.
- Результат — `core/result/ApiResult` через `apiCall {}`; ошибки раскладываются
  в `ApiError`. Голых `Response<T>` в репозиториях быть не должно.
- `AuthInterceptor` — Bearer; `TokenAuthenticator` — refresh по 401, один повтор.
- **Эндпоинты авторизации ходят на `@RefreshClient`** — клиент без
  authenticator'а, иначе 401 на самом refresh уходит в рекурсию.
- `baseUrl` — из `BuildConfig.API_BASE_URL`, не хардкодить.

## Аналитика (`analytics/track`, issue #169)

- Ручка **place-центрична**: `placeId` обязателен, `eventType` — закрытое
  перечисление из девяти значений. Событие без заведения (экран, поиск, отказ
  бэкенда) отправить нечем — issue #226; свой `eventType` не выдумывать.
- Событие создаётся **только через `AnalyticsEvents`** и уходит только через
  `AnalyticsTracker.track` («выстрелил и забыл», своя область, не
  `viewModelScope`). Строк с именами событий по экранам быть не должно.
- Без сети событие теряется, очереди нет — `docs/adr/0006`.

## Осторожно

- **Не логировать `Authorization`** — у `HttpLoggingInterceptor` стоит
  `redactHeader`, не снимать.
- **Разбор DTO мягкий**: одно битое поле не должно ронять весь список.
- Ответ сервера повторно не фильтровать на клиенте — сервер ищет по описанию,
  меню и тегам, локальный фильтр вырежет валидную выдачу.
