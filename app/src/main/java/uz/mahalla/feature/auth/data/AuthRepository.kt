package uz.mahalla.feature.auth.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.data.device.DeviceInfoProvider
import uz.mahalla.data.location.RequestLocationProvider
import uz.mahalla.data.network.auth.AuthApi
import uz.mahalla.data.network.auth.PinLoginRequest
import uz.mahalla.data.network.auth.RefreshTokenRequest
import uz.mahalla.data.network.auth.SendOtpRequest
import uz.mahalla.data.network.auth.SetupPinRequest
import uz.mahalla.data.network.auth.TelegramCheckRequest
import uz.mahalla.data.network.auth.TelegramCheckResponse
import uz.mahalla.data.network.auth.TelegramInitRequest
import uz.mahalla.data.network.auth.TokenPairDto
import uz.mahalla.data.network.auth.UserDto
import uz.mahalla.data.network.auth.VerifyOtpRequest
import uz.mahalla.data.network.auth.toDto
import uz.mahalla.data.network.payload
import uz.mahalla.data.prefs.Session
import uz.mahalla.data.prefs.SessionStore
import uz.mahalla.data.prefs.UserProfile
import uz.mahalla.data.prefs.UserProfileStore
import uz.mahalla.data.security.PinStorage
import uz.mahalla.feature.auth.domain.LoginResult
import uz.mahalla.feature.auth.domain.OtpChallenge
import uz.mahalla.feature.auth.domain.ServerPin
import uz.mahalla.feature.auth.domain.ServerPinChallenge
import uz.mahalla.feature.auth.domain.ServerPinStep
import uz.mahalla.feature.auth.domain.TelegramChallenge
import uz.mahalla.feature.auth.domain.TelegramLoginState
import uz.mahalla.feature.auth.domain.VerificationResult
import uz.mahalla.feature.auth.domain.isTelegramPending
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Авторизация по SMS-коду (эпик 3, сквозная задача).
 *
 * Интерфейс, а не класс: ViewModel'и онбординга тестируются с фейком, без
 * MockWebServer и без графа DI.
 */
interface AuthRepository {

    /** Есть ли сохранённая сессия. Источник — [SessionStore], а не память. */
    val isAuthorized: Flow<Boolean>

    suspend fun requestCode(phoneE164: String): ApiResult<OtpChallenge>

    /**
     * Код проверяется по [otpToken] из [requestCode], а не по номеру телефона:
     * так решил бэкенд (issue #42), и это заодно означает, что просроченный
     * или чужой токен отсекается сервером, а не сравнением строк на клиенте.
     *
     * Верный код ещё не значит «вошёл»: токены бэкенд выдаёт только после
     * PIN-шага (issue #51). [VerificationResult.Authorized] — сессия
     * сохранена, [VerificationResult.PinRequired] — нужен [completeServerPin].
     */
    suspend fun verifyCode(otpToken: String, code: String): ApiResult<VerificationResult>

    /**
     * Незавершённый вход: что бэкенд ждёт после проверки кода. `null` —
     * PIN-шаг не нужен (токены уже есть) или входа не было.
     *
     * Живёт только в памяти процесса: `sessionId` — одноразовый пропуск к
     * токенам, и переживать перезапуск приложения он не должен.
     */
    val pendingServerPin: ServerPinChallenge?

    /**
     * Завершить вход PIN'ом: `auth/setup-pin` либо `auth/pin-login` — что
     * именно, решает [pendingServerPin]. Успех сохраняет сессию.
     */
    suspend fun completeServerPin(pin: String): ApiResult<LoginResult>

    /**
     * Начать вход через Telegram (issue #46): получить одноразовую ссылку на
     * бота. Номер телефона не нужен — его сообщит боту сам Telegram.
     */
    suspend fun startTelegramLogin(): ApiResult<TelegramChallenge>

    /**
     * Нажали ли Start в боте. [TelegramLoginState.Pending] — «ещё нет», это не
     * ошибка; успех сохраняет сессию, как и [verifyCode].
     */
    suspend fun checkTelegramLogin(deepLinkToken: String): ApiResult<TelegramLoginState>

