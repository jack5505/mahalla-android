package uz.mahalla.core.ui.state

import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure
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

    /** Ошибка вместе с ответом сервера: экран показывает его текст (issue #34). */
    data class Error(val failure: ApiFailure) : ScreenState<Nothing> {
        constructor(error: ApiError) : this(ApiFailure(error))

        val error: ApiError get() = failure.error
    }

    data class Content<out T>(val data: T) : ScreenState<T>
}

fun <T> ScreenState<T>.dataOrNull(): T? = (this as? ScreenState.Content)?.data

fun <T> ScreenState<T>.errorOrNull(): ApiError? = (this as? ScreenState.Error)?.error

fun <T> ScreenState<T>.failureOrNull(): ApiFailure? = (this as? ScreenState.Error)?.failure

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
        is ApiResult.Failure -> ScreenState.Error(failure)
    }

/** Список: пустой ответ — это [ScreenState.Empty], а не пустой контент. */
fun <T> ApiResult<List<T>>.toListScreenState(): ScreenState<List<T>> =
    toScreenState(isEmpty = List<T>::isEmpty)
