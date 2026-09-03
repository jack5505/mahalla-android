package uz.mahalla.feature.auth.domain

import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure

/**
 * «Тот ли это аккаунт, под которым входят» (issue #86).
 *
 * Вход завершает `auth/pin-login`, а его тело — `{pin, device, lat, lng}`:
 * ни номера, ни `otpToken`, ни `sessionId` там нет, и пользователя бэкенд ищет
 * **по устройству**. На телефоне, который раньше входил под другим аккаунтом
 * (например через Telegram), этот шаг возвращает токены прежнего владельца —
 * какой бы номер ни ввели в форму. Отличить свой аккаунт от чужого клиент
 * может только одним способом: сравнить номер, который человек ввёл, с
 * номером в ответе сервера.
 *
 * Сравниваются значащие цифры: бэкенд отдаёт номер то как `+998901234567`, то
 * как `998901234567`, а человек вводит национальную часть — по строкам это три
 * разных значения, по человеку один и тот же номер.
 */
object PhoneIdentity {

    /** Национальная часть узбекского номера. */
    private const val SIGNIFICANT_DIGITS = 9

    /**
     * Цифры, по которым номера имеет смысл сравнивать. `null` — сравнивать
     * нечего: пусто, либо в строке вообще нет цифр.
     */
    fun significantDigits(phone: String?): String? {
        val digits = phone?.filter(Char::isDigit).orEmpty()
        if (digits.isEmpty()) return null
        // Хвост, а не начало: лишним бывает код страны, а не номер абонента.
        // Короткие строки (тестовые номера, мусор) сравниваются целиком.
        return digits.takeLast(SIGNIFICANT_DIGITS)
    }

    /**
     * Один ли это номер. Неизвестный с любой стороны — не «нет»: см.
     * [isForeignAccount], решение принимается там.
     */
    fun isSame(left: String?, right: String?): Boolean {
        val a = significantDigits(left) ?: return false
        val b = significantDigits(right) ?: return false
        return a == b
    }

    /**
     * Точно ли сервер вернул **чужой** аккаунт.
     *
     * Обе стороны обязаны быть известны: ответ без номера — это «эндпоинт про
     * другое», а не «аккаунт чужой». Отказывать во входе каждый раз, когда
     * бэкенд не прислал поле, значило бы сломать вход целиком ради защиты от
     * случая, которого в ответе не видно.
     */
    fun isForeignAccount(expectedPhone: String?, accountPhone: String?): Boolean {
        val expected = significantDigits(expectedPhone) ?: return false
        val actual = significantDigits(accountPhone) ?: return false
        return expected != actual
    }

    /**
     * Код отказа: бэкенд такого не присылает, его выставляет клиент, поймав
     * чужой аккаунт. Отдельный код нужен, чтобы экраны показали не «нет сети»,
     * а объяснение, и увели человека на повторный вход.
     */
    const val FOREIGN_ACCOUNT_CODE = "ACCOUNT_MISMATCH"
}

/** Вход вернул чужой аккаунт — см. [PhoneIdentity]. */
fun ApiFailure.isForeignAccount(): Boolean =
    error == ApiError.Business(PhoneIdentity.FOREIGN_ACCOUNT_CODE)
