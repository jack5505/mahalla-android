package uz.mahalla.feature.auth.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.result.ServerError

/**
 * Раскладка ошибок верификации (3.3). Ветвление важнее, чем кажется: на
 * «неверный код» экран остаётся рабочим, на «попытки исчерпаны» — блокирует
 * ввод, на сетевую ошибку не трогает введённые цифры.
 */
class OtpFailureTest {

    @Test
    fun `unauthorized on verification means a wrong code`() {
        // Сессии на этом шаге ещё нет, поэтому 401 — это не «сессия истекла».
        assertEquals(OtpFailure.InvalidCode, ApiFailure(ApiError.Unauthorized).asOtpFailure())
    }

    @Test
    fun `validation codes mean a wrong code`() {
        assertEquals(OtpFailure.InvalidCode, httpFailure(400).asOtpFailure())
        assertEquals(OtpFailure.InvalidCode, httpFailure(422).asOtpFailure())
    }

    @Test
    fun `gone means the code expired`() {
        assertEquals(OtpFailure.Expired, httpFailure(410).asOtpFailure())
    }

    @Test
    fun `too many requests and locked mean the attempts are spent`() {
        assertEquals(OtpFailure.TooManyAttempts, httpFailure(429).asOtpFailure())
        assertEquals(OtpFailure.TooManyAttempts, httpFailure(423).asOtpFailure())
    }

    @Test
    fun `transport problems are not about the code`() {
        assertEquals(OtpFailure.Network, ApiFailure(ApiError.NoConnection).asOtpFailure())
        assertEquals(OtpFailure.Network, ApiFailure(ApiError.Timeout).asOtpFailure())
        assertEquals(OtpFailure.Network, ApiFailure(ApiError.Serialization).asOtpFailure())
        assertEquals(OtpFailure.Network, httpFailure(500).asOtpFailure())
        assertEquals(OtpFailure.Network, ApiFailure(ApiError.NotFound).asOtpFailure())
    }

    @Test
    fun `expired code is recognized by the backend code, not by the http status`() {
        // Стенд отвечает 400 и на истёкший код: по одному HTTP-статусу это
        // было бы «код неверный», то есть кнопка повтора осталась бы
        // заблокированной таймером.
        assertEquals(
            OtpFailure.Expired,
            httpFailure(400, code = "OTP_EXPIRED").asOtpFailure(),
        )
    }

    @Test
    fun `invalid request is not blamed on the code`() {
        // `VALIDATION_ERROR` — это про тело запроса (координаты, устройство).
        // Стирать введённые цифры и писать «код неверный» здесь нельзя.
        assertEquals(
            OtpFailure.Network,
            httpFailure(400, code = "VALIDATION_ERROR").asOtpFailure(),
        )
    }

    @Test
    fun `unknown otp codes are classified by keywords`() {
        assertEquals(
            OtpFailure.TooManyAttempts,
            httpFailure(400, code = "OTP_ATTEMPTS_EXCEEDED").asOtpFailure(),
        )
        assertEquals(
            OtpFailure.TooManyAttempts,
            httpFailure(400, code = "otp_cooldown").asOtpFailure(),
        )
        assertEquals(
            OtpFailure.InvalidCode,
            httpFailure(400, code = "OTP_MISMATCH").asOtpFailure(),
        )
    }

    @Test
    fun `unrecognized code falls back to the http status`() {
        assertEquals(
            OtpFailure.TooManyAttempts,
            httpFailure(429, code = "SMS_PROVIDER_QUOTA").asOtpFailure(),
        )
        assertEquals(
            OtpFailure.Network,
            httpFailure(503, code = "SERVICE_UNAVAILABLE").asOtpFailure(),
        )
    }

    @Test
    fun `envelope failure without http error is classified by its code`() {
        // Ответ 200 с `success: false`: HTTP-кода отказа нет вовсе.
        assertEquals(
            OtpFailure.Expired,
            ApiFailure(
                error = ApiError.Business("OTP_EXPIRED"),
                server = ServerError(httpCode = 200, code = "OTP_EXPIRED"),
            ).asOtpFailure(),
        )
    }

    @Test
    fun `challenge clamps values the ui cannot render`() {
        val token = "otp-token-1"
        assertEquals(
            OtpChallenge(otpToken = token),
            OtpChallenge.of(token, null, null, null),
        )
        assertEquals(
            OtpChallenge(otpToken = token),
            OtpChallenge.of(
                otpToken = token,
                codeLength = 99,
                resendAfterSeconds = -1,
                expiresInSeconds = 0,
            ),
        )
        assertEquals(
            OtpChallenge(
                otpToken = token,
                codeLength = 4,
                resendAfterSeconds = 0,
                expiresInSeconds = 60,
            ),
            // Ноль секунд до повтора допустим: сервер разрешает сразу.
            OtpChallenge.of(
                otpToken = token,
                codeLength = 4,
                resendAfterSeconds = 0,
                expiresInSeconds = 60,
            ),
        )
    }

    private fun httpFailure(httpCode: Int, code: String? = null): ApiFailure = ApiFailure(
        error = ApiError.fromHttpCode(httpCode),
        server = ServerError(httpCode = httpCode, code = code),
    )
}
