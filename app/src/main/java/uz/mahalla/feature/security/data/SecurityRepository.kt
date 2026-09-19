package uz.mahalla.feature.security.data

import javax.inject.Inject
import javax.inject.Singleton
import uz.mahalla.core.crash.reportSwallowed
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.runCatchingCancellable
import uz.mahalla.data.device.DeviceInfoProvider
import uz.mahalla.data.location.RequestLocationProvider
import uz.mahalla.data.network.auth.CheckSessionRequest
import uz.mahalla.data.network.auth.PinResumeRequest
import uz.mahalla.data.network.auth.SessionApi
import uz.mahalla.data.network.auth.toDto
import uz.mahalla.data.network.ensureSuccess
import uz.mahalla.data.network.payload
import uz.mahalla.data.network.pin.BiometricToggleRequest
import uz.mahalla.data.network.pin.ChangePinRequest
import uz.mahalla.data.network.pin.PinApi
import uz.mahalla.data.prefs.Session
import uz.mahalla.data.prefs.SessionStore
import uz.mahalla.data.security.PinStorage
import uz.mahalla.feature.onboarding.data.OnboardingRepository
import uz.mahalla.feature.security.domain.ChangePinRules
import uz.mahalla.feature.security.domain.ServerPinStatus
import uz.mahalla.feature.security.domain.SessionCheck
import java.time.Clock

/**
 * Аккаунтный PIN и продолжение сессии (issue #102).
 *
 * **Источник истины по PIN — сервер**, локальный Keystore-хэш это его
 * офлайновая копия. Инвариант, который держит весь этот класс: локальный хэш
 * перезаписывается **только** кодом, который бэкенд уже принял. Поэтому
 * [changePin] пишет в Keystore после ответа сервера, а не до, и не пишет
 * вовсе, если сервер отказал: разъехавшиеся PIN человек не различит, и
 * половина приложения перестала бы его пускать.
 *
 * Интерфейс, а не класс: экраны безопасности и app-lock тестируются с фейком,
 * без MockWebServer и без графа DI.
 */
interface SecurityRepository {

    /** Что бэкенд знает о PIN этого устройства. */
    suspend fun pinStatus(): ApiResult<ServerPinStatus>

    /**
     * Сменить PIN. Успех перезаписывает и локальный хэш — иначе экран
     * блокировки продолжал бы принимать старый код, а сервер — новый.
     */
    suspend fun changePin(currentPin: String, newPin: String): ApiResult<Unit>

    /**
     * Переключатель биометрии на сервере. PIN обязателен по контракту: это
     * смена настройки безопасности, и подтверждают её кодом.
     *
     * Успех пишет и локальный флаг: по нему экран блокировки решает, показывать
     * ли системный промпт.
     */
    suspend fun setBiometricEnabled(enabled: Boolean, pin: String): ApiResult<Boolean>

    /** Жива ли сессия и требует ли она PIN (`auth/session/check`). */
    suspend fun checkSession(): ApiResult<SessionCheck>

    /**
     * Продолжить сессию после блокировки (`auth/pin-resume`): успех сохраняет
     * свежую пару токенов и локальный хэш того же кода.
     */
    suspend fun resumeSession(pin: String): ApiResult<Unit>
}

