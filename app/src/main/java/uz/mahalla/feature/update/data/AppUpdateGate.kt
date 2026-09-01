package uz.mahalla.feature.update.data

import kotlinx.coroutines.withTimeoutOrNull
import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.update.domain.UpdateDecision
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Результат проверки версии на время жизни процесса (issue #80).
 *
 * Проверка идёт **один раз за запуск**, под держащимся splash'ем, рядом с
 * `BackendUrlStore.hydrate()` в `RootViewModel.resolveStart` — там уже собрана
 * вся стартовая логика, и это единственный момент, когда решение о
 * блокирующем экране ещё можно принять до того, как человек начал работать.
 *
 * Решение хранится здесь, а не в аргументах маршрута: на блокирующем экране
 * оно объёмное (имя версии, список изменений, ссылка), а `SavedStateHandle`
 * пережил бы смерть процесса — то есть экран мог бы всплыть с данными
 * проверки, которой в этом запуске не было.
 */
@Singleton
class AppUpdateGate @Inject constructor(
    private val repository: AppVersionRepository,
) {

    @Volatile
    private var decision: UpdateDecision = UpdateDecision.None

    @Volatile
    private var checked = false

    /** Что показывать сейчас. Читает экран обновления. */
    fun current(): UpdateDecision = decision

    /**
     * Сходить на бэкенд. Повторные вызовы отдают уже принятое решение: настройки
     * — живой flow, и `resolveStart` мог бы вызваться на каждой эмиссии.
     *
     * **Отказ проверки не запирает приложение**: и сеть, и таймаут, и любой код
     * ответа дают [UpdateDecision.None]. Упавший бэкенд не должен превращаться
     * в кирпич на всех устройствах сразу — а именно это и получилось бы, если
     * бы «не смогли проверить» показывало экран обновления.
     *
     * Отдельный бюджет времени обязателен: на клиенте стоят таймауты 15 сек на
     * соединение и 30 на чтение (`NetworkFactory`), то есть недоступный сервер
     * держал бы splash почти минуту — приложение выглядело бы зависшим при
     * старте. Три секунды хватает живому серверу и не заметны глазу.
     */
    suspend fun check(): UpdateDecision {
        if (checked) return decision
        checked = true
        val result = withTimeoutOrNull(CHECK_BUDGET_MILLIS) { repository.check() }
        decision = (result as? ApiResult.Success)?.data ?: UpdateDecision.None
        return decision
    }

    /**
     * «Позже» на мягком предложении.
     *
     * Пропуск сообщается серверу (он считает их пользователю и по исчерпании
     * переводит обновление в обязательное), но результат ни на что не влияет:
     * до входа `skip` отвечает `401` — пропуски привязаны к пользователю, а его
     * ещё нет. Держать человека на экране из-за служебного запроса нельзя,
     * поэтому бюджет короткий, а неудача просто игнорируется: в худшем случае
     * предложение повторится при следующем запуске.
     */
    suspend fun skip() {
        val versionId = (decision as? UpdateDecision.Suggested)?.update?.versionId
        dismiss()
        if (versionId == null) return
        withTimeoutOrNull(SKIP_BUDGET_MILLIS) { repository.skip(versionId) }
    }

    /**
     * Забыть решение до конца процесса: экран обновления уже отработал, и
     * возвращаться на него по «назад» или после смены конфигурации незачем.
     */
    fun dismiss() {
        decision = UpdateDecision.None
    }

    internal companion object {
        const val CHECK_BUDGET_MILLIS = 3_000L
        const val SKIP_BUDGET_MILLIS = 2_000L
    }
}
