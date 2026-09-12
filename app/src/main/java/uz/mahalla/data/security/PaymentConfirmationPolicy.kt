package uz.mahalla.data.security

import uz.mahalla.core.crash.reportSwallowed
import uz.mahalla.core.result.runCatchingCancellable
import uz.mahalla.data.prefs.SettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton

/** Чем человек подтверждает оплату из кошелька (задача 8.3 эпика #12). */
enum class PaymentConfirmationMethod {
    /** Системный промпт: включён в настройках и датчик доступен. */
    Biometric,

    /** Локальный PIN (эпик 1.4): фолбэк биометрии и вариант по умолчанию. */
    Pin,
}

/**
 * Чем подтверждать платёж.
 *
 * За интерфейсом, потому что `BiometricManager` — статика Android, а
 * `SettingsDataStore` — DataStore: с ними flow оплаты пришлось бы тестировать
 * под Robolectric вместо чистого JVM.
 *
 * Подтверждение здесь **локальное**: сервер его не проверяет — серверного
 * подтверждения платежа в контракте нет (`auth/session/check` и `pin-resume`
 * ждут issue #102). То есть это защита от чужих рук на разблокированном
 * телефоне, а не второй фактор для бэкенда.
 */
interface PaymentConfirmationPolicy {

    /**
     * Метод подтверждения; `null` — подтверждать нечем: PIN не настроен и
     * биометрия недоступна. Тогда оплата идёт без подтверждения — запирать
     * человека в форме, из которой нет выхода, хуже, чем оплатить по одному
     * нажатию, как это и было до 8.3.
     */
    suspend fun method(): PaymentConfirmationMethod?
}

@Singleton
class DefaultPaymentConfirmationPolicy @Inject constructor(
    private val settings: SettingsDataStore,
    private val pinStorage: PinStorage,
    private val biometricAvailability: BiometricAvailability,
) : PaymentConfirmationPolicy {

    /**
     * Биометрия впереди PIN: она быстрее и не показывает код соседям в
     * автобусе. Но только когда её **включили сами** — флаг `biometricEnabled`
     * ставится после успешного промпта в онбординге (3.5), и без него
     * системный диалог был бы сюрпризом.
     *
     * Отказ хранилища (потерянный ключ Keystore, недоступный DataStore) — не
     * повод обвалить оплату: считаем метод ненастроенным и идём дальше по
     * списку.
     */
    override suspend fun method(): PaymentConfirmationMethod? {
        val biometricEnabled = runCatchingCancellable { settings.current().biometricEnabled }
            .reportSwallowed("payment.confirmation.settings")
            .getOrDefault(false)
        if (biometricEnabled && biometricAvailability.status().canEnable) {
            return PaymentConfirmationMethod.Biometric
        }
        val pinConfigured = runCatchingCancellable { pinStorage.isConfigured() }
            .reportSwallowed("payment.confirmation.pin")
            .getOrDefault(false)
        return PaymentConfirmationMethod.Pin.takeIf { pinConfigured }
    }
}
