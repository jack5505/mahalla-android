package uz.mahalla.feature.security.domain

import uz.mahalla.feature.auth.domain.ServerPin

/**
 * Что бэкенд знает о PIN этого устройства (issue #102).
 *
 * Читается только для показа: включать и выключать что-либо на основании
 * этого ответа приложение не пытается. Единственное решение, которое от него
 * зависит, — [locked]: пока сервер держит блокировку, отправлять PIN
 * бессмысленно, а человеку надо назвать причину.
 *
 * @param lockedSecondsRemaining счётчик ведёт сервер (issue #51): свой лимит
 * попыток расходился бы с ним в сообщениях и сбрасывал бы PIN раньше времени.
 */
data class ServerPinStatus(
    val pinSet: Boolean,
    val biometricEnabled: Boolean,
    val lockedSecondsRemaining: Long,
) {
    val locked: Boolean get() = lockedSecondsRemaining > 0

    companion object {
        /**
         * Молчание сервера о флаге читается как «выключено», а не как ошибка:
         * все поля `PinStatusResponse` необязательны, и спрятать из-за
         * неприехавшего поля весь экран безопасности было бы хуже, чем
         * показать его и получить внятный отказ на первое же действие.
         *
         * Отрицательный остаток блокировки приводится к нулю: «заблокировано
         * на −5 секунд» не значит ничего, а обратный отсчёт по такому числу
         * не закончится никогда.
         */
        fun of(
            pinSet: Boolean?,
            biometricEnabled: Boolean?,
            lockedSecondsRemaining: Long?,
        ): ServerPinStatus = ServerPinStatus(
            pinSet = pinSet == true,
            biometricEnabled = biometricEnabled == true,
            lockedSecondsRemaining = lockedSecondsRemaining?.coerceAtLeast(0L) ?: 0L,
        )
    }
}

/**
 * Ответ `auth/session/check`: жива ли сессия и нужен ли ей PIN.
 *
 * @param valid `false` — сессии больше нет. Это единственный исход, при
 * котором экран блокировки не поможет: там ждут PIN, а сервер ждёт новый
 * вход.
 */
data class SessionCheck(
    val valid: Boolean,
    val pinRequired: Boolean,
    val reason: String?,
) {
    companion object {
        /**
         * Молчание сервера о `sessionValid` считается «сессия жива».
         *
         * Осторожность здесь работает в обратную сторону: `false` по
         * умолчанию выкинул бы человека из аккаунта из-за неприехавшего поля,
         * а мёртвый токен приложение и так узнает по первому же 401 —
         * `TokenAuthenticator` попробует refresh и разлогинит, если он не
         * прошёл.
         */
        fun of(
            sessionValid: Boolean?,
            pinRequired: Boolean?,
            reason: String?,
        ): SessionCheck = SessionCheck(
            valid = sessionValid != false,
            pinRequired = pinRequired == true,
            reason = reason?.takeIf { it.isNotBlank() },
        )
    }
}

/**
 * Правила смены PIN (issue #102) — чистыми функциями, потому что проверить их
 * иначе нечем: экран собирает три кода подряд, и половина условий видна
 * только на последнем.
 */
object ChangePinRules {

    /** Столько цифр требует бэкенд (`^[0-9]{6}$`), и столько же рисует экран. */
    const val LENGTH = ServerPin.LENGTH

    /**
     * Код нужной длины и целиком из **арабских** цифр.
     *
     * Именно `in '0'..'9'`, а не `Char.isDigit()`: последний принимает и
     * деванагари, и полноширинные `１２３４５６`, а бэкенд проверяет
     * `^[0-9]{6}$` и такой код отвергнет. Клавиатуры с национальными цифрами
     * на устройствах Узбекистана встречаются, и отказ сервера на вид
     * правильно набранном коде объяснить было бы нечем.
     */
    fun isWellFormed(pin: String): Boolean =
        pin.length == LENGTH && pin.all { it in '0'..'9' }

    /**
     * Новый код не должен повторять текущий.
     *
     * Бэкенд об этом, возможно, и не спросит, но «сменил PIN на тот же самый»
     * — это не смена: человек уверен, что защитился, а не защитился ничем.
     */
    fun isSameAsCurrent(currentPin: String, newPin: String): Boolean = currentPin == newPin
}
