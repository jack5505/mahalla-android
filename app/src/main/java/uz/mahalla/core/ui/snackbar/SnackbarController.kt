package uz.mahalla.core.ui.snackbar

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import uz.mahalla.core.ui.components.MahallaTone

/** Как долго висит снекбар — своя модель, чтобы контроллер не зависел от Material. */
enum class SnackbarLength { Short, Long, Indefinite }

@Immutable
data class SnackbarMessage(
    val text: String,
    val actionLabel: String? = null,
    val tone: MahallaTone = MahallaTone.Neutral,
    val length: SnackbarLength = SnackbarLength.Short,
    val onAction: (() -> Unit)? = null,
)

/**
 * Единая точка показа снекбаров (эпик 2.3).
 *
 * Канал, а не `StateFlow`: сообщение — событие, его нельзя терять при
 * пересоздании экрана и нельзя показывать повторно после поворота.
 * `Channel.BUFFERED` держит очередь, пока экран не подписан.
 */
class SnackbarController {

    private val channel = Channel<SnackbarMessage>(Channel.BUFFERED)

    val messages: Flow<SnackbarMessage> = channel.receiveAsFlow()

    suspend fun show(message: SnackbarMessage) {
        channel.send(message)
    }

    suspend fun show(
        text: String,
        tone: MahallaTone = MahallaTone.Neutral,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
    ) = show(SnackbarMessage(text = text, tone = tone, actionLabel = actionLabel, onAction = onAction))

    /** Показ из не-suspend кода (обработчик клика): очередь переполнена — сообщение теряем. */
    fun tryShow(message: SnackbarMessage): Boolean = channel.trySend(message).isSuccess
}
