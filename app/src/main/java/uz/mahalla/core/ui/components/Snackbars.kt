package uz.mahalla.core.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import uz.mahalla.core.ui.snackbar.SnackbarController
import uz.mahalla.core.ui.snackbar.SnackbarLength

@Composable
fun rememberSnackbarController(): SnackbarController = remember { SnackbarController() }

/**
 * Единый снекбар приложения (эпик 2.3): цвет по тону, действие — опционально.
 *
 * Тон хранится рядом с хостом, а не внутри `SnackbarData`: Material не умеет
 * переносить произвольные поля, а снекбары показываются строго по одному,
 * поэтому «текущий тон» однозначен.
 */
@Composable
fun MahallaSnackbarHost(
    controller: SnackbarController,
    modifier: Modifier = Modifier,
    hostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    var tone by remember { mutableStateOf(MahallaTone.Neutral) }
    val colors = tone.snackbarColors()

    LaunchedEffect(controller, hostState) {
        controller.messages.collect { message ->
            tone = message.tone
            val result = hostState.showSnackbar(
                message = message.text,
                actionLabel = message.actionLabel,
                withDismissAction = message.length == SnackbarLength.Indefinite,
                duration = message.length.toMaterialDuration(),
            )
            if (result == SnackbarResult.ActionPerformed) {
                message.onAction?.invoke()
            }
        }
    }

    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        Snackbar(
            snackbarData = data,
            // Полите-регион: TalkBack дочитает текущую фразу и озвучит снекбар,
            // не перебивая пользователя.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            shape = MaterialTheme.shapes.extraSmall,
            containerColor = colors.container,
            contentColor = colors.content,
            actionColor = colors.action,
        )
    }
}

/**
 * Цвета снекбара. Обычное сообщение — тёмная плашка из макета (инверсные роли
 * схемы), а не светлый `Neutral`: снекбар всплывает поверх светлого экрана, и
 * заливка `surfaceVariant` на нём почти не видна.
 *
 * Смысловые тоны (отказ, предупреждение) остаются своими: плашка «не удалось»
 * обязана отличаться от «готово» не только словами.
 */
@Composable
@ReadOnlyComposable
private fun MahallaTone.snackbarColors(): SnackbarColors {
    val scheme = MaterialTheme.colorScheme
    return if (this == MahallaTone.Neutral) {
        SnackbarColors(
            container = scheme.inverseSurface,
            content = scheme.inverseOnSurface,
            action = scheme.inversePrimary,
        )
    } else {
        val tone = colors()
        SnackbarColors(container = tone.container, content = tone.content, action = tone.content)
    }
}

@Immutable
private data class SnackbarColors(
    val container: Color,
    val content: Color,
    /** Подпись действия: в макете она акцентная, а не цвета текста. */
    val action: Color,
)

private fun SnackbarLength.toMaterialDuration(): SnackbarDuration = when (this) {
    SnackbarLength.Short -> SnackbarDuration.Short
    SnackbarLength.Long -> SnackbarDuration.Long
    SnackbarLength.Indefinite -> SnackbarDuration.Indefinite
}
