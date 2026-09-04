package uz.mahalla.feature.queue.data

import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.data.network.ensureSuccess
import uz.mahalla.data.network.payload
import uz.mahalla.feature.queue.domain.WalkInRequest
import uz.mahalla.feature.queue.domain.WalkInRequestValidator
import uz.mahalla.feature.queue.domain.WalkInStatus
import uz.mahalla.feature.queue.domain.WalkInTicket
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Очередь (walk-in) — клиентская половина: взять талон и отменить его
 * (issue #96).
 *
 * Читать состояние талона у бэкенда нечем (см. [WalkInApi]), поэтому
 * [activeTicket] отдаёт **последнее известное** состояние из локального
 * хранилища, а не спрашивает сервер. Это разные вещи, и экран обязан
 * показывать разницу — правило в [WalkInTicket.showsQueueInfo].
 *
 * Интерфейс — ради тестов ViewModel: экран проверяется без MockWebServer.
 */
interface WalkInRepository {

    /**
     * Записаться в очередь.
     *
     * @param placeName название заведения: в ответе сервера его нет, а талон
     * без имени места читается как чужой.
     */
    suspend fun take(request: WalkInRequest, placeName: String): ApiResult<WalkInTicket>

    /**
     * Отменить свой талон. Возвращается состояние после отмены — либо из
     * ответа сервера, либо выведенное из самого факта успешной отмены (см.
     * реализацию).
     */
    suspend fun cancel(ticket: WalkInTicket): ApiResult<WalkInTicket>

    /** Последнее известное состояние живого талона этого заведения. */
    suspend fun activeTicket(placeId: String): WalkInTicket?

    companion object {
        /** Код отказа, когда запрос не прошёл проверку ещё на клиенте. */
        const val INVALID_REQUEST_CODE = "WALKIN_REQUEST_INVALID"
    }
}

@Singleton
class DefaultWalkInRepository @Inject constructor(
    private val api: WalkInApi,
    private val store: WalkInTicketStore,
    private val clock: Clock,
) : WalkInRepository {

    /**
     * Незаполненный запрос в сеть не уходит: 400 от сервера сказал бы то же
     * самое, но платой были бы запрос и молчание экрана на время его
     * выполнения.
     *
     * Ответ без `id` — отказ: отменить такой талон нечем, а показать его как
     * взятый значило бы запереть человека в очереди без выхода.
     */
    override suspend fun take(
        request: WalkInRequest,
        placeName: String,
    ): ApiResult<WalkInTicket> {
        val trimmed = request.trimmed()
        if (WalkInRequestValidator.validate(trimmed).isNotEmpty()) {
            return ApiResult.Failure(
                ApiError.Business(WalkInRepository.INVALID_REQUEST_CODE),
            )
        }

        val result = apiCall {
            api.send(
                SendWalkInRequest(
                    placeId = trimmed.placeId,
                    userName = trimmed.userName,
                    serviceName = trimmed.serviceOrNull(),
                ),
            ).payload()
        }

        return when (result) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                val ticket = result.data.toDomain(
                    placeId = trimmed.placeId,
                    placeName = placeName,
                    receivedAt = clock.instant(),
                ) ?: return ApiResult.Failure(ApiError.Serialization)
                store.save(ticket)
                ApiResult.Success(ticket)
            }
        }
    }

    /**
     * Ответ на отмену — тот же талон, но обязательным его разбор не считаем:
     * `ensureSuccess()` уже подтвердил, что сервер отменил именно этот талон.
     * Если в ответе не окажется годного тела, состояние выводится из факта
     * отмены ([WalkInStatus.Cancelled]) — иначе удачная отмена выглядела бы
     * как «отменить не удалось» (та же грабля, что у заказов еды, issue #9).
     *
     * Локальный талон при этом снимается: он больше не живой, и запись в это
     * же заведение снова доступна.
     */
    override suspend fun cancel(ticket: WalkInTicket): ApiResult<WalkInTicket> {
        val result = apiCall {
            val response = api.cancel(ticket.id)
            response.ensureSuccess()
            response.data
        }

        return when (result) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                val now = clock.instant()
                val cancelled = result.data?.toDomain(
                    placeId = ticket.placeId,
                    placeName = ticket.placeName,
                    receivedAt = now,
                ) ?: ticket.copy(status = WalkInStatus.Cancelled, receivedAt = now)
                // `save`, а не `remove`: отменённый талон хранилище выбросит
                // само (он больше не живой), а вот если сервер неожиданно
                // ответит живым состоянием, отменить его повторно будет чем.
                store.save(cancelled)
                ApiResult.Success(cancelled)
            }
        }
    }

    override suspend fun activeTicket(placeId: String): WalkInTicket? = store.active(placeId)
}
