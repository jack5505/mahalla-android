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

    /** Единственная точка входа для UI. */
    abstract fun onEvent(event: E)

    protected fun updateState(reducer: S.() -> S) {
        mutableState.update { it.reducer() }
    }

    protected fun emitEffect(effect: F) {
        effectChannel.trySend(effect)
    }
}
