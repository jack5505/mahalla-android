package uz.mahalla.feature.auth.domain

import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure

/**
 * Вход через Telegram-бот (issue #46) — бесплатная замена SMS.
 *
 * Бэкенд (`auth/telegram/init`) выдаёт одноразовый `deepLinkToken` и ссылку на
 * бота вида `https://t.me/MahallaVerifyBot?start=<token>`. Приложение открывает
 * её в Telegram, пользователь нажимает Start, бот отдаёт боту-серверу свой
 * контакт — и `auth/telegram/check` начинает отвечать парой токенов вместо
 * `TG_PENDING`.
 *
 * Кода из шести цифр в этом пути нет вообще: подтверждением служит сам факт
 * нажатия Start в аккаунте с известным номером. Это короче SMS-пути на один
 * экран и ничего не стоит.
 */
data class TelegramChallenge(
    /**
     * Одноразовый токен попытки входа. **Не хранится на диске и не логируется**:
     * кто им владеет, тот и заберёт сессию (бэкенд `check` устройство не
     * сверяет — см. риски issue #46). Живёт только в памяти ViewModel.
     */
    val deepLinkToken: String,
    /** Ссылка на бота. Уже проверена [TelegramBotLink] — открывать можно. */
    val botUrl: String,
    val expiresInSeconds: Int = DEFAULT_EXPIRES_SECONDS,
) {
    companion object {
        const val DEFAULT_EXPIRES_SECONDS = 300

        private const val MIN_EXPIRES_SECONDS = 1
        private const val MAX_EXPIRES_SECONDS = 3_600

        /**
         * Сборка из ответа сервера. `null` возвращается, когда ответ
         * непригоден: без токена нечего проверять, а ссылку, которая не ведёт в
         * Telegram, открывать нельзя (см. [TelegramBotLink.sanitize]).
         *
         * Срок жизни клампится, как у [OtpChallenge]: «истекает через -5
         * секунд» означало бы экран, который закрывается сразу после открытия.
         */
        fun of(
            deepLinkToken: String?,
            botUrl: String?,
            expiresInSeconds: Int?,
        ): TelegramChallenge? {
            val token = deepLinkToken?.takeIf { it.isNotBlank() } ?: return null
            val link = TelegramBotLink.sanitize(botUrl) ?: return null
            return TelegramChallenge(
                deepLinkToken = token,
                botUrl = link,
                expiresInSeconds = expiresInSeconds
                    ?.takeIf { it in MIN_EXPIRES_SECONDS..MAX_EXPIRES_SECONDS }
                    ?: DEFAULT_EXPIRES_SECONDS,
            )
        }
    }
}

/**
 * Чем закончился опрос `auth/telegram/check`.
 *
 * [Pending] — не ошибка, а нормальное состояние ожидания: человек в этот момент
 * ищет кнопку Start. Поэтому оно отделено от отказов, которые показываются
 * пользователю.
 */
sealed interface TelegramLoginState {

    /** Start ещё не нажат — бэкенд отвечает 400 с кодом `TG_PENDING`. */
    data object Pending : TelegramLoginState

    /**
     * Telegram подтвердил вход.
     *
     * @param requiresPhoneVerify у аккаунта Telegram нет подтверждённого
     * номера. Сессия в этом случае **не сохраняется** — пользователь
     * отправляется на обычный SMS-путь. Полуавторизованное состояние («токены
     * есть, телефон не проверен») в приложении не заводим: отличить его потом
     * от нормального входа было бы нечем.
     */
    data class Confirmed(
        val login: LoginResult,
        val requiresPhoneVerify: Boolean = false,
    ) : TelegramLoginState
}

/**
 * «Start ещё не нажали» — бэкенд сообщает это отказом (HTTP 400, код
 * `TG_PENDING`), хотя ничего не сломалось.
 *
 * Смотрим только на машинный код из тела: 400 приезжает и на действительно
 * плохой запрос (`VALIDATION_ERROR`), и его нельзя принять за ожидание — опрос
 * крутился бы до самого истечения токена, ничего не показывая пользователю.
 * Отсутствие тела (сеть, таймаут, 5xx) ожиданием тоже не считается.
 *
 * Проверка по вхождению `PENDING`, а не по точному равенству: список кодов
 * бэкенда закрыт и уже менялся (issue #42), а цена ошибки здесь несимметрична —
 * незнакомый код ожидания превратится в ошибку на экране, что заметно и
 * поправимо.
 */
