package uz.mahalla.feature.security.domain

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uz.mahalla.core.crash.reportSwallowed
import uz.mahalla.core.result.runCatchingCancellable
import uz.mahalla.data.prefs.SessionStore
import uz.mahalla.data.security.PinStorage
import java.time.Clock
import java.time.Duration

/**
 * Замок приложения (issue #102): возврат из фона требует PIN или биометрии.
 *
 * До этой задачи флаги `pinConfigured` и `biometricEnabled` писались, но
 * никем не спрашивались — открытый пункт с эпика 3.
 *
 * **Замок вооружается только когда его есть чем открыть**: нужна сессия и
 * локальная копия PIN. Локальная копия — потому что экран блокировки,
 * которому нужна сеть, превращает приложение в кирпич в метро; это ровно тот
 * риск, о котором предупреждает issue. Копия не расходится с сервером по
 * построению: её пишет только код, который бэкенд принял (см.
 * `SecurityRepository`).
 *
 * Синглтон, а не состояние экрана: пережить смену Activity (тема, язык) и
 * уход в фон обязан именно он.
 */
@Singleton
class AppLockManager @Inject constructor(
    private val sessionStore: SessionStore,
    private val pinStorage: PinStorage,
    private val clock: Clock,
) {

    private val _locked = MutableStateFlow(false)

    /** Показывать ли поверх приложения экран блокировки. */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /**
     * Когда приложение ушло в фон. `null` — оно на переднем плане либо фон
     * уже учтён.
     */
    private var backgroundedAt: Long? = null

    /**
     * Пересчёт «вооружён ли замок» ходит в DataStore и Keystore, а звать его
     * могут и жизненный цикл процесса, и экран разблокировки. Два
     * одновременных пересчёта дали бы гонку на [_locked].
     */
    private val mutex = Mutex()

    /**
     * Приложение ушло в фон. Само по себе это ещё не блокировка: замок
     * защёлкивается на возврате, и только если фон продлился дольше [GRACE].
     */
    fun onBackground() {
        backgroundedAt = clock.instant().toEpochMilli()
    }

    /**
     * Приложение вернулось на передний план.
     *
     * Отсрочка [GRACE] обязательна, иначе замок срабатывал бы на каждом
     * коротком выходе, которого требует само приложение: код из SMS
     * копируют в приложении сообщений, вход через Telegram уходит в Telegram
     * (issue #46), пополнение кошелька — в браузер провайдера (issue #93).
     * Спрашивать PIN на возврате из этих трёх сценариев — не безопасность, а
     * способ не дать человеку закончить дело.
     */
    suspend fun onForeground() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        if (_locked.value) return
        val away = Duration.ofMillis(clock.instant().toEpochMilli() - since)
        // Часы устройства могли перевести назад: отрицательный интервал — не
        // «вернулись мгновенно», а «измерить не удалось». Запираем.
        if (!away.isNegative && away < GRACE) return
        if (isArmed()) _locked.value = true
    }

    /**
     * Замок открыт: код подтверждён PIN'ом или биометрией.
     *
     * Вызывать только после настоящей проверки — метод ничего не проверяет
     * сам, он лишь снимает оверлей.
     */
    fun unlock() {
        backgroundedAt = null
        _locked.value = false
    }

    /**
     * Запереть немедленно, не дожидаясь фона: так поступает экран
     * разблокировки, когда сервер сообщил, что сессия ещё жива, но PIN всё
     * равно нужен (`session/check`).
     */
    suspend fun lockNow() {
        if (isArmed()) _locked.value = true
    }

    /**
     * Сессии больше нет (выход, повторный вход): замок снимается и больше не
     * вооружается. Оставить его защёлкнутым значило бы показать экран
     * блокировки поверх онбординга, где вводить нечего.
     */
    fun disarm() {
        backgroundedAt = null
        _locked.value = false
    }

    /**
     * Есть ли что запирать и чем открывать.
     *
     * Отказ хранилища читается как «не вооружён»: замок, который защёлкнулся
     * из-за недоступного Keystore, открыть будет нечем — это и есть
     * запирание пользователя вне приложения.
     */
    suspend fun isArmed(): Boolean = mutex.withLock {
        runCatchingCancellable {
            sessionStore.current() != null && pinStorage.isConfigured()
        }
            .reportSwallowed("applock.isArmed")
            .getOrDefault(false)
    }

    companion object {
        /**
         * Сколько приложение может пробыть в фоне, не запираясь.
         *
         * Тридцать секунд — обычная величина для банковских приложений: их
         * хватает на «скопировать код и вернуться» и не хватает на то, чтобы
         * чужой человек успел взять телефон со стола и что-то сделать.
         */
        val GRACE: Duration = Duration.ofSeconds(30)
    }
}
