package uz.mahalla.feature.auth.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import uz.mahalla.core.result.ApiError

/**
 * Раскладка ошибок верификации (3.3). Ветвление важнее, чем кажется: на
 * «неверный код» экран остаётся рабочим, на «попытки исчерпаны» — блокирует
 * ввод, на сетевую ошибку не трогает введённые цифры.
 */
class OtpFailureTest {

    @Test
    fun `unauthorized on verification means a wrong code`() {
        // Сессии на этом шаге ещё нет, поэтому 401 — это не «сессия истекла».
        assertEquals(OtpFailure.InvalidCode, ApiError.Unauthorized.asOtpFailure())
    }

    @Test
    fun `validation codes mean a wrong code`() {
        assertEquals(OtpFailure.InvalidCode, ApiError.Http(400, null).asOtpFailure())
        assertEquals(OtpFailure.InvalidCode, ApiError.Http(422, null).asOtpFailure())
    }

    @Test
    fun `gone means the code expired`() {
        assertEquals(OtpFailure.Expired, ApiError.Http(410, null).asOtpFailure())
    }

    @Test
    fun `too many requests and locked mean the attempts are spent`() {
        assertEquals(OtpFailure.TooManyAttempts, ApiError.Http(429, null).asOtpFailure())
        assertEquals(OtpFailure.TooManyAttempts, ApiError.Http(423, null).asOtpFailure())
    }

    @Test
    fun `transport problems are not about the code`() {
        assertEquals(OtpFailure.Network, ApiError.NoConnection.asOtpFailure())
        assertEquals(OtpFailure.Network, ApiError.Timeout.asOtpFailure())
        assertEquals(OtpFailure.Network, ApiError.Serialization.asOtpFailure())
        assertEquals(OtpFailure.Network, ApiError.Http(500, null).asOtpFailure())
        assertEquals(OtpFailure.Network, ApiError.NotFound.asOtpFailure())
    }

    @Test
    fun `challenge clamps values the ui cannot render`() {
        assertEquals(OtpChallenge(), OtpChallenge.of(null, null, null))
        assertEquals(OtpChallenge(), OtpChallenge.of(codeLength = 99, resendAfterSeconds = -1, expiresInSeconds = 0))
        assertEquals(
            OtpChallenge(codeLength = 4, resendAfterSeconds = 0, expiresInSeconds = 60),
            // Ноль секунд до повтора допустим: сервер разрешает сразу.
            OtpChallenge.of(codeLength = 4, resendAfterSeconds = 0, expiresInSeconds = 60),
        )
    }
}
