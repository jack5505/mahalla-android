package uz.mahalla.feature.wallet.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Перечисления кошелька: незнакомое значение сервера не должно превращаться
 * ни в пугающий статус, ни в неверное направление операции.
 *
 * Пересчёт единиц денег отсюда ушёл: единица бэкенда известна — тийины, —
 * и проверяется в `MoneyTest` (issue #149).
 */
class WalletEnumsTest {

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
