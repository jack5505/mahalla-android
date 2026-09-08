package uz.mahalla.feature.update.domain

/**
 * Обновление приложения (issue #80, задача T12).
 *
 * Бэкенд (`app/version/check`) сравнивает `versionCode` сборки со своим
 * реестром версий и отвечает, надо ли обновляться. Это единственный способ
 * сказать человеку «ваша сборка устарела»: контракт API за месяц ломался
 * четырежды (#42, #51, #53 и пути вертикали «Еда»), и каждый раз старая сборка
 * переставала работать молча — на экране было «Nimadir xato ketdi».
 *
 * Все поля необязательные: на стенде без заполненного реестра версий ответ
 * приходит с одними `null` (проверено), и это штатный ответ «всё в порядке», а
 * не повод показать пустой экран обновления.
 */
data class AppUpdate(
    /** Идентификатор версии в реестре бэкенда: по нему считается пропуск. */
    val versionId: String? = null,
    val versionName: String? = null,
    val versionCode: Int? = null,
    val releaseNotes: String? = null,
    /**
     * Куда идти обновляться. Уже проверен [StoreLink] — открывать можно.
     * `null` означает, что сервер ссылку не прислал (или прислал негодную) и
     * экран подставит магазин по имени собственного пакета.
     */
    val storeUrl: String? = null,
    /** Сколько раз ещё можно отложить. `null` — сервер не сказал. */
    val remainingSkips: Int? = null,
    val policy: UpdatePolicy = UpdatePolicy.Unknown,
)

/**
 * Насколько бэкенд настаивает на обновлении.
 *
 * [Unknown] — не ошибка, а незнакомое значение: список политик у бэкенда
 * открыт, и новая политика не должна ни запирать приложение, ни оставаться
 * незамеченной. Решение о блокировке принимается по [UpdateDecision.of], где
 * незнакомая политика опирается на явные флаги `updateRequired`/`updateAvailable`.
 */
enum class UpdatePolicy {
    Optional,
    Flexible,
    Immediate,
    Unknown,
    ;

    companion object {
        fun fromServer(raw: String?): UpdatePolicy =
            when (raw?.trim()?.uppercase()) {
                "OPTIONAL" -> Optional
                "FLEXIBLE" -> Flexible
                "IMMEDIATE" -> Immediate
                else -> Unknown
            }
    }
}

/**
 * Что показать пользователю по итогам проверки.
 *
 * Отделено от [AppUpdate] намеренно: «есть новая версия» и «дальше не пустим» —
 * разные решения, и принимаются они по нескольким полям сразу.
 */
sealed interface UpdateDecision {

    /** Ничего показывать не нужно — в том числе когда проверка не удалась. */
    data object None : UpdateDecision

    /** Блокирующий экран: пользоваться старой сборкой бэкенд не даёт. */
    data class Required(val update: AppUpdate) : UpdateDecision

    /** Мягкое предложение с «Позже». */
    data class Suggested(val update: AppUpdate) : UpdateDecision

    companion object {

        /**
         * Правила решения — чистая функция, потому что цена ошибки здесь
         * несимметрична: лишний блокирующий экран превращает приложение в
         * кирпич на всех устройствах сразу.
         *
         * - `updateRequired` **или** политика `IMMEDIATE` → блокируем. Флага
         *   достаточно самого по себе: политика может не приехать (на стенде
         *   она `null`), а требование — приехать.
         * - `updateAvailable` и пропуски ещё есть → мягкое предложение.
         *   `remainingSkips == null` считается «сервер не считает пропуски», а
         *   не «пропусков не осталось»: превращать молчание в невозможность
         *   отложить необязательное обновление нельзя.
         * - Пропуски кончились (`remainingSkips == 0`), а требования нет →
         *   **не показываем ничего**. Экран без «Позже» при неблокирующем
         *   ответе сервера был бы той самой блокировкой, которой бэкенд не
         *   просил; когда он решит настоять, он пришлёт `updateRequired`.
         */
        fun of(
            updateRequired: Boolean,
            updateAvailable: Boolean,
            update: AppUpdate,
        ): UpdateDecision = when {
            updateRequired || update.policy == UpdatePolicy.Immediate -> Required(update)
            updateAvailable && update.canSkip() -> Suggested(update)
            else -> None
        }

        private fun AppUpdate.canSkip(): Boolean = remainingSkips == null || remainingSkips > 0
    }
}

/**
 * Проверка ссылки на магазин.
 *
 * Ссылку присылает сервер, а адрес сервера в debug-сборке вводит пользователь
 * (issue #26) — то есть строка приходит из места, которому нельзя доверять
 * безусловно. Открывать её `Intent.ACTION_VIEW` без проверки означало бы, что
 * подменённый бэкенд запускает на устройстве произвольный intent: `intent://`
 * с явным компонентом, чужой deep link, наш собственный `mahalla://`.
 *
 * Поэтому схем ровно две:
 * - `market:` — её обрабатывают только магазины приложений, и худшее, что
 *   может случиться, — открытая карточка чужого приложения;
 * - `https:` — на **любой** хост. Ограничивать список магазинов здесь было бы
 *   вредно: APK у проекта вполне может лежать на своём же сервере, а открытая
 *   в браузере ссылка показывает человеку адрес, куда он идёт.
 *
 * `http` не проходит намеренно: установочный файл не должен ехать по открытому
 * каналу, где его может подменить кто угодно по дороге.
 */
object StoreLink {

    private const val HTTPS = "https"
    private const val MARKET = "market"

    private const val PLAY_PREFIX = "https://play.google.com/store/apps/details?id="

    /**
     * Ссылка, которую можно открыть, или `null`.
     *
     * Разбор ручной, без `android.net.Uri`: правило проверяется JVM-тестом, а
     * `Uri` в юнит-тестах заглушен и молча вернул бы `null` у каждого поля —
     * то есть тест был бы зелёным при любой реализации.
     */
    fun sanitize(url: String?): String? {
        val candidate = url?.trim().orEmpty()
        if (candidate.isEmpty()) return null

        val scheme = candidate.substringBefore(':', missingDelimiterValue = "").lowercase()
        return when (scheme) {
            MARKET -> candidate
            HTTPS -> candidate.takeIf { it.startsWith("$HTTPS://", ignoreCase = true) }
            else -> null
        }
    }

    /**
     * Куда идти, когда сервер ссылки не дал.
     *
     * Имя пакета — своё собственное, из сборки, а не из ответа сервера:
     * подставлять сюда серверную строку значило бы обойти [sanitize] с другой
     * стороны.
     */
    fun playStore(packageName: String): String = PLAY_PREFIX + packageName
}
