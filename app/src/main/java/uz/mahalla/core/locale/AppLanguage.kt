package uz.mahalla.core.locale

/**
 * Языки приложения (эпик 1.5). `uz` — язык по умолчанию (`values/`),
 * `ru` — `values-ru/`. [SYSTEM] означает «как в системе»: тег пустой,
 * per-app language сбрасывается.
 */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    UZBEK("uz"),
    RUSSIAN("ru"),
    ;

    /** Значение для DataStore: у [SYSTEM] пустая строка, не `null`. */
    val storedValue: String get() = tag.orEmpty()

    companion object {
        val Default: AppLanguage = SYSTEM

        /**
         * Разбор языкового тега (`ru`, `ru-RU`, `uz-Latn-UZ`, `null`).
         * Неизвестный язык — это не ошибка: отдаём [SYSTEM], иначе смена
         * системной локали пользователем валила бы старт приложения.
         */
        fun fromTag(tag: String?): AppLanguage {
            val language = tag?.trim()?.takeIf { it.isNotEmpty() }
                ?.substringBefore('-')
                ?.substringBefore('_')
                ?.lowercase()
                ?: return SYSTEM
            return entries.firstOrNull { it.tag == language } ?: SYSTEM
        }
    }
}
