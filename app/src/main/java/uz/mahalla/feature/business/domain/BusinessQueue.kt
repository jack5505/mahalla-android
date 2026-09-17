package uz.mahalla.feature.business.domain

import uz.mahalla.feature.queue.domain.WalkInStatus
import java.time.Instant

/**
 * Талон в очереди **глазами заведения** (задача 12.2).
 *
 * Приезжает из `GET walkin/barber/dashboard?placeId=` — схема
 * `WalkInResponse`, та же, что видит клиент в своём талоне. Отличие в том, что
 * заведению видны имя человека и его комментарий, а позиция в очереди здесь не
 * устаревает: список перечитывается целиком на каждом действии, и «на какой
 * момент» — это момент последней загрузки экрана, а не момент записи (ср.
 * `WalkInTicket.receivedAt`, у клиента ручки чтения нет вовсе, issue #96).
 *
 * @param queuePosition место в очереди; `null` у `PENDING` — мастер ещё не
 * подтвердил запись, места у неё пока нет.
 */
data class QueueEntry(
    val id: String,
    val userName: String = "",
    val serviceName: String? = null,
    val status: WalkInStatus,
    val queuePosition: Int? = null,
    val estimatedWaitMinutes: Int? = null,
    val note: String? = null,
    val createdAt: Instant? = null,
)

/**
 * Что заведение может сделать с талоном.
 *
 * Ровно четыре ручки бэкенда: `PUT walkin/{id}/accept`, `/decline`, `/start`,
 * `/complete`. Пятой («перенести», «вернуть в очередь») у него нет, и
 * придумывать её нельзя — кнопка, которая ничего не вызывает, хуже её
 * отсутствия.
 */
enum class QueueAction {
    /** Принять запрос: человек встаёт в очередь. */
    Accept,

    /**
     * Отказать. Это же и «пропустить» из задачи 12.2: отдельной ручки для
     * неявки (`NO_SHOW`) бэкенд не даёт, а `decline` — единственный способ
     * убрать талон из очереди, не обслужив его.
     */
    Decline,

    /** Вызвать: человек садится в кресло (`IN_CHAIR`). */
    Start,

    /** Обслужен. */
    Complete,
}

/**
 * Какие действия доступны в каком состоянии талона (задача 12.2).
 *
 * Чистые функции, как `WalkInStatusFlow` у клиента: экран рисует кнопки, а
 * решает домен — иначе правила разошлись бы между списком и карточкой.
 */
object QueueActionRules {

    /**
     * Порядок в списке — порядок кнопок на экране: сначала то, что делают
     * чаще, отказ последним.
     *
     * [WalkInStatus.CounterOffered] действий не даёт: мяч на стороне
     * клиента — он ещё не ответил на предложенное время, и «вызвать» его
     * сейчас значило бы решить за него.
     *
     * [WalkInStatus.Unknown] — тоже пусто, и это главное отличие от
     * клиентского `canCancel`, где незнакомый статус разрешает отмену.
     * Клиенту нельзя запирать выход, а заведению нельзя предлагать действие,
     * последствий которого никто не знает: сервер откажет, а талон в списке
     * уже успеет мигнуть «обслужен».
     */
    fun available(status: WalkInStatus): List<QueueAction> = when (status) {
        WalkInStatus.Pending -> listOf(QueueAction.Accept, QueueAction.Decline)
        WalkInStatus.Accepted, WalkInStatus.Waiting ->
            listOf(QueueAction.Start, QueueAction.Decline)

        WalkInStatus.InChair -> listOf(QueueAction.Complete)

        WalkInStatus.CounterOffered, WalkInStatus.Declined, WalkInStatus.Completed,
        WalkInStatus.Cancelled, WalkInStatus.NoShow, WalkInStatus.Expired,
        WalkInStatus.Unknown,
        -> emptyList()
    }

    fun isAllowed(status: WalkInStatus, action: QueueAction): Boolean =
        action in available(status)

    /**
     * Кого вызывать следующим — тот, кто ближе всех к креслу.
     *
     * Пока кто-то в кресле ([WalkInStatus.InChair]), следующего нет: мастер
     * один, и «вызвать следующего» при занятом кресле — это два человека на
     * одно место. Экран в этот момент предлагает не «вызвать», а «завершить».
     *
     * Порядок — по [QueueEntry.queuePosition], а талоны без позиции идут в
     * хвост по времени создания: сортировать `null` как ноль значило бы
     * вызывать раньше всех того, о ком сервер позиции не сообщил.
     */
    fun nextInLine(entries: List<QueueEntry>): QueueEntry? {
        if (entries.any { it.status == WalkInStatus.InChair }) return null
        return entries
            .filter { isAllowed(it.status, QueueAction.Start) }
            .minWithOrNull(
                compareBy(
                    { it.queuePosition ?: Int.MAX_VALUE },
                    { it.createdAt ?: Instant.MAX },
                    { it.id },
                ),
            )
    }

    /** Талон уже не в игре — в «активной» части списка ему не место. */
    fun isFinished(status: WalkInStatus): Boolean = status == WalkInStatus.Completed ||
        status == WalkInStatus.Declined ||
        status == WalkInStatus.Cancelled ||
        status == WalkInStatus.NoShow ||
        status == WalkInStatus.Expired
}
