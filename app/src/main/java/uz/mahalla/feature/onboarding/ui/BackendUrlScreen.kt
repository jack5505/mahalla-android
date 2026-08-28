package uz.mahalla.feature.onboarding.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import uz.mahalla.data.network.tls.ServerCertificate
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing

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
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                BackendUrlEffect.Saved -> onSaved()
                is BackendUrlEffect.OpenHttpInspector -> context.startActivity(effect.intent)
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
            // Доверие первым: когда сертификату нет доверия, это единственный
            // путь дальше — сохранённый адрес всё равно не заработает.
            if (state.certificate != null) {
                MahallaButton(
                    text = stringResource(R.string.backend_url_certificate_trust),
                    onClick = { onEvent(BackendUrlEvent.TrustCertificateRequested) },
                    state = ButtonState(enabled = state.canTrustCertificate),
                )
            }
            MahallaButton(
                text = if (state.checked && state.error == BackendUrlError.UNREACHABLE) {
                    // Сервер не ответил, но пользователь может знать лучше:
                    // повторный тап сохраняет адрес как есть.
                    stringResource(R.string.backend_url_save_anyway)
                } else {
                    stringResource(R.string.backend_url_action)
                },
                onClick = { onEvent(BackendUrlEvent.Submit) },
                state = ButtonState(enabled = state.canSubmit, loading = state.checking),
                variant = if (state.certificate != null) {
                    MahallaButtonVariant.Secondary
                } else {
                    MahallaButtonVariant.Primary
                },
            )
            MahallaButton(
                text = stringResource(R.string.backend_url_default),
                onClick = { onEvent(BackendUrlEvent.DefaultRequested) },
                variant = MahallaButtonVariant.Ghost,
                state = ButtonState(enabled = !state.checking),
            )
            if (state.httpInspectorAvailable) {
                MahallaButton(
                    text = stringResource(R.string.http_inspector_open),
                    onClick = { onEvent(BackendUrlEvent.HttpInspectorRequested) },
                    variant = MahallaButtonVariant.Ghost,
                )
            }
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
                BackendUrlError.CERTIFICATE_UNTRUSTED ->
                    stringResource(R.string.backend_url_certificate_untrusted)

                null -> null
            },
            enabled = !state.checking,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
        )
        state.certificate?.let { certificate -> CertificateDetails(certificate) }
    }
}

/**
 * Что за сертификат предлагают принять (issue #32).
 *
 * Отпечаток показывается целиком и в том же формате, что отдаёт
 * `openssl x509 -noout -fingerprint -sha256`: подтверждение доверия имеет смысл
 * только тогда, когда строку можно сверить с сервером глазами. Перенос по
 * словам — иначе 95 символов не помещаются в 393 dp.
 */
@Composable
private fun CertificateDetails(certificate: ServerCertificate, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.item / 2),
    ) {
        Text(
            text = stringResource(R.string.backend_url_certificate_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalMahallaColors.current.fgMuted,
        )
        Text(
            text = stringResource(
                R.string.backend_url_certificate_subject,
                certificate.subject,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = LocalMahallaColors.current.fgMuted,
        )
        Text(
            text = stringResource(
                R.string.backend_url_certificate_issuer,
                certificate.issuer,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = LocalMahallaColors.current.fgMuted,
        )
        Text(
            text = stringResource(
                R.string.backend_url_certificate_fingerprint,
                certificate.sha256,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@ThemeLanguagePreviews
@Composable
private fun BackendUrlScreenPreview() {
    PreviewSurface {
        BackendUrlContent(
            state = BackendUrlState(
                url = "https://189-74-96-232.nip.io/api/v1/",
                defaultUrl = "https://189-74-96-232.nip.io/api/v1/",
            ),
            onEvent = {},
        )
    }
}

/** Самоподписанный сертификат стенда (issue #32): самый длинный текст экрана. */
@ThemeLanguagePreviews
@Composable
private fun BackendUrlCertificatePreview() {
    PreviewSurface {
        BackendUrlContent(
            state = BackendUrlState(
                url = "https://189.74.96.232/",
                defaultUrl = "https://api.mahalla.uz/api/v1/",
                checked = true,
                error = BackendUrlError.CERTIFICATE_UNTRUSTED,
                certificate = ServerCertificate(
                    sha256 = "3A:1F:9C:04:BE:77:12:E5:8D:60:AA:31:4C:D9:02:6B:" +
                        "F8:55:17:E0:9A:24:73:CB:10:8E:42:FD:66:B3:07:91",
                    subject = "CN=189.74.96.232",
                    issuer = "CN=189.74.96.232",
                ),
            ),
            onEvent = {},
        )
    }
}
