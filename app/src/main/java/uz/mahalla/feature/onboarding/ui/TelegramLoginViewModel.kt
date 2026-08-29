package uz.mahalla.feature.onboarding.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.feature.auth.data.AuthRepository
import uz.mahalla.feature.auth.data.TelegramAvailability
import uz.mahalla.feature.auth.domain.TelegramLoginState
import uz.mahalla.feature.auth.domain.TelegramPollSchedule
import uz.mahalla.feature.auth.domain.isTelegramPollRecoverable
import java.time.Clock
import javax.inject.Inject

/**
 * Вход через Telegram-бот (issue #46).
 *
 * Сценарий: `init` выдаёт одноразовую ссылку → открываем её в Telegram →
 * опрашиваем `check`, пока пользователь не нажмёт Start.
 *
 * Опрос сознательно **не останавливается на время, пока приложение в фоне**:
 * именно тогда человек и находится в Telegram, и остановка означала бы, что
 * подтверждение замечается только после возвращения. `viewModelScope` живёт,
 * пока жив экран, — этого достаточно.
 */
@HiltViewModel
class TelegramLoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val telegramAvailability: TelegramAvailability,
    private val clock: Clock,
) : MviViewModel<TelegramState, TelegramEvent, TelegramEffect>(TelegramState()) {

    /**
     * Токен живёт только здесь: на диск он не попадает и в логи тоже. Кто им
     * владеет, тот и заберёт сессию — бэкенд устройство в `check` не сверяет.
     */
    private var deepLinkToken: String? = null

    /**
     * Абсолютный срок жизни токена. Абсолютный, а не «осталось N секунд»:
     * опрос перезапускается при каждом возвращении на экран, и относительный
     * остаток продлевал бы окно бесконечно.
     */
    private var deadlineEpochSeconds: Long = 0

    private var pollJob: Job? = null

    init {
        start()
    }

    override fun onEvent(event: TelegramEvent) {
        when (event) {
            TelegramEvent.OpenBotRequested -> currentState.botUrl?.let(::openBot)

            TelegramEvent.ScreenResumed -> onScreenResumed()
            TelegramEvent.RetryRequested -> start()
            TelegramEvent.SmsRequested -> emitEffect(TelegramEffect.SwitchToSms)
        }
    }

    /**
     * Каждая попытка начинается с нового `init`.
     *
     * Переиспользовать прежний токен нельзя: он одноразовый и, скорее всего,
     * уже просрочен — именно поэтому пользователь и жмёт «Попробовать снова».
     */
    private fun start() {
        pollJob?.cancel()
        deepLinkToken = null
        updateState {
            copy(
                status = TelegramStatus.PREPARING,
                botUrl = null,
                phone = null,
                apiFailure = null,
            )
        }

        viewModelScope.launch {
            when (val result = authRepository.startTelegramLogin()) {
                is ApiResult.Success -> {
                    val challenge = result.data
                    deepLinkToken = challenge.deepLinkToken
                    deadlineEpochSeconds =
                        clock.instant().epochSecond + challenge.expiresInSeconds
                    updateState {
                        copy(status = TelegramStatus.WAITING, botUrl = challenge.botUrl)
                    }
                    openBot(challenge.botUrl)
                    poll(immediate = false)
                }

                is ApiResult.Failure -> updateState {
                    copy(status = TelegramStatus.FAILED, apiFailure = result.failure)
                }
            }
        }
    }

    /**
     * Адресат определяется в момент открытия, а не при создании экрана:
     * Telegram могли установить как раз сейчас — человек ушёл за ним и
     * вернулся.
     */
    private fun openBot(url: String) {
        emitEffect(
            TelegramEffect.OpenBot(
                url = url,
                packageName = telegramAvailability.installedPackage(),
            ),
        )
    }

    /**
     * Возвращение на экран — самый вероятный момент подтверждения: человек
     * нажал Start и переключился обратно. Проверяем сразу и заодно сбрасываем
     * выросшую паузу опроса.
     */
    private fun onScreenResumed() {
        if (currentState.status != TelegramStatus.WAITING) return
        poll(immediate = true)
    }

    private fun poll(immediate: Boolean) {
        val token = deepLinkToken ?: return
        pollJob?.cancel()

        val remainingMillis = (deadlineEpochSeconds - clock.instant().epochSecond) * MILLIS
        if (remainingMillis <= 0) {
            expire()
            return
        }

        pollJob = viewModelScope.launch {
            // Ограничение по времени снаружи цикла: срок жизни токена задаёт
            // сервер, и переживать его опрос не должен ни на секунду.
            withTimeoutOrNull(remainingMillis) {
                var attempt = 0
                while (isActive) {
                    if (attempt > 0 || !immediate) {
                        delay(TelegramPollSchedule.delayMillisAt(attempt))
                    }
                    attempt++

                    when (val result = authRepository.checkTelegramLogin(token)) {
                        is ApiResult.Success -> {
                            val state = result.data
                            if (state is TelegramLoginState.Confirmed) {
                                // Результат применяется здесь же, а не после
                                // выхода из `withTimeoutOrNull`: возвращение на
                                // экран (самый частый момент подтверждения)
                                // отменяет этот job, а отмена вклинивается
                                // только на точке приостановки. Между ответом
                                // сервера и `confirm` их быть не должно, иначе
                                // подтверждённый вход теряется вместе с уже
                                // потраченным токеном.
                                confirm(state)
                                return@withTimeoutOrNull
                            }
                        }

                        is ApiResult.Failure ->
                            if (!result.failure.isTelegramPollRecoverable()) {
                                updateState {
                                    copy(
                                        status = TelegramStatus.FAILED,
                                        apiFailure = result.failure,
                                    )
                                }
                                return@withTimeoutOrNull
                            }
                    }
                }
            }

            // Статус изменился — значит цикл уже всё рассказал пользователю
            // (подтверждение, отказ или просьба подтвердить номер).
            if (currentState.status == TelegramStatus.WAITING) expire()
        }
    }

    /**
     * Токен отработал — держать его в памяти дальше незачем, и опрашивать
     * больше нечего.
     *
     * Статус меняется в обоих случаях: пока он оставался [TelegramStatus.WAITING],
     * экран крутил «ждём подтверждения» и после успеха, а `ScreenResumed`
     * уходил в `return` по обнулённому токену — выйти из этого состояния было
     * нельзя (issue #49).
     */
    private fun confirm(state: TelegramLoginState.Confirmed) {
        deepLinkToken = null
        if (state.requiresPhoneVerify) {
            // Автоматически на форму номера не уводим: подтверждение через
            // Telegram уже случилось, и человек должен увидеть, почему этого
            // оказалось мало.
            updateState {
                copy(
                    status = TelegramStatus.PHONE_VERIFY,
                    botUrl = null,
                    phone = state.phone,
                    apiFailure = null,
                )
            }
        } else {
            updateState { copy(status = TelegramStatus.CONFIRMED, botUrl = null) }
            emitEffect(TelegramEffect.Confirmed(isNewUser = state.login.isNewUser))
        }
    }

    private fun expire() {
        deepLinkToken = null
        updateState { copy(status = TelegramStatus.EXPIRED, botUrl = null) }
    }

    private companion object {
        const val MILLIS = 1_000L
    }
}
