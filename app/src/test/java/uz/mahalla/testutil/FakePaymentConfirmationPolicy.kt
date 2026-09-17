package uz.mahalla.testutil

import uz.mahalla.data.security.PaymentConfirmationMethod
import uz.mahalla.data.security.PaymentConfirmationPolicy

/**
 * Чем подтверждать оплату — задаётся тестом (задача 8.3 эпика #12).
 *
 * По умолчанию `null`: подтверждать нечем, и flow оплаты идёт сразу в сеть.
 * Так ведут себя тесты, которым важна не форма подтверждения, а сам заказ.
 */
class FakePaymentConfirmationPolicy(
    var method: PaymentConfirmationMethod? = null,
) : PaymentConfirmationPolicy {

    override suspend fun method(): PaymentConfirmationMethod? = method
}
