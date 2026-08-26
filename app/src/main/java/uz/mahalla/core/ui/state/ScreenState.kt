package uz.mahalla.core.ui.state

import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult

/**
 * Состояние экрана (эпик 2.3). Четыре состояния вместо связки
 * `isLoading`/`error`/`data`: комбинации вроде «грузится и одновременно
 * ошибка» просто невыразимы, поэтому экран не может показать два блока сразу.
 *
 * Pull-to-refresh намеренно живёт отдельным флагом (`isRefreshing`): обновление
 * поверх уже показанных данных — не [Loading], скелетон при нём не нужен.
 */
sealed interface ScreenState<out T> {
    data object Loading : ScreenState<Nothing>
    data object Empty : ScreenState<Nothing>
    data class Error(val error: ApiError) : ScreenState<Nothing>
    data class Content<out T>(val data: T) : ScreenState<T>
}

fun <T> ScreenState<T>.dataOrNull(): T? = (this as? ScreenState.Content)?.data

fun <T> ScreenState<T>.errorOrNull(): ApiError? = (this as? ScreenState.Error)?.error

val ScreenState<*>.isLoading: Boolean get() = this is ScreenState.Loading

inline fun <T, R> ScreenState<T>.map(transform: (T) -> R): ScreenState<R> = when (this) {
    is ScreenState.Content -> ScreenState.Content(transform(data))
    is ScreenState.Loading -> ScreenState.Loading
    is ScreenState.Empty -> ScreenState.Empty
    is ScreenState.Error -> this
}

/**
 * Ответ API → состояние экрана. [isEmpty] решает, считать ли успешный ответ
 * пустым: у списка это `isEmpty()`, у карточки — обычно ничего.
 */
inline fun <T> ApiResult<T>.toScreenState(isEmpty: (T) -> Boolean = { false }): ScreenState<T> =
    when (this) {
        is ApiResult.Success -> if (isEmpty(data)) ScreenState.Empty else ScreenState.Content(data)
        is ApiResult.Failure -> ScreenState.Error(error)
    }

/** Список: пустой ответ — это [ScreenState.Empty], а не пустой контент. */
fun <T> ApiResult<List<T>>.toListScreenState(): ScreenState<List<T>> =
    toScreenState(isEmpty = List<T>::isEmpty)