fun ApiFailure.isTelegramPending(): Boolean =
    server?.code?.trim()?.uppercase()?.contains("PENDING") == true

/**
 * Стоит ли продолжать опрос после этой ошибки.
 *
 * Пока человек нажимает Start, телефон часто теряет сеть (переключение в
 * Telegram, экономия батареи, метро). Обрывать из-за этого попытку входа и
 * заставлять начинать заново — худшее, что можно сделать: токен ещё жив, и
 * следующий опрос, скорее всего, пройдёт. А вот отказ, который сервер
 * сформулировал (токен просрочен, устройство заблокировано), повторять
 * бессмысленно — он останется тем же до конца жизни токена.
 */
fun ApiFailure.isTelegramPollRecoverable(): Boolean = when (val cause = error) {
    ApiError.NoConnection, ApiError.Timeout -> true
    is ApiError.Http -> cause.code >= HTTP_SERVER_ERROR
    else -> false
}

private const val HTTP_SERVER_ERROR = 500

/**
 * Проверка ссылки на бота.
 *
 * Ссылку присылает сервер, а адрес сервера в debug-сборке вводит пользователь
 * (issue #26) — то есть строка приходит из места, которому нельзя доверять
 * безусловно. Открывать её `Intent.ACTION_VIEW` без проверки означало бы, что
 * подменённый бэкенд может запустить на устройстве произвольный intent
 * (`market://`, `intent://`, чужой deep link, в том числе наш собственный
 * `mahalla://`).
 *
 * Поэтому пропускаем только то, что действительно ведёт в Telegram: `https` на
 * официальные хосты либо схему `tg`.
 */
object TelegramBotLink {

    /** Хосты, которыми Telegram пользуется для ссылок на ботов. */
    private val ALLOWED_HOSTS = setOf("t.me", "telegram.me", "telegram.dog")

    private const val HTTPS = "https"
    private const val TG = "tg"

    /**
     * Ссылка, которую можно открыть, или `null`.
     *
     * Разбор ручной, без `android.net.Uri`: правило проверяется JVM-тестом, а
     * `Uri` в юнит-тестах заглушен и молча вернул бы `null` у каждого поля.
     */
    fun sanitize(url: String?): String? {
        val candidate = url?.trim().orEmpty()
        if (candidate.isEmpty()) return null

        val scheme = candidate.substringBefore("://", missingDelimiterValue = "").lowercase()
        return when (scheme) {
            TG -> candidate
            HTTPS -> candidate.takeIf { host(it) in ALLOWED_HOSTS }
            // http сюда не попадает намеренно: ссылка на бота приходит по TLS,
            // и понижать её до открытого канала незачем.
            else -> null
        }
    }

    /**
     * Хост без порта, userinfo и регистра. `userinfo` отбрасывается по
     * последнему `@`: `https://t.me@evil.example/` — это хост `evil.example`,
     * и наивный `startsWith("t.me")` на нём ошибся бы.
     */
    private fun host(url: String): String = url
        .substringAfter("://")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('@')
        .substringBefore(':')
        .lowercase()
}

/**
 * Пауза между опросами `auth/telegram/check`.
 *
 * Опрашивать приходится клиенту: пуш-канала в приложении нет (FCM не
 * подключён), а узнать про нажатый Start больше неоткуда. Интервал растёт,
 * потому что вероятность распределена неравномерно — обычно Start нажимают в
 * первые секунды после открытия бота, а дальше человек либо отвлёкся, либо
 * ушёл. Ровный частый опрос все пять минут — это полторы сотни запросов на
 * каждую попытку входа; на стенде лимита на этот эндпоинт нет (проверено), так
 * что сдержанность здесь целиком на клиенте.
 */
object TelegramPollSchedule {

    const val FIRST_DELAY_MILLIS = 1_500L
    const val MAX_DELAY_MILLIS = 5_000L

    private const val GROWTH = 1.5

    /** @param attempt номер уже сделанного опроса, начиная с нуля. */
    fun delayMillisAt(attempt: Int): Long {
        if (attempt <= 0) return FIRST_DELAY_MILLIS
        val grown = FIRST_DELAY_MILLIS * Math.pow(GROWTH, attempt.toDouble())
        return if (grown >= MAX_DELAY_MILLIS) MAX_DELAY_MILLIS else grown.toLong()
    }
}
