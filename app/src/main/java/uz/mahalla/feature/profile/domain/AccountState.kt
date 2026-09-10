package uz.mahalla.feature.profile.domain

/**
 * Насколько бэкенд проверил, кто этот человек (`MeResponse.verificationStatus`)
 * — issue #237.
 *
 * Поднять статус приложение не может: ручки для этого у бэкенда нет, всё
 * решается на его стороне. Поэтому это подпись, а не действие.
 *
 * [Unknown] — статус, которого приложение ещё не знает.
 */
enum class VerificationStatus {
    /** Номер не подтверждён — единственное состояние, о котором стоит сказать. */
    Unverified,

    /** Обычное состояние всех, кто вошёл по SMS-коду. */
    SmsVerified,

    /** Проверены документы. */
    FullVerified,

    Unknown,
    ;

    companion object {
        fun fromServer(value: String?): VerificationStatus = when (value?.trim()?.uppercase()) {
            "UNVERIFIED" -> Unverified
            "SMS_VERIFIED" -> SmsVerified
            "FULL_VERIFIED" -> FullVerified
            else -> Unknown
        }
    }
}

/**
 * Состояние аккаунта на бэкенде (`MeResponse.accountStatus`) — issue #237.
 *
 * Блокировку человек обязан видеть словами: иначе половина экранов отвечает
 * отказами, и приложение выглядит сломанным, а не запертым.
 *
 * [Unknown] — состояние, которого приложение ещё не знает: показываем экран
 * как обычно, но не называем аккаунт рабочим.
 */
enum class AccountStatus {
    Active,
    TempBlocked,
    PermBlocked,
    Suspended,
    Deleted,
    Unknown,
    ;

    companion object {
        fun fromServer(value: String?): AccountStatus = when (value?.trim()?.uppercase()) {
            "ACTIVE" -> Active
            "TEMP_BLOCKED" -> TempBlocked
            "PERM_BLOCKED" -> PermBlocked
            "SUSPENDED" -> Suspended
            "DELETED" -> Deleted
            else -> Unknown
        }
    }
}
