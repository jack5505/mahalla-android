package uz.mahalla.data.network

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Сессия кончилась не по воле пользователя (issue #138).
 *
 * До этого о её смерти не знал никто: [TokenAuthenticator] стирал мёртвую
 * пару токенов и возвращал 401 наверх, а человек оставался
 * внутри приложения, где каждый экран показывал ошибку сервера и кнопку
 * «повторить», которая не могла помочь — токена-то больше нет. Событие уводит
 * его на вход, туда же, куда ведёт явный выход из профиля.
 *
 * Явный выход сюда не попадает: экран профиля уводит на вход сам, и второе
 * сообщение «сессия истекла» там было бы неправдой.
 *
 * Без `replay`: потерянное событие лучше повторного. Новый подписчик
 * (пересоздание activity) иначе получил бы прошлую смерть сессии и выкинул на
 * вход человека, который к этому времени уже вошёл заново. А если событие
 * действительно потерялось — следующий запуск разберётся сам:
 * [uz.mahalla.feature.root.ui.RootViewModel] без сессии в основной граф не
 * пускает.
 */
@Singleton
class SessionExpiry @Inject constructor() {

    private val events = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val expired: Flow<Unit> = events.asSharedFlow()

    /**
     * Не suspend: вызывается с потока OkHttp, из `Authenticator`. Буфера на
     * одно событие достаточно — подряд идущие 401 означают одно и то же.
     */
    fun notifyExpired() {
        events.tryEmit(Unit)
    }
}
