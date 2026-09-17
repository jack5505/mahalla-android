package uz.mahalla.core.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

/** Иммутабельное состояние экрана. Один экран — один класс состояния. */
interface UiState

/** Намерение пользователя (или системы), приходящее из UI во ViewModel. */
interface UiEvent

/**
 * Одноразовый побочный эффект: навигация, snackbar, системный диалог.
 * Не часть состояния — иначе переигрывается при рекомпозиции/повороте.
 */
interface UiEffect

/**
 * База для всех ViewModel приложения (эпик 1.1).
 *
 * Эффекты складываются в буферизованный [Channel] и отдаются через
 * [effects]; [emitEffect] не suspend и не требует Main-диспетчера, поэтому
 * ViewModel остаётся тестируемой на чистом JVM без правил Instant/Main.
 */
abstract class MviViewModel<S : UiState, E : UiEvent, F : UiEffect>(
    initialState: S,
) : ViewModel() {

    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<S> = mutableState.asStateFlow()

    private val effectChannel = Channel<F>(capacity = Channel.BUFFERED)
    val effects: Flow<F> = effectChannel.receiveAsFlow()

    protected val currentState: S get() = mutableState.value

    /**
     * Экран уже был на переднем плане. Нужен, чтобы отличить **возврат** на
     * экран от его открытия — см. [onScreenResumed]. Живёт здесь, а не в
     * композабле: композабл пересоздаётся при каждом уходе с таба, а
     * ViewModel держится за запись бэкстека.
     */
    private var resumedOnce = false

    /** Единственная точка входа для UI. */
    abstract fun onEvent(event: E)

    protected fun updateState(reducer: S.() -> S) {
        mutableState.update { it.reducer() }
    }

    protected fun emitEffect(effect: F) {
        effectChannel.trySend(effect)
    }

    /**
     * Общая защита от дубля загрузки на `ScreenResumed` (issue #145, #209).
     *
     * **Первый resume пропускается.** `LifecycleEventEffect(ON_RESUME)`
     * срабатывает на первой же композиции, то есть сразу после того, как
     * экран запросил загрузку в `init` — это не возврат на экран, а его
     * открытие. Проверки `isLoading`/`isRefreshing` для этого мало: они
     * отсекают дубль только пока стартовая загрузка в полёте, а успела та
     * дойти до конца — экран открывался бы двумя одинаковыми загрузками.
     *
     * **Второй резюм подряд тоже не должен грузить заново.** Диалог поверх
     * экрана или быстрый уход в фон и обратно дают два resume, пока первый
     * (уже "возвратный") ответ ещё не пришёл. [isLoadInFlight] обязан
     * проверять именно job запущенной этим resume загрузки, а не
     * `isLoading`/`isRefreshing`: такая загрузка идёт молча и эти флаги не
     * поднимает.
     */
    protected fun onScreenResumed(isLoadInFlight: () -> Boolean = { false }, load: () -> Unit) {
        if (!resumedOnce) {
            resumedOnce = true
            return
        }
        if (isLoadInFlight()) return
        load()
    }
}
