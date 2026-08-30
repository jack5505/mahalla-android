package uz.mahalla.feature.profile.domain

import java.time.Instant

/**
 * Состояние сессии на бэкенде (`ActiveSessionResponse.status`).
 *
 * [Unknown] — статус, которого приложение ещё не знает: список устройств
 * обязан показываться и после того, как бэкенд заведёт новое состояние.
 */
enum class DeviceSessionStatus {
    PendingPinSetup,
    PinRequired,
    Active,
    Locked,
    Revoked,
    Unknown,
    ;

    companion object {
        fun fromServer(value: String?): DeviceSessionStatus = when (value?.trim()?.uppercase()) {
            "PENDING_PIN_SETUP" -> PendingPinSetup
            "PIN_REQUIRED" -> PinRequired
            "ACTIVE" -> Active
            "LOCKED" -> Locked
            "REVOKED" -> Revoked
            else -> Unknown
        }
    }
}

/**
 * Устройство, на котором открыт вход (`GET auth/sessions`).
 *
 * @param isCurrent это устройство. Отзывать его из списка нельзя: получилось
 * бы «выйти», не сказав об этом — экран остался бы прежним, а следующий
 * запрос ответил бы 401. Для текущего устройства есть кнопка «Выйти».
 */
data class DeviceSession(
    val id: String,
    val deviceName: String? = null,
    val platform: String? = null,
    val appVersion: String? = null,
    val status: DeviceSessionStatus = DeviceSessionStatus.Unknown,
    val lastActivityAt: Instant? = null,
    val lastIp: String? = null,
    val trusted: Boolean = false,
    val isCurrent: Boolean = false,
)

/**
 * Порядок списка: своё устройство сверху, дальше по времени последней
 * активности (свежие выше). Сессия без времени уходит вниз — про неё нечего
 * сказать, а вверх списка человек смотрит в поисках чужого входа.
 */
fun List<DeviceSession>.sortedForDisplay(): List<DeviceSession> = sortedWith(
    compareByDescending<DeviceSession> { it.isCurrent }
        .thenByDescending { it.lastActivityAt ?: Instant.EPOCH },
)
