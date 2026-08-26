package uz.mahalla.feature.map.data

import kotlin.coroutines.cancellation.CancellationException

/**
 * `runCatching` для вызовов MapKit (эпик 4.2).
 *
 * Отдельно от `runCatchingCancellable`, потому что MapKit нарушает обычное для
 * JVM-библиотек соглашение «ошибки — это `Exception`»:
 *
 * - контракт SDK (не тот поток, второй `initialize`, пустая локаль) сообщается
 *   через `java.lang.AssertionError`;
 * - на устройстве без подходящей ABI `libmaps-mobile.so` не загружается, и
 *   первый же вызов кидает `UnsatisfiedLinkError`.
 *
 * И то, и другое — `Error`, а не `Exception`, то есть мимо обычного `catch`. Для
 * экрана карты это ровно та ситуация, ради которой есть [MapEngineState.Failed]:
 * показать объяснение с «Повторить», а не уронить приложение.
 *
 * Ловятся только эти два семейства: `OutOfMemoryError` и `StackOverflowError`
 * глотать нельзя — после них процесс всё равно нерабочий.
 */
internal inline fun <T> runCatchingMapKit(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (error: Exception) {
    Result.failure(error)
} catch (error: LinkageError) {
    Result.failure(error)
} catch (error: AssertionError) {
    Result.failure(error)
}
