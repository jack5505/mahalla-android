package uz.mahalla.feature.update.ui

import uz.mahalla.core.ui.UiEffect
import uz.mahalla.core.ui.UiEvent
import uz.mahalla.core.ui.UiState
import uz.mahalla.feature.update.domain.AppUpdate

/**
 * @param blocking обновление обязательно: «Позже» на экране нет, и уйти с него
 * можно только обновившись.
 * @param skipping идёт запрос пропуска. Кнопка «Позже» на это время занята —
 * второй тап завёл бы второй запрос и вторую навигацию.
 * @param storeFailed открыть магазин не удалось (нет ни одного приложения,
 * умеющего эту ссылку). Без сообщения тап по единственной кнопке экрана
 * выглядел бы как поломка.
 */
data class AppUpdateState(
    val update: AppUpdate? = null,
    val blocking: Boolean = false,
    val skipping: Boolean = false,
    val storeFailed: Boolean = false,
) : UiState

sealed interface AppUpdateEvent : UiEvent {
    data object UpdateRequested : AppUpdateEvent
    data object LaterRequested : AppUpdateEvent

    /** Магазин не открылся: на устройстве нет приложения для такой ссылки. */
    data object StoreOpenFailed : AppUpdateEvent
}

sealed interface AppUpdateEffect : UiEffect {
    data class OpenStore(val url: String) : AppUpdateEffect

    /** Экран отработал — дальше начинается приложение. */
    data object Continue : AppUpdateEffect
}
