package uz.mahalla.feature.map.canvas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import kotlinx.coroutines.launch
import uz.mahalla.R
import uz.mahalla.core.ui.components.ErrorState
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaTextField
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.feature.map.data.MapEngineState
import uz.mahalla.feature.map.data.MapKitKeyStore
import uz.mahalla.ui.theme.Spacing

/**
 * Почему карты нет и что с этим делать.
 *
 * Текст разный: «сборка без ключа» и провал инициализации — разные беды.
 * Кнопка «Повторить» стоит в обоих случаях: движок мог не подняться и по
 * причине, о которой SDK не сказал, и второй шанс дешевле, чем перезапуск
 * приложения.
 *
 * [keyStore] — ключ MapKit, который можно ввести прямо здесь (issue #129).
 * Кнопка ввода появляется только когда карты нет **именно** из-за ключа и
 * сборка разрешает его менять: в магазинной сборке ключ приезжает релизом, и
 * поле ввода там предлагало бы пользователю чинить чужую сборку. `null` —
 * экран ввода не предлагает (превью, тесты).
 */
@Composable
fun MapUnavailable(
    engine: MapEngine,
    modifier: Modifier = Modifier,
    keyStore: MapKitKeyStore? = null,
) {
    var keyDialogVisible by remember { mutableStateOf(false) }
    val canEnterKey = keyStore != null &&
        keyStore.canEdit &&
        engine.state == MapEngineState.MissingApiKey

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        ErrorState(
            onRetry = engine.retry,
            modifier = Modifier.fillMaxWidth(),
            title = stringResource(R.string.map_unavailable_title),
            description = stringResource(
                if (engine.state == MapEngineState.MissingApiKey) {
                    R.string.map_missing_key_description
                } else {
                    R.string.map_engine_failed_description
                },
            ),
        )

        if (canEnterKey) {
            MahallaButton(
                text = stringResource(R.string.map_key_action_enter),
                onClick = { keyDialogVisible = true },
                variant = MahallaButtonVariant.Ghost,
                fillWidth = false,
            )
        }
    }

    if (keyDialogVisible && keyStore != null) {
        MapApiKeyDialog(
            keyStore = keyStore,
            // Закрытие шторки — всегда попытка поднять движок заново: ключ уже
            // действует, а движок его с прошлой попытки не спрашивал, и без
            // повтора экран остался бы с прежним текстом. Отмена без ввода
            // стоит одной безуспешной попытки — это дешевле разбора случаев.
            onClose = {
                keyDialogVisible = false
                engine.retry()
            },
        )
    }
}

/**
 * Ввод ключа MapKit (issue #129).
 *
 * Поле предзаполняется тем, что уже вводили, а не ключом сборки: показать
 * чужой ключ как свой значит спутать источник — а именно источник здесь и
 * выясняют. Пустое поле — «убрать свой ключ», карта возвращается к ключу
 * сборки; поэтому кнопка сохранения активна и при пустом поле.
 */
@Composable
private fun MapApiKeyDialog(
    keyStore: MapKitKeyStore,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var saveFailed by remember { mutableStateOf(false) }

    LaunchedEffect(keyStore) {
        key = keyStore.saved().orEmpty()
    }

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            MahallaButton(
                text = stringResource(R.string.action_save),
                onClick = {
                    scope.launch {
                        // Ключ действует и когда запись не удалась (он уже в
                        // памяти), поэтому провал хранилища не отменяет ввод —
                        // о нём просто говорят словами, а шторка остаётся
                        // открытой, чтобы это было видно.
                        if (keyStore.save(key)) onClose() else saveFailed = true
                    }
                },
                fillWidth = false,
            )
        },
        dismissButton = {
            MahallaButton(
                text = stringResource(R.string.action_close),
                onClick = onClose,
                variant = MahallaButtonVariant.Ghost,
                fillWidth = false,
            )
        },
        title = { Text(text = stringResource(R.string.map_key_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
                Text(
                    text = stringResource(R.string.map_key_dialog_description),
                    style = MaterialTheme.typography.bodyMedium,
                )
                MahallaTextField(
                    value = key,
                    onValueChange = {
                        key = it
                        saveFailed = false
                    },
                    label = stringResource(R.string.map_key_field_label),
                    supportingText = stringResource(R.string.map_key_field_hint),
                    errorText = stringResource(R.string.map_key_save_failed)
                        .takeIf { saveFailed },
                    // Ключ MapKit — набор из букв и дефисов, и автозаглавная
                    // первая буква ломала бы его молча.
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                    ),
                    onClear = { key = "" },
                )
            }
        },
    )
}

@ThemeLanguagePreviews
@Composable
private fun MapUnavailablePreview() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
            MapUnavailable(engine = MapEngine(state = MapEngineState.MissingApiKey, retry = {}))
            MapUnavailable(engine = MapEngine(state = MapEngineState.Failed, retry = {}))
        }
    }
}
