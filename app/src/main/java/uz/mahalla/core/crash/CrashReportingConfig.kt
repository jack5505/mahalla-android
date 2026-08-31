package uz.mahalla.core.crash

/**
 * Решение «слать отчёты о падениях или нет» (issue #74) — одним местом и без
 * Android, чтобы его можно было проверить тестом.
 *
 * Почему Sentry, а не Crashlytics: Crashlytics доставляет отчёты через Google
 * Play Services, а устройства без сервисов Google в Узбекистане обычны — это
 * уже записанная причина, по которой в эпике 4.2 выбран Yandex MapKit вместо
 * Google Maps. Отчётов не было бы ровно с тех прошивок, где падений больше
 * всего. Вдобавок Crashlytics требует `google-services.json` в репозитории, а
 * T8 прямо просит держать ключ в секрете Actions.
 *
 * @param dsn адрес проекта Sentry из `BuildConfig.SENTRY_DSN` (секрет сборки).
 * @param enabledForBuild разрешает ли сбор сам тип сборки
 * (`BuildConfig.CRASH_REPORTING_ENABLED`): в release — да, в debug — только по
 * флагу `SENTRY_ENABLED_IN_DEBUG`, иначе падения при отладке засоряют панель.
 * @param environment `debug` / `release` — в панели отчёты не смешиваются.
 * @param release версия приложения: без неё нельзя сказать, в какой сборке
 * падает.
 */
data class CrashReportingConfig(
    val dsn: String,
    val enabledForBuild: Boolean,
    val environment: String,
    val release: String,
) {

    /**
     * Пустой DSN — не ошибка: секрет мог быть не задан (форк, локальная
     * сборка), и приложение обязано работать без него. Заведомо непохожая на
     * DSN строка тоже выключает сбор: `Sentry.init` на мусоре бросает
     * `IllegalArgumentException`, и опечатка в секрете уронила бы старт
     * приложения у всех.
     */
    val isEnabled: Boolean
        get() = enabledForBuild && looksLikeDsn(dsn)

    private companion object {

        /**
         * Минимальная проверка формы: `https://<ключ>@<хост>/<id проекта>`.
         * Полный разбор делает сам SDK — здесь нужно лишь не отдать ему
         * заведомый мусор.
         */
        fun looksLikeDsn(dsn: String): Boolean {
            val value = dsn.trim()
            if (value.isEmpty()) return false
            val scheme = value.substringBefore("://", missingDelimiterValue = "")
            if (scheme != "http" && scheme != "https") return false
            val rest = value.substringAfter("://")
            val credentials = rest.substringBefore('@', missingDelimiterValue = "")
            if (credentials.isEmpty()) return false
            val host = rest.substringAfter('@').substringBefore('/')
            return host.isNotEmpty() && rest.substringAfter('@').contains('/')
        }
    }
}