    /**
     * Явное обновление токенов. Обычный путь — `TokenAuthenticator` по 401;
     * этот метод нужен там, где сессию надо проверить до запроса (запуск
     * приложения, разблокировка по PIN).
     */
    suspend fun refresh(): ApiResult<Unit>

    /** Локальные данные чистятся всегда, даже если запрос к серверу не ушёл. */
    suspend fun logout()
}

@Singleton
class DefaultAuthRepository @Inject constructor(
    private val authApi: AuthApi,
    private val sessionStore: SessionStore,
    private val userProfileStore: UserProfileStore,
    private val pinStorage: PinStorage,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val locationProvider: RequestLocationProvider,
    private val clock: Clock,
) : AuthRepository {

    override val isAuthorized: Flow<Boolean> = sessionStore.session.map { it != null }

    override suspend fun requestCode(phoneE164: String): ApiResult<OtpChallenge> {
        val device = deviceInfoProvider.current().toDto()
        val location = locationProvider.current()

        val result = apiCall {
            authApi.sendOtp(
                SendOtpRequest(
                    phone = phoneE164,
                    device = device,
                    lat = location.latitude,
                    lng = location.longitude,
                ),
            ).payload()
        }

        return when (result) {
            is ApiResult.Success -> {
                val otpToken = result.data.otpToken
                // Без токена проверить код нечем: уходить на экран ввода
                // означало бы гарантированную ошибку после шестой цифры.
                if (otpToken.isNullOrBlank()) {
                    ApiResult.Failure(ApiError.Serialization)
                } else {
                    ApiResult.Success(
                        OtpChallenge.of(
                            otpToken = otpToken,
                            codeLength = null,
                            resendAfterSeconds = result.data.cooldownSeconds,
                            expiresInSeconds = result.data.expiresInSeconds,
                            // Куда именно ушёл код, знает только сервер
                            // (issue #54): для связанного с ботом номера это
                            // Telegram, и человеку это надо сказать.
                            channel = result.data.channel,
                        ),
                    )
                }
            }

            is ApiResult.Failure -> result
        }
    }

    @Volatile
    override var pendingServerPin: ServerPinChallenge? = null
        private set

    override suspend fun verifyCode(
        otpToken: String,
        code: String,
    ): ApiResult<VerificationResult> {
        val device = deviceInfoProvider.current().toDto()
        val location = locationProvider.current()

        val result = apiCall {
            authApi.verifyOtp(
                VerifyOtpRequest(
                    otpToken = otpToken,
                    otpCode = code,
                    device = device,
                    lat = location.latitude,
                    lng = location.longitude,
                ),
            ).payload()
        }

        return when (result) {
            is ApiResult.Success -> {
                val login = LoginResult(isNewUser = result.data.user?.fullName.isNullOrBlank())
                val session = result.data.tokens.toSession(sessionId = result.data.sessionId)
                if (session != null) {
                    pendingServerPin = null
                    sessionStore.save(session)
                    saveProfile(result.data.user)
                    return ApiResult.Success(VerificationResult.Authorized(login))
                }

                // Токенов нет — значит бэкенд ждёт PIN (issue #51). Раньше это
                // считалось неразобранным ответом, и верный код заканчивался
                // тупиком «Nimadir xato ketdi».
                val step = ServerPin.stepOf(
                    nextStep = result.data.nextStep,
                    pinConfigured = result.data.user?.pinSetup == true,
                )
                val sessionId = result.data.sessionId?.takeIf { it.isNotBlank() }
                // Установка PIN без sessionId невозможна: отправлять нечего.
                if (step == ServerPinStep.Setup && sessionId == null) {
                    pendingServerPin = null
                    return ApiResult.Failure(ApiError.Serialization)
                }

                val challenge = ServerPinChallenge(step = step, sessionId = sessionId)
                pendingServerPin = challenge
                ApiResult.Success(VerificationResult.PinRequired(challenge, login))
            }

            is ApiResult.Failure -> result
        }
    }

    override suspend fun completeServerPin(pin: String): ApiResult<LoginResult> {
        val challenge = pendingServerPin ?: return ApiResult.Failure(ApiError.Unauthorized)
        return when (challenge.step) {
            ServerPinStep.Setup -> setupServerPin(
                sessionId = challenge.sessionId ?: return ApiResult.Failure(ApiError.Unauthorized),
                pin = pin,
            )

            ServerPinStep.Enter -> serverPinLogin(pin)
        }
    }

    private suspend fun setupServerPin(sessionId: String, pin: String): ApiResult<LoginResult> {
        val result = apiCall {
            authApi.setupPin(
                SetupPinRequest(sessionId = sessionId, pin = pin, pinConfirm = pin),
            ).payload()
        }

        return when (result) {
            is ApiResult.Success -> saveLogin(
                tokens = result.data.tokens,
                sessionId = result.data.sessionId ?: sessionId,
                user = result.data.user,
            )

            is ApiResult.Failure -> result
        }
    }

    private suspend fun serverPinLogin(pin: String): ApiResult<LoginResult> {
        val device = deviceInfoProvider.current().toDto()
        val location = locationProvider.current()

        val result = apiCall {
            authApi.pinLogin(
                PinLoginRequest(
                    pin = pin,
                    device = device,
                    lat = location.latitude,
                    lng = location.longitude,
                ),
            ).payload()
        }

        return when (result) {
            is ApiResult.Success -> saveLogin(
                tokens = result.data.tokens,
                sessionId = result.data.sessionId,
                user = result.data.user,
            )

            is ApiResult.Failure -> result
        }
    }

    /** Общий хвост обоих PIN-запросов: без токенов вход не состоялся. */
    private suspend fun saveLogin(
        tokens: TokenPairDto?,
        sessionId: String?,
        user: UserDto?,
    ): ApiResult<LoginResult> {
        val session = tokens.toSession(sessionId = sessionId)
            ?: return ApiResult.Failure(ApiError.Serialization)
        sessionStore.save(session)
        saveProfile(user)
        pendingServerPin = null
        return ApiResult.Success(LoginResult(isNewUser = user?.fullName.isNullOrBlank()))
    }

    /**
     * Кто вошёл — единственный источник этих данных (issue #61): отдельного
     * `GET /users/me` у бэкенда нет, спросить профиль потом будет нечем.
     * Ответ без блока `user` прежний профиль не стирает: это не «пользователь
     * стал безымянным», а «эндпоинт про другое».
     */
    private suspend fun saveProfile(user: UserDto?) {
        if (user == null) return
        userProfileStore.save(
            UserProfile(
                id = user.id,
                phone = user.phone,
                fullName = user.fullName,
                avatarUrl = user.avatarUrl,
            ),
        )
    }

    override suspend fun startTelegramLogin(): ApiResult<TelegramChallenge> {
        val device = deviceInfoProvider.current().toDto()
        val location = locationProvider.current()

        val result = apiCall {
            authApi.telegramInit(
                TelegramInitRequest(
                    device = device,
                    lat = location.latitude,
                    lng = location.longitude,
                ),
            ).payload()
        }

        return when (result) {
            is ApiResult.Success -> TelegramChallenge.of(
                deepLinkToken = result.data.deepLinkToken,
                botUrl = result.data.telegramBotUrl,
                expiresInSeconds = result.data.expiresInSeconds,
            )
                // Либо токена нет (проверять будет нечего), либо ссылка ведёт
                // не в Telegram — открывать её мы всё равно откажемся, так что
                // честнее сразу уйти на SMS, чем показать экран-тупик.
                ?.let { ApiResult.Success(it) }
                ?: ApiResult.Failure(ApiError.Serialization)

            is ApiResult.Failure -> result
        }
    }

    override suspend fun checkTelegramLogin(
        deepLinkToken: String,
    ): ApiResult<TelegramLoginState> {
        val device = deviceInfoProvider.current().toDto()
        val location = locationProvider.current()

        val result = apiCall {
            authApi.telegramCheck(
                TelegramCheckRequest(
                    deepLinkToken = deepLinkToken,
                    device = device,
                    lat = location.latitude,
                    lng = location.longitude,
                ),
            ).payload()
        }

        return when (result) {
            is ApiResult.Success -> onTelegramConfirmed(result.data)

            is ApiResult.Failure ->
                // «Ещё не нажали Start» приезжает отказом (400 `TG_PENDING`),
                // но отказом не является: опрос продолжается, а пользователю
                // ничего не показываем.
                if (result.failure.isTelegramPending()) {
                    ApiResult.Success(TelegramLoginState.Pending)
                } else {
                    result
                }
        }
    }

    /**
     * Подтверждённый вход. Сессия сохраняется только когда телефон аккаунта
     * подтверждён: иначе бэкенд сам просит добить вход SMS-кодом, и держать до
     * этого момента живые токены незачем — отличить такую сессию от полноценной
     * в остальном приложении было бы нечем.
     */
    private suspend fun onTelegramConfirmed(
        response: TelegramCheckResponse,
    ): ApiResult<TelegramLoginState> {
        val login = LoginResult(isNewUser = response.user?.fullName.isNullOrBlank())
        if (response.requiresPhoneVerify) {
            return ApiResult.Success(
                TelegramLoginState.Confirmed(
                    login = login,
                    requiresPhoneVerify = true,
                    // Номер бот уже сообщил бэкенду — показать его человеку
                    // дешевле, чем заставлять вспоминать, что именно он
                    // подтверждал.
                    phone = response.user?.phone?.takeIf { it.isNotBlank() },
                ),
            )
        }

        // Токены у этого эндпоинта лежат в корне ответа, а не в `tokens`.
        val session = TokenPairDto(
            accessToken = response.accessToken,
            refreshToken = response.refreshToken,
            accessExpiresIn = response.accessExpiresIn,
            refreshExpiresIn = response.refreshExpiresIn,
        ).toSession(sessionId = null) ?: return ApiResult.Failure(ApiError.Serialization)

        sessionStore.save(session)
        saveProfile(response.user)
        return ApiResult.Success(TelegramLoginState.Confirmed(login = login))
    }

    override suspend fun refresh(): ApiResult<Unit> {
        val session = sessionStore.current() ?: return ApiResult.Failure(ApiError.Unauthorized)
        val device = deviceInfoProvider.current().toDto()
        val location = locationProvider.current()

        val result = apiCall {
            authApi.refresh(
                RefreshTokenRequest(
                    refreshToken = session.refreshToken,
                    device = device,
                    lat = location.latitude,
                    lng = location.longitude,
                ),
            ).payload()
        }

        return when (result) {
            is ApiResult.Success -> {
                val refreshed = result.data.tokens
                    .toSession(sessionId = result.data.sessionId ?: session.sessionId)
                    ?: return ApiResult.Failure(ApiError.Serialization)
                sessionStore.save(refreshed)
                ApiResult.Success(Unit)
            }

            is ApiResult.Failure -> {
                // Refresh-токен мёртв — сессии больше нет, иначе приложение
                // будет вечно ходить с невалидной парой токенов.
                if (result.error == ApiError.Unauthorized) sessionStore.clear()
                result
            }
        }
    }

    override suspend fun logout() {
        val session = sessionStore.current()
        // Ответ сервера не важен: локальный выход должен случиться и без сети,
        // иначе пользователь останется «залогиненным» на устройстве.
        if (session != null) {
            apiCall { authApi.logout(sessionId = session.sessionId, allDevices = false) }
        }
        sessionStore.clear()
        // Имя и номер прошлого пользователя после выхода не показываем.
        userProfileStore.clear()
        // Незавершённый вход тоже сбрасываем: `sessionId` от прошлой попытки
        // после выхода не значит ничего.
        pendingServerPin = null
        // PIN защищает именно эту сессию — оставлять его от прошлого
        // пользователя нельзя.
        pinStorage.clear()
    }

    /**
     * Сервер сообщает «через сколько истечёт», хранить полезно «когда
     * истечёт»: после перезапуска приложения относительное значение
     * бессмысленно. Не сообщил — срок неизвестен, а не «истёк в 1970».
     *
     * `null` означает ответ без пары токенов: такой успех для клиента
     * бесполезен, вызывающий превращает его в ошибку.
     */
    private fun TokenPairDto?.toSession(sessionId: String?): Session? {
        val access = this?.accessToken?.takeIf { it.isNotBlank() } ?: return null
        val refresh = this.refreshToken?.takeIf { it.isNotBlank() } ?: return null
        return Session(
            accessToken = access,
            refreshToken = refresh,
            expiresAtEpochSeconds = accessExpiresIn
                ?.let { clock.instant().epochSecond + it }
                ?: Session.UNKNOWN_EXPIRY,
            sessionId = sessionId,
        )
    }
}
