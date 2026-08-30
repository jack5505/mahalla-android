package uz.mahalla.feature.wallet.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Единица денег бэкенда (issue #62). Схема стенда её не описывает, а ошибка в
 * сто раз на экране баланса — худшее, что кошелёк может показать, поэтому
 * масштаб выводится из пары «целое поле + его дробный близнец».
 */
class WalletAmountsTest {

    @Test
    fun `tiyin are recognised by the som twin`() {
        assertEquals(WalletAmounts.TIYIN_IN_SOM, WalletAmounts.scaleOf(128_450_000, 1_284_500.0))
    }

    @Test
    fun `whole sums are recognised too`() {
        // Тот же ответ, но целое поле уже в сумах: делить его на сто значило бы
        // показать 12 845 вместо 1 284 500.
        assertEquals(1L, WalletAmounts.scaleOf(1_284_500, 1_284_500.0))
    }

    @Test
    fun `without a twin the minor unit is assumed`() {
        // Отдельное поле `*Som` существует ровно потому, что целое поле хранит
        // что-то другое.
        assertEquals(WalletAmounts.TIYIN_IN_SOM, WalletAmounts.scaleOf(500_000, null))
        assertEquals(WalletAmounts.TIYIN_IN_SOM, WalletAmounts.scaleOf(null, 5_000.0))
        assertEquals(WalletAmounts.TIYIN_IN_SOM, WalletAmounts.scaleOf(0, 0.0))
    }

    @Test
    fun `sign does not confuse the scale`() {
        // Списание сервер шлёт с минусом; сравниваются величины.
        assertEquals(WalletAmounts.TIYIN_IN_SOM, WalletAmounts.scaleOf(-8_450_000, -84_500.0))
    }

    @Test
    fun `conversion keeps whole sums and rounds the remainder`() {
        assertEquals(1_284_500L, WalletAmounts.toSom(128_450_000, WalletAmounts.TIYIN_IN_SOM))
        assertEquals(1_284_500L, WalletAmounts.toSom(1_284_500, 1))
        // Тийины в сумах не показываются: 1 284 500,49 — это 1 284 500.
        assertEquals(1_284_500L, WalletAmounts.toSom(128_450_049, WalletAmounts.TIYIN_IN_SOM))
        assertEquals(0L, WalletAmounts.toSom(null, WalletAmounts.TIYIN_IN_SOM))
        assertEquals(-84_500L, WalletAmounts.toSom(-8_450_000, WalletAmounts.TIYIN_IN_SOM))
    }

    @Test
    fun `unknown server values do not become a status`() {
        assertEquals(WalletStatus.Active, WalletStatus.fromServer(" active "))
        assertEquals(WalletStatus.Blocked, WalletStatus.fromServer("FROZEN"))
        // Новый статус бэкенда не должен рисовать плашку «заблокирован» всем.
        assertEquals(WalletStatus.Unknown, WalletStatus.fromServer("PENDING_KYC"))
        assertEquals(WalletStatus.Unknown, WalletStatus.fromServer(null))
    }

    @Test
    fun `direction and status accept the usual spellings`() {
        assertEquals(TransactionDirection.In, TransactionDirection.fromServer("credit"))
        assertEquals(TransactionDirection.Out, TransactionDirection.fromServer("DEBIT"))
        assertEquals(TransactionDirection.Unknown, TransactionDirection.fromServer("SIDEWAYS"))
        assertEquals(TransactionStatus.Pending, TransactionStatus.fromServer("PROCESSING"))
        assertEquals(TransactionStatus.Completed, TransactionStatus.fromServer("success"))
        assertEquals(TransactionStatus.Failed, TransactionStatus.fromServer("REJECTED"))
        assertEquals(TransactionStatus.Unknown, TransactionStatus.fromServer(""))
    }
}
