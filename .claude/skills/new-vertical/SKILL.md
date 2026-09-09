---
name: new-vertical
description: Добавить новую вертикаль в mahalla-android (каталог, услуги, заказ или запись). Используй при задачах вида «вертикаль X», «раздел X», «витрина X» в feature/.
---

# Новая вертикаль

В проекте уже восемь вертикалей по одному шаблону: `food`, `pharmacy`,
`hospital`, `cinema`, `freelancer`, `fashion`, `queue` (walk-in), `booking`.
Не изобретай девятый способ — повтори шаблон. Самая близкая по смыслу
вертикаль и есть образец: витрина товаров — `pharmacy`, услуги со слотами —
`booking`, сеансы и билеты — `cinema`, серверная корзина — `fashion`.

## Прежде чем писать код

1. **`docs/API-CONTRACT.md`** — найди эндпоинты вертикали. Стоит ⚠️ — контракт
   не сверен: не подгоняй код под догадку, зафиксируй расхождение в отчёте.
2. **`design-repo/design/android/`** (если каталог есть) — `SCREENS.md` и
   `TZ-ANDROID.md` по этому разделу. Каталога нет — верстай по описанию задачи
   и напиши об этом в отчёте.
3. Прочитай одноимённые файлы у ближайшей вертикали. Быстрее, чем этот текст.

## Раскладка

```
feature/<name>/
  domain/<Entity>.kt          модели + чистые функции (правила, расчёты)
  data/<Name>Api.kt           Retrofit-интерфейс
  data/<Name>Mappers.kt       DTO → domain, мягкий разбор
  data/<Name>Repository.kt    интерфейс + Default<Name>Repository
  data/di/<Name>DataModule.kt @Provides api + @Binds репозиторий
  ui/<Name>Contract.kt        State (data class) + Event (sealed interface)
  ui/<Name>Screen.kt          Compose, только из кита core/ui/components
  ui/<Name>ViewModel.kt       MviViewModel из core/ui/Mvi.kt
```

Несколько экранов — раскладывай `ui/` по подпапкам, как в `cinema`
(`ui/poster`, `ui/movie`, `ui/tickets`), общие куски в `ui/<Name>Pieces.kt`.

## Как принято

- **Домен без Android.** Правила, фильтры, расписания — чистые функции, они
  тестируются на JVM без Robolectric.
- **Репозиторий возвращает `ApiResult`**, а не `Response<T>`; ошибки через
  `apiCall {}`. Кэш в Room — только если он реально нужен, и по правилам
  `docs/adr/0003-room-kak-kesh.md`.
- **DI:** `object <Name>DataModule` с `@Provides` для Api и
  `interface <Name>BindingsModule` с `@Binds` для репозитория. Api — на
  основном Retrofit; `@RefreshClient` только для того, что обязано ходить
  без токена.
- **State — `data class`, Event — `sealed interface`.** Поля состояния
  документируй, если неочевидны: `searchedQuery` отдельно от `query`,
  `loadMoreFailure` отдельно от списка.
- **Список с пагинацией** — почти всегда. Держи `hasMore`, `isLoadingMore`
  и отдельный `loadMoreFailure`: провал догрузки показывает «Повторить», а не
  прячет уже показанный список и не крутит спиннер вечно.
- **Поиск — на сервере** (`?query=`), если у ручки есть пагинация. Локальная
  фильтрация спрячет совпадения с непрогруженных страниц.
- **Пустое состояние различай:** «ничего не нашлось по запросу» и «здесь пока
  пусто» — разные тексты, иначе второе выглядит как сломанный поиск.
- **Маршрут** — `@Serializable data class <Name>Route` в `navigation/Routes.kt`,
  регистрация в `MahallaNavHost`, добавить в `RoutesSerializationTest`.
- **Строки** — сразу в `values/` (uz) и `values-ru/`.

## Тесты (без них работа не готова)

Минимум по образцу `pharmacy`:

- `domain/<Entity>Test.kt` — чистые правила;
- `data/<Name>RepositoryTest.kt` — MockWebServer: успех, пагинация, параметры
  запроса, ошибка, при наличии кэша — фоллбэк;
- `ui/<Name>ViewModelTest.kt` — переходы состояний; читает аргументы
  маршрута — **под Robolectric** (см. `.claude/rules/testing.md`);
- `testutil/Fake<Name>Repository.kt` — фейк с возможностью отказать;
- вертикаль попадает в `GraphAssemblyTest` — граф Hilt должен собираться.

## Перед пушем

```bash
./gradlew testDebugUnitTest assembleDebug
```

Зелёное — обязательно, а не желательно. В отчёт: сколько тестов, что из
контракта не сверено, что осталось.