@Singleton
class DefaultSecurityRepository @Inject constructor(
    private val pinApi: PinApi,
    private val sessionApi: SessionApi,
    private val sessionStore: SessionStore,
    private val onboardingRepository: OnboardingRepository,
    private val pinStorage: PinStorage,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val locationProvider: RequestLocationProvider,
    private val clock: Clock,
) : SecurityRepository {

    override suspend fun pinStatus(): ApiResult<ServerPinStatus> {
        val deviceId = deviceInfoProvider.current().deviceId
        val result = apiCall { pinApi.status(deviceId).payload() }
        return when (result) {
            is ApiResult.Success -> ApiResult.Success(
                ServerPinStatus.of(
                    pinSet = result.data.pinSet,
                    biometricEnabled = result.data.biometricEnabled,
                    lockedSecondsRemaining = result.data.lockedSecondsRemaining,
                ),
            )

            is ApiResult.Failure -> result
        }
    }

    override suspend fun changePin(currentPin: String, newPin: String): ApiResult<Unit> {
        // Заведомо негодный код в сеть не отправляем: 400 сказал бы то же
        // самое, но платой были бы запрос и ожидание.
        if (!ChangePinRules.isWellFormed(currentPin) || !ChangePinRules.isWellFormed(newPin)) {
            return ApiResult.Failure(ApiError.Business(INVALID_PIN_CODE))
        }
        if (ChangePinRules.isSameAsCurrent(currentPin, newPin)) {
            return ApiResult.Failure(ApiError.Business(SAME_PIN_CODE))
        }

        val deviceId = deviceInfoProvider.current().deviceId
        val result = apiCall {
            pinApi.change(
                ChangePinRequest(
                    currentPin = currentPin,
                    newPin = newPin,
                    deviceId = deviceId,
                ),
            ).ensureSuccess()
        }

        return when (result) {
            // Сервер принял новый код — только теперь он становится и
            // локальным. Обратный порядок оставил бы в Keystore код, которого
            // бэкенд не знает.
            is ApiResult.Success -> {
                storeAcceptedPin(newPin, operation = "security.changePin")
                ApiResult.Success(Unit)
            }

            is ApiResult.Failure -> result
        }
    }

    /**
     * Записать код, который бэкенд уже принял, в локальную копию.
     *
     * Keystore умеет отказать (ключ инвалидирован сменой блокировки экрана,
     * DataStore недоступен), и тогда в хранилище остался бы **прежний** код —
     * тот, которого сервер больше не знает. Экран блокировки принимал бы его,
     * а `pin-resume` отвергал: разъехавшиеся PIN человек не различит. Поэтому
     * при отказе локальная копия стирается: app-lock без неё не вооружается
     * (см. `AppLockManager`) и включится обратно при следующем успешном входе
     * или разблокировке. Смена PIN при этом состоялась — сервер сказал «да»,
     * и врать об этом из-за локальной записи нельзя.
     */
    private suspend fun storeAcceptedPin(pin: String, operation: String) {
        val saved = runCatchingCancellable { pinStorage.save(pin) }
            .reportSwallowed(operation)
            .isSuccess
        if (!saved) {
            runCatchingCancellable { pinStorage.clear() }.reportSwallowed("$operation.clear")
        }
    }

    override suspend fun setBiometricEnabled(
        enabled: Boolean,
        pin: String,
    ): ApiResult<Boolean> {
        if (!ChangePinRules.isWellFormed(pin)) {
            return ApiResult.Failure(ApiError.Business(INVALID_PIN_CODE))
        }

        val deviceId = deviceInfoProvider.current().deviceId
        val result = apiCall {
            pinApi.setBiometric(
                BiometricToggleRequest(enabled = enabled, deviceId = deviceId, pin = pin),
            ).also { it.ensureSuccess() }.data
        }

        return when (result) {
            is ApiResult.Success -> {
                // Молчание сервера о новом значении — не отказ: `ensureSuccess`
                // уже подтвердил, что переключение состоялось, а состояние тогда
                // выводится из того, о чём просили.
                val applied = result.data ?: enabled
                // Локальный флаг — не источник истины, а то, по чему экран
                // блокировки решает, показывать ли системный промпт. Отказ
                // записи не повод объявить неудачной операцию, которую сервер
                // уже выполнил.
                runCatchingCancellable { onboardingRepository.setBiometricEnabled(applied) }
                    .reportSwallowed("security.setBiometricEnabled")
                ApiResult.Success(applied)
            }

            is ApiResult.Failure -> result
        }
    }

    override suspend fun checkSession(): ApiResult<SessionCheck> {
        val device = deviceInfoProvider.current().toDto()
        val result = apiCall { sessionApi.checkSession(CheckSessionRequest(device)).payload() }
        return when (result) {
            is ApiResult.Success -> ApiResult.Success(
                SessionCheck.of(
                    sessionValid = result.data.sessionValid,
                    pinRequired = result.data.pinRequired,
                    reason = result.data.reason,
                ),
            )

            is ApiResult.Failure -> result
        }
    }

    override suspend fun resumeSession(pin: String): ApiResult<Unit> {
        if (!ChangePinRules.isWellFormed(pin)) {
            return ApiResult.Failure(ApiError.Business(INVALID_PIN_CODE))
        }

        val device = deviceInfoProvider.current().toDto()
        val location = locationProvider.current()
        val result = apiCall {
            sessionApi.pinResume(
                PinResumeRequest(
                    pin = pin,
                    device = device,
                    lat = location.latitude,
                    lng = location.longitude,
                ),
            ).payload()
        }

        return when (result) {
            is ApiResult.Success -> {
                val tokens = result.data.tokens
                val access = tokens?.accessToken?.takeIf { it.isNotBlank() }
                val refresh = tokens?.refreshToken?.takeIf { it.isNotBlank() }
                // Ответ без пары токенов — не повод считать разблокировку
                // провалившейся: сервер сказал «сессия продолжена», а прежняя
                // пара всё ещё в хранилище и всё ещё рабочая. Роняя здесь, мы
                // запирали бы человека за экраном блокировки при живой сессии.
                if (access != null && refresh != null) {
                    sessionStore.save(
                        Session(
                            accessToken = access,
                            refreshToken = refresh,
                            expiresAtEpochSeconds = tokens.accessExpiresIn
                                ?.let { clock.instant().epochSecond + it }
                                ?: Session.UNKNOWN_EXPIRY,
                            sessionId = result.data.sessionId
                                ?: sessionStore.current()?.sessionId,
                        ),
                    )
                }
                // Сервер принял код — значит он и есть аккаунтный PIN, и
                // локальная копия обязана его знать: следующая разблокировка
                // может случиться без сети.
                storeAcceptedPin(pin, operation = "security.resumeSession")
                ApiResult.Success(Unit)
            }

            is ApiResult.Failure -> result
        }
    }

    companion object {
        /** Код короче/длиннее шести цифр или с не-цифрами — до сети не доходит. */
        const val INVALID_PIN_CODE = "PIN_INVALID_FORMAT"

        /** Новый PIN совпал с текущим: смены не произошло бы. */
        const val SAME_PIN_CODE = "PIN_UNCHANGED"
    }
}
