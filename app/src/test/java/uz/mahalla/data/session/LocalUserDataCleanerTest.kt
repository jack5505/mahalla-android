package uz.mahalla.data.session

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uz.mahalla.data.db.entity.CartDraftItemEntity
import uz.mahalla.data.db.entity.OrderEntity
import uz.mahalla.data.push.PushTokenRegistrar
import uz.mahalla.data.push.PushTokenStore
import uz.mahalla.testutil.FakeCartDraftDao
import uz.mahalla.testutil.FakeOrderDao
import uz.mahalla.testutil.FakePreferencesDataStore
import uz.mahalla.testutil.FakePushTokenProvider

/**
 * Уборка локальных данных вошедшего на выходе/истёкшей сессии (issue #341):
 * кэш заказов, черновик корзины и токен пушей. Кэш мест (`PlaceEntity`) сюда
 * не входит осознанно (`docs/adr/0003`) — у [LocalUserDataCleaner] для него и
 * зависимости нет.
 */
class LocalUserDataCleanerTest {

    @Test
    fun `clear wipes orders, the cart draft and the push token`() = runTest {
        val orderDao = FakeOrderDao()
        val cartDraftDao = FakeCartDraftDao()
        val pushTokenProvider = FakePushTokenProvider(token = "fcm-1")
        val pushTokenStore = PushTokenStore(FakePreferencesDataStore())
        pushTokenStore.save("fcm-1")
        orderDao.upsert(listOf(order("o-1")))
        cartDraftDao.upsert(draft("place-1", "osh"))

        LocalUserDataCleaner(
            orderDao = orderDao,
            cartDraftDao = cartDraftDao,
            pushTokenRegistrar = PushTokenRegistrar(pushTokenProvider, pushTokenStore),
        ).clear()

        assertEquals(emptyList<OrderEntity>(), orderDao.observeAll().first())
        assertEquals(emptyList<CartDraftItemEntity>(), cartDraftDao.observe("place-1").first())
        // Локальная запись стёрта, и отвязка на Firebase запрошена — иначе
        // следующий человек на устройстве получал бы чужие пуши, пока
        // сервер не перепривяжет токен новым входом (issue #341).
        assertNull(pushTokenStore.current())
        assertEquals(1, pushTokenProvider.deleteCalls)
    }

    @Test
    fun `clear survives an empty database and no stored token`() = runTest {
        val cleaner = LocalUserDataCleaner(
            orderDao = FakeOrderDao(),
            cartDraftDao = FakeCartDraftDao(),
            pushTokenRegistrar = PushTokenRegistrar(
                FakePushTokenProvider(),
                PushTokenStore(FakePreferencesDataStore()),
            ),
        )

        cleaner.clear()
    }

    private fun order(id: String) = OrderEntity(
        id = id,
        placeId = "place-1",
        placeName = "Osh markazi",
        status = "NEW",
        totalSum = 50_000,
        createdAtEpochSeconds = 1_774_000_000L,
    )

    private fun draft(placeId: String, productId: String) = CartDraftItemEntity(
        placeId = placeId,
        lineId = productId,
        productId = productId,
        name = productId,
        priceSum = 30_000,
        quantity = 1,
    )
}
