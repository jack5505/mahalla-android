@AGENTS.md

# Claude Code в CI (GitHub Actions)

Ты работаешь через `anthropics/claude-code-action@v1` на ubuntu-latest в одном
из двух workflow:

- `claude-dev.yml` — ручной запуск (Actions → Run workflow), задача приходит
  в prompt; поднят docker-бэкенд + postgres/redis.
- `claude.yml` — реакция на `@claude` в issue/PR/ревью; задача — из контекста
  комментария, бэкенда и сервисов нет.

Что важно знать сверх AGENTS.md:

- **Бэкенд** (только `claude-dev.yml`): если docker-образ задан (переменная
  `BACKEND_IMAGE`), он уже поднят на `http://localhost:8080`; health —
  `BACKEND_HEALTH_PATH` (по умолчанию `/actuator/health`). Зависимости
  (postgres, redis) доступны на localhost. Если образ не задан — бэкенда нет,
  работай без него и укажи это в отчёте.
- **Окружение**: JDK 17, Android SDK (platform 35 + build-tools 35.0.0),
  `ANDROID_HOME` настроен. Сборка — только через `./gradlew`.
- **Эмулятора в CI нет**: проверки — только `./gradlew testDebugUnitTest`
  и `./gradlew assembleDebug`. Instrumentation-тесты не запускать.
- **Тесты обязательны** (правило проекта): для любого функционала пиши тесты
  и прогоняй `./gradlew testDebugUnitTest` до завершения. Красные тесты —
  работа не готова.
- **Git**: коммить и пушить в свою ветку можно и нужно самому — в CI это
  разрешено (ветки `claude-dev/*` и `claude/*`, автор `claude[bot]`). PR
  открывай, когда об этом просят или когда задача пришла из `claude-dev.yml`.
  Прямо в `main` не пушить. Запрет git-команд из AGENTS.md касается только
  локального агента.
- **Финальное сообщение** — краткий отчёт (идёт в комментарий/тело PR): что
  сделано, какие тесты прогнаны и их результат, что осталось/какие риски.
