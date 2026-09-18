package uz.mahalla.feature.wallet.ui.pay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.runCatchingCancellable
import uz.mahalla.core.ui.text.OtpFieldState
import uz.mahalla.data.security.PaymentConfirmationMethod
import uz.mahalla.data.security.PaymentConfirmationPolicy
import uz.mahalla.data.security.PinStorage
import uz.mahalla.feature.wallet.data.WalletRepository
import uz.mahalla.feature.wallet.domain.IdempotencyKey
import uz.mahalla.feature.wallet.domain.WalletPaymentConfirmation
import uz.mahalla.feature.wallet.domain.WalletPaymentGuard
import uz.mahalla.feature.wallet.domain.WalletPaymentRejection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Единый ход оплаты из кошелька (задача 8.3 эпика #12).
 *
 * Не ViewModel: оплатой заканчивается запрос **вертикали** (`создать заказ`,
 * `купить билет`), и её ViewModel уже владеет и корзиной, и формой. Поэтому
 * flow — состояние-держатель внутри такой ViewModel: она отдаёт свой
 * `viewModelScope` и сам запрос, а flow берёт на себя то, что у всех вертикалей
 * одинаково:
 *
 * 1. перечитать баланс и не идти в сеть, когда денег заведомо не хватает;
 * 2. спросить подтверждение — биометрию или PIN (см. [PaymentConfirmationPolicy]);
 * 3. отправить **один** запрос на одно подтверждение;
 * 4. разобрать отказ и дать повторить его тем же ключом идемпотентности.
 *
 * Про идемпотентность. Ключ создаётся один раз на оплату и живёт до её конца:
 * «повторить» после таймаута отправляет тот же ключ, потому что соединение
 * могло оборваться уже после того, как сервер списал деньги. Второй запрос
 * при неответившем первом не уходит вовсе, и после успеха — тоже: до 8.3
 * двойное нажатие «оформить» ловилось только флагом `isSubmitting` в состоянии
 * экрана, то есть не ловилось при отказе и повторе.
 *
 * @param T что возвращает запрос вертикали — обычно идентификатор заказа.
 * @param submit сам запрос. Ключ идемпотентности приходит аргументом: класть
 * его в заголовок или в тело — дело вертикали.
 */
class WalletPaymentFlow<T> internal constructor(
    private val walletRepository: WalletRepository,
    private val pinStorage: PinStorage,
    private val confirmationPolicy: PaymentConfirmationPolicy,
    private val scope: CoroutineScope,
    private val newKey: () -> String,
    private val submit: suspend (idempotencyKey: String) -> ApiResult<T>,
) {

    private val mutableState = MutableStateFlow<WalletPaymentState?>(null)

    /** `null` — оплата не начата или уже закрыта; шторки на экране нет. */
    val state: StateFlow<WalletPaymentState?> = mutableState.asStateFlow()

    private val paidChannel = Channel<T>(capacity = Channel.BUFFERED)

    /**
     * Успешные оплаты. Одноразовое событие, а не часть состояния: вертикаль по
     * нему уходит на экран заказа, и при повороте это не должно повториться.
     */
    val paid: Flow<T> = paidChannel.receiveAsFlow()

    private var idempotencyKey: String? = null
    private var submitJob: Job? = null

    /** Загрузка баланса и проверка PIN — их отмена нужна при закрытии шторки. */
    private var confirmJob: Job? = null

    /** Оплата уже прошла: повторять её нечем и незачем. */
    private var isPaid: Boolean = false

    private val current: WalletPaymentState? get() = mutableState.value

    /**
     * Начать оплату на [amountSum] сум.
     *
     * Повторный вызов при начатой оплате игнорируется: это второе нажатие
     * «оплатить», а не второй платёж.
     */
    fun start(amountSum: Long) {
        if (isPaid || current != null) return
        val key = idempotencyKey ?: newKey().also { idempotencyKey = it }
        mutableState.value = WalletPaymentState(
            amountSum = amountSum,
            step = WalletPaymentStep.Preparing,
        )
        confirmJob = scope.launch {
            // Баланс перечитывается перед оплатой, а не берётся с экрана: между
            // открытием корзины и оплатой деньги могли уйти на другой заказ.
            val walletResult = walletRepository.wallet()
            val method = confirmationPolicy.method()
            val pinLength = pinLength()

            val wallet = (walletResult as? ApiResult.Success)?.data
            val rejection = wallet?.let { WalletPaymentGuard.rejection(it, amountSum) }
            if (rejection != null) {
                mutableState.value = current?.copy(
                    availableSum = wallet.availableSum,
                    step = WalletPaymentStep.Rejected,
                    rejection = rejection,
                )
                return@launch
            }

            mutableState.value = current?.copy(
                // Баланс не приехал — так и говорим `null`: показать «доступно
                // 0» было бы неправдой, а запретить оплату — перестраховкой за
                // счёт человека.
                availableSum = wallet?.availableSum,
                method = method,
                pin = OtpFieldState(length = pinLength),
                step = WalletPaymentStep.Confirm,
            )
            // Подтверждать нечем (PIN не настроен, биометрии нет) — платим
            // сразу: раньше 8.3 так было у всех.
            if (method == null) sendRequest(key)
        }
    }

    /** Цифра PIN. Полностью набранный код проверяется сам, кнопки «ок» нет. */
    fun pinChanged(raw: String) {
        val state = current ?: return
        if (!state.isPinStep) return
        val pin = state.pin.onInput(raw)
        mutableState.value = state.copy(pin = pin)
        if (pin.isComplete) verifyPin(pin.code)
    }

    /** Системный промпт подтвердил личность. */
    fun biometricConfirmed() {
        val state = current ?: return
        if (state.step != WalletPaymentStep.Confirm) return
        if (state.method != PaymentConfirmationMethod.Biometric) return
        confirmed()
    }

    /**
     * Промпт закрыт или не сработал.
     *
     * Это не отказ оплаты: PIN настроен у всех, кто прошёл онбординг, и падать
     * в «подтвердить не удалось» из-за мокрого пальца незачем. Нет PIN — тогда
     * подтвердить действительно нечем.
     */
    fun biometricRejected() {
        val state = current ?: return
        if (state.step != WalletPaymentStep.Confirm) return
        confirmJob = scope.launch {
            val length = pinLength()
            val pinConfigured = runCatchingCancellable { pinStorage.isConfigured() }
                .getOrDefault(false)
            mutableState.value = if (pinConfigured) {
                current?.copy(
                    method = PaymentConfirmationMethod.Pin,
                    pin = OtpFieldState(length = length),
                )
            } else {
                current?.copy(
                    step = WalletPaymentStep.Rejected,
                    rejection = WalletPaymentRejection.ConfirmationFailed,
                )
            }
        }
    }

    /**
     * Повтор после отказа сервера — **тем же** ключом идемпотентности и без
     * повторного подтверждения: личность человек уже подтвердил, а отказала
     * сеть.
     */
    fun retry() {
        val state = current ?: return
        if (state.step != WalletPaymentStep.Rejected || !state.canRetry) return
        val key = idempotencyKey ?: return
        sendRequest(key)
    }

    /**
     * Закрыть шторку. Пока запрос в полёте — не закрываем: ответ решает, ушли
     * деньги или нет, и прятать это от человека нельзя.
     */
    fun dismiss() {
        if (current?.step == WalletPaymentStep.Submitting) return
        submitJob?.cancel()
        submitJob = null
        confirmJob?.cancel()
        confirmJob = null
        // Ключ сбрасывается вместе со шторкой: следующая оплата — другая
        // сумма и другой состав, повторять по старому ключу нечего.
        idempotencyKey = null
        mutableState.value = null
    }

    private fun confirmed() {
        val key = idempotencyKey ?: return
        sendRequest(key)
    }

    private fun verifyPin(code: String) {
        val state = current ?: return
        // Проверка локальная, но не мгновенная: PBKDF2 на 120 000 итераций —
        // сотни миллисекунд, и всё это время ячейки должны быть заперты.
        mutableState.value = state.copy(step = WalletPaymentStep.Checking)
        confirmJob = scope.launch {
            val correct = runCatchingCancellable { pinStorage.verify(code) }.getOrDefault(false)
            if (correct) {
                confirmed()
                return@launch
            }
            val attemptsLeft = (current?.attemptsLeft ?: 0) - 1
            mutableState.value = if (attemptsLeft <= 0) {
                current?.copy(
                    step = WalletPaymentStep.Rejected,
                    attemptsLeft = 0,
                    pin = OtpFieldState(length = state.pin.length),
                    rejection = WalletPaymentRejection.ConfirmationFailed,
                )
            } else {
                current?.copy(
                    step = WalletPaymentStep.Confirm,
                    attemptsLeft = attemptsLeft,
                    // Код стирается и помечается ошибкой: набирать поверх
                    // шести заполненных ячеек нечем.
                    pin = state.pin.cleared().asError(),
                )
            }
        }
    }

    /**
     * Отправка. Второй запрос при неответившем первом не уходит: именно это и
     * есть идемпотентность со стороны клиента, потому что серверную
     * поддержку `Idempotency-Key` бэкенд пока не подтвердил.
     */
    private fun sendRequest(key: String) {
        if (isPaid) return
        if (submitJob?.isActive == true) return
        mutableState.value = current?.copy(step = WalletPaymentStep.Submitting, rejection = null)
        submitJob = scope.launch {
            when (val result = submit(key)) {
                is ApiResult.Failure -> mutableState.value = current?.copy(
                    step = WalletPaymentStep.Rejected,
                    rejection = WalletPaymentGuard.rejection(result.failure),
                )

                is ApiResult.Success -> {
                    isPaid = true
                    // Шторка закрывается сама: дальше вертикаль уводит человека
                    // на экран заказа, и подтверждение под ним не нужно.
                    mutableState.value = null
                    paidChannel.trySend(result.data)
                }
            }
        }
    }

    /**
     * Длина сохранённого PIN: у кода, заведённого до issue #51, она четыре, и
     * шесть ячеек вводить было бы нечем.
     */
    private suspend fun pinLength(): Int =
        runCatchingCancellable { pinStorage.configuredLength() }.getOrNull()
            ?: OtpFieldState.DEFAULT_LENGTH
}

/**
 * Сборка [WalletPaymentFlow] для ViewModel вертикали: зависимости приходят из
 * Hilt, а scope и сам запрос — от вызывающего.
 */
@Singleton
class WalletPaymentFlowFactory @Inject constructor(
    private val walletRepository: WalletRepository,
    private val pinStorage: PinStorage,
    private val confirmationPolicy: PaymentConfirmationPolicy,
) {

    fun <T> create(
        scope: CoroutineScope,
        newKey: () -> String = IdempotencyKey::random,
        submit: suspend (idempotencyKey: String) -> ApiResult<T>,
    ): WalletPaymentFlow<T> = WalletPaymentFlow(
        walletRepository = walletRepository,
        pinStorage = pinStorage,
        confirmationPolicy = confirmationPolicy,
        scope = scope,
        newKey = newKey,
        submit = submit,
    )
}
