package uz.mahalla.data.session

import uz.mahalla.core.crash.reportSwallowed
import uz.mahalla.core.result.runCatchingCancellable
import uz.mahalla.data.db.dao.CartDraftDao
import uz.mahalla.data.db.dao.OrderDao
import uz.mahalla.data.push.PushTokenRegistrar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Локальные следы вошедшего, которые выход и `AuthRepository.clearLocalIdentity`
 * (сам выход, чужой аккаунт на устройстве, истёкшая сессия — issue #341) не
 * чистили: кэш заказов, черновик корзины, токен пушей. Без этого следующий
 * человек на том же устройстве видел бы чужие заказы и корзину из кэша и
 * получал бы чужие пуши, пока сервер не перепривяжет токен ближайшим входом.
 *
 * Кэш мест (`PlaceEntity`) сюда осознанно не входит — это общий каталог по
 * координатам, а не личные данные (`docs/adr/0003-room-kak-kesh.md`).
 *
 * Общий вызов для [uz.mahalla.feature.auth.data.DefaultAuthRepository] и
 * [uz.mahalla.data.network.TokenAuthenticator]: обоим нужна ровно одна и та же
 * уборка, только по разным поводам.
 */
@Singleton
class LocalUserDataCleaner @Inject constructor(
    private val orderDao: OrderDao,
    private val cartDraftDao: CartDraftDao,
    private val pushTokenRegistrar: PushTokenRegistrar,
) {

    /**
     * Каждый шаг независим, как и в `clearLocalIdentity`: недоступная БД не
     * должна помешать забыть токен пушей, и наоборот.
     */
    suspend fun clear() {
        runCatchingCancellable { orderDao.clear() }.reportSwallowed("session.clearOrders")
        runCatchingCancellable { cartDraftDao.clearAll() }.reportSwallowed("session.clearCart")
        pushTokenRegistrar.forget()
    }
}
