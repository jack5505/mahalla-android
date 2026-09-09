# Release-сборка

Эпик 13.4. Здесь то, чего не видно из `app/build.gradle.kts`: какие секреты
нужны, что проверяется машиной, а что придётся проверить руками.

## Окружения

Адрес бэкенда задаётся типом сборки — отдельных productFlavor нет, иначе
вариантов сборки стало бы вчетверо больше ради одной строки:

| Сборка | baseUrl по умолчанию | Экран смены адреса |
|---|---|---|
| `debug` | `https://189-74-96-232.nip.io/api/v1/` (стенд) | всегда |
| `release` | `https://api.mahalla.uz/api/v1/` (прод) | только с `BACKEND_URL_OVERRIDE=true` |

Направить сборку на другой стенд, не трогая код:

```bash
API_BASE_URL_DEBUG=https://stand.example/api/v1/ ./gradlew assembleDebug
./gradlew assembleRelease -PAPI_BASE_URL_RELEASE=https://rc.example/api/v1/
```

Завершающий `/` дописывается автоматически: без него Retrofit молча отбрасывает
последний сегмент baseUrl, и все запросы уезжают на уровень выше.

## R8

В release включены `minifyEnabled` и `shrinkResources`. Правила — в
`app/proguard-rules.pro`, каждое с объяснением: без объяснения следующий
человек не сможет понять, можно ли правило убрать.

Что важно помнить:

- **R8 ломает только release и только в рантайме.** Ни `assembleRelease`, ни
  тесты этого не видят: класс, выкинутый как «недостижимый», всплывает при
  первом обращении у пользователя. Эмулятора в CI нет (AGENTS.md), поэтому
  release-сборку перед выкладкой **надо прогнать руками** — вход, карта,
  список мест, заказ.
- Основные кандидаты на поломку — то, что резолвится не по прямой ссылке:
  `kotlinx.serialization` (генерируемые `$serializer`), Retrofit (интерфейсы
  через динамический прокси), Yandex MapKit (JNI).
- `mapping.txt` (`app/build/outputs/mapping/release/`) нужен, чтобы читать
  стектрейсы из Sentry. **Сохраняйте его на каждый выпущенный билд** — без
  него отчёт о падении показывает `a.b.c`.

Проверка, что R8 не съел сериализаторы:

```bash
./gradlew assembleRelease
grep '\$\$serializer ->' app/build/outputs/mapping/release/mapping.txt | head
```

### Размер APK

`app-release-unsigned.apk` — около 108 МБ, из них ~103 МБ — нативные
библиотеки Yandex MapKit на четыре ABI. Кода после R8 всего 6 МБ. Пользователь
столько не качает: Play отдаёт AAB и режет по ABI (`bundleRelease`), реальная
загрузка — около 26 МБ. Универсальный APK такого размера — нормальный
результат, а не регресс; ужимать имеет смысл только сам MapKit.

## Подпись

Хранилище ключей в репозиторий не кладётся. Значения — секреты Actions или
строки в `local.properties`:

| Переменная окружения | `local.properties` | Что это |
|---|---|---|
| `MAHALLA_KEYSTORE_FILE` | `release.keystore.file` | путь к `.jks` (абсолютный или от корня проекта) |
| `MAHALLA_KEYSTORE_PASSWORD` | `release.keystore.password` | пароль хранилища |
| `MAHALLA_KEY_ALIAS` | `release.key.alias` | алиас ключа |
| `MAHALLA_KEY_PASSWORD` | `release.key.password` | пароль ключа |

Хранилища нет — release собирается **неподписанным**, и Gradle пишет об этом
предупреждение. Так сделано намеренно: `assembleRelease` в CI проверяет, что
отработал R8, и ронять эту проверку из-за отсутствия ключа неправильно —
ровно так один незаполненный секрет ломал бы сборку всем, включая форки.

Создать хранилище (один раз, ключ хранить вне репозитория):

```bash
keytool -genkeypair -v -keystore mahalla-release.jks -alias mahalla \
  -keyalg RSA -keysize 4096 -validity 10000
```

Ключ потерян — приложение с тем же `applicationId` в Play обновить уже нельзя.

## Что проверяет машина, а что нет

| Проверка | Где |
|---|---|
| `testDebugUnitTest`, `assembleDebug`, `lintDebug` | `ci.yml` |
| R8, шринк ресурсов, сборка release | `./gradlew assembleRelease` — **в CI не запускается** |
| Скриншот-тесты темы | `./gradlew verifyRoborazziDebug` — **в CI не запускается** |
| Работа release на устройстве | только руками |

Две строки «в CI не запускается» — не оговорка, а задача: шаги нужно добавить
в `.github/workflows/ci.yml`, а правки workflow агенту недоступны (у GitHub
App нет прав на `.github/workflows`).
