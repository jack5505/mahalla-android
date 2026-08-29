package uz.mahalla.feature.auth.domain

/**
 * Куда бэкенд отправил код (`channel` в ответе `auth/send-otp`).
 *
 * Канал выбирает сервер, а не приложение: у кого номер уже связан с
 * Telegram-ботом, тот получает код бесплатным сообщением от бота, остальные —
 * платным SMS. Проверено на стенде: один и тот же запрос отвечает `SMS` для
 * незнакомого номера и `TELEGRAM` для связанного.
 *
 * Пользователю разница видна не подписью на экране, а тем, что SMS не приходит:
 * он ждёт его, не зная, что код уже лежит в Telegram (issue #54). Поэтому канал
 * доезжает до экрана ввода кода.
 */
enum class OtpDeliveryChannel {
    Sms,
    Telegram,
    ;

    companion object {
        /**
         * Разбор значения сервера. Незнакомый канал (и молчание) считаем SMS:
         * это поведение до issue #54, и обещать Telegram там, где его может не
         * быть, хуже, чем не обещать ничего.
         */
        fun of(raw: String?): OtpDeliveryChannel = when (raw?.trim()?.uppercase()) {
            "TELEGRAM" -> Telegram
            else -> Sms
        }

        /** Обратный разбор для аргумента маршрута (`OtpRoute.channel`). */
        fun byName(name: String?): OtpDeliveryChannel =
            entries.firstOrNull { it.name == name } ?: Sms
    }
}

/**
 * Параметры отправленного SMS-кода (эпик 3.3).
 *
 * Значения по умолчанию — клиентские: бэкенд может не прислать ни одного
 * поля, и это не повод показывать пользователю пустой экран. Некорректные
 * значения (ноль, отрицательные, абсурдно большие) тоже приводятся к
 * разумным — иначе таймер «повторить через -5 сек» или поле на 300 ячеек.
 */
data class OtpChallenge(
    /**
     * Токен отправленного кода: `verify-otp` принимает его, а не номер
     * телефона (issue #42). Пустым не бывает — репозиторий считает ответ без
     * токена нечитаемым, потому что проверить код по нему всё равно нельзя.
     */
    val otpToken: String,
    val codeLength: Int = DEFAULT_CODE_LENGTH,
    val resendAfterSeconds: Int = DEFAULT_RESEND_SECONDS,
    val expiresInSeconds: Int = DEFAULT_EXPIRES_SECONDS,
    /** Куда ушёл код — от этого зависит текст экрана (issue #54). */
    val channel: OtpDeliveryChannel = OtpDeliveryChannel.Sms,
) {
    companion object {
        const val DEFAULT_CODE_LENGTH = 6
        const val DEFAULT_RESEND_SECONDS = 60
        const val DEFAULT_EXPIRES_SECONDS = 180

        private const val MIN_CODE_LENGTH = 4
        private const val MAX_CODE_LENGTH = 8
        private const val MAX_RESEND_SECONDS = 600
        private const val MAX_EXPIRES_SECONDS = 3_600

        /** Сборка из ответа сервера: `null` и мусор заменяются дефолтами. */
        fun of(
            otpToken: String,
            codeLength: Int?,
            resendAfterSeconds: Int?,
            expiresInSeconds: Int?,
            channel: String? = null,
        ) = OtpChallenge(
            otpToken = otpToken,
            codeLength = codeLength
                ?.takeIf { it in MIN_CODE_LENGTH..MAX_CODE_LENGTH }
                ?: DEFAULT_CODE_LENGTH,
            resendAfterSeconds = resendAfterSeconds
                ?.takeIf { it in 0..MAX_RESEND_SECONDS }
                ?: DEFAULT_RESEND_SECONDS,
            expiresInSeconds = expiresInSeconds
                ?.takeIf { it in 1..MAX_EXPIRES_SECONDS }
                ?: DEFAULT_EXPIRES_SECONDS,
            channel = OtpDeliveryChannel.of(channel),
        )
    }
}

/**
 * Итог верификации кода. Новому пользователю дальше предлагается заполнить
 * профиль.
 *
 * Отдельного признака «новый» бэкенд не отдаёт (issue #42), поэтому им служит
 * пустое имя в профиле: заполнять нечего ровно у того, кто только что
 * зарегистрировался.
 */
data class LoginResult(val isNewUser: Boolean)
