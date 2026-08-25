@AGENTS.md

# Claude Code в CI (GitHub Actions)

Ты работаешь headless внутри workflow `claude-dev.yml` на ubuntu-latest.
Что важно знать сверх AGENTS.md:

- **Бэкенд**: если docker-образ задан (переменная `BACKEND_IMAGE`), он уже
  поднят на `http://localhost:8080`; health — `BACKEND_HEALTH_PATH`
  (по умолчанию `/actuator/health`). Зависимости (postgres, redis) доступны
  на localhost. Если образ не задан — бэкенда нет, работай без него и укажи
  это в отчёте.
- **Окружение**: JDK 17, Android SDK (platform 35 + build-tools 35.0.0),
  `ANDROID_HOME` настроен. Сборка — только через `./gradlew`.
- **Эмулятора в CI нет**: проверки — только `./gradlew testDebugUnitTest`
  и `./gradlew assembleDebug`. Instrumentation-тесты не запускать.
- **Тесты обязательны** (правило проекта): для любого функционала пиши тесты
  и прогоняй `./gradlew testDebugUnitTest` до завершения. Красные тесты —
  работа не готова.
- **Git**: не делай `git commit`/`git push` сам — финальный шаг workflow
  закоммитит, запушит ветку и откроет PR автоматически.
- **Финальное сообщение** — краткий отчёт для тела PR: что сделано, какие
  тесты прогнаны и их результат, что осталось/какие риски.
