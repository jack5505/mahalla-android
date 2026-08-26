package uz.mahalla.feature.onboarding.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaTextField
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews

/**
 * Адрес бэкенда (issue #26): первый экран приложения, пока адрес не задан.
 *
 * Запросы уходят именно туда, что введено здесь, — адрес из сборки остаётся
 * только значением по умолчанию. Экран доступен и позже (кнопки на welcome и
 * в профиле): опечатка в хосте иначе запирала бы приложение навсегда.
 */
@Composable
fun BackendUrlScreen(
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    viewModel: BackendUrlViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                BackendUrlEffect.Saved -> onSaved()
            }
        }
    }

    BackendUrlContent(
        state = state,
        onEvent = viewModel::onEvent,
        modifier = modifier,
        onBack = onBack,
    )
}

@Composable
private fun BackendUrlContent(
    state: BackendUrlState,
    onEvent: (BackendUrlEvent) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    OnboardingStep(
        title = stringResource(R.string.backend_url_title),
        modifier = modifier,
        subtitle = stringResource(R.string.backend_url_subtitle),
        onBack = onBack,
        footer = {
            MahallaButton(
                text = if (state.error == BackendUrlError.UNREACHABLE) {
                    // Сервер не ответил, но пользователь может знать лучше:
                    // повторный тап сохраняет адрес как есть.
                    stringResource(R.string.backend_url_save_anyway)
                } else {
                    stringResource(R.string.backend_url_action)
                },
                onClick = { onEvent(BackendUrlEvent.Submit) },
                state = ButtonState(enabled = state.canSubmit, loading = state.checking),
            )
            MahallaButton(
                text = stringResource(R.string.backend_url_default),
                onClick = { onEvent(BackendUrlEvent.DefaultRequested) },
                variant = MahallaButtonVariant.Ghost,
                state = ButtonState(enabled = !state.checking),
            )
        },
    ) {
        MahallaTextField(
            value = state.url,
            onValueChange = { onEvent(BackendUrlEvent.UrlChanged(it)) },
            label = stringResource(R.string.backend_url_label),
            placeholder = stringResource(R.string.backend_url_placeholder),
            supportingText = stringResource(R.string.backend_url_hint),
            errorText = when (state.error) {
                BackendUrlError.INVALID -> stringResource(R.string.backend_url_invalid)
                BackendUrlError.CLEARTEXT_BLOCKED ->
                    stringResource(R.string.backend_url_cleartext_blocked)

                BackendUrlError.UNREACHABLE -> stringResource(R.string.backend_url_unreachable)
                null -> null
            },
            enabled = !state.checking,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
        )
    }
}

@ThemeLanguagePreviews
@Composable
private fun BackendUrlScreenPreview() {
    PreviewSurface {
        BackendUrlContent(
            state = BackendUrlState(
                url = "http://10.0.2.2:8080/api/v1/",
                defaultUrl = "http://10.0.2.2:8080/api/v1/",
            ),
            onEvent = {},
        )
    }
}
