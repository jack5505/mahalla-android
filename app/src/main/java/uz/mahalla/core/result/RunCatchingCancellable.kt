package uz.mahalla.core.result

import kotlin.coroutines.cancellation.CancellationException

/**
 * `runCatching` для корутин.
 *
 * Обычный `runCatching` ловит и [CancellationException] — то есть проглатывает
 * отмену корутины и продолжает выполнять её тело. Здесь отмена пробрасывается
 * дальше, а ловятся только настоящие ошибки.
 *
 * Нужен там, где падение записи не должно ронять приложение: Keystore
 * ([uz.mahalla.data.security.PinStorage]) кидает `KeyStoreException` и
 * `KeyPermanentlyInvalidatedException`, DataStore — `IOException` при
 * нехватке места. Чтения уже прикрыты (`ReplaceFileCorruptionHandler` и
 * `.catch {}` в эпике 1), записи — нет.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (error: Exception) {
    Result.failure(error)
}
