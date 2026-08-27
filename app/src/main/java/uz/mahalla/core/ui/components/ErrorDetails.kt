package uz.mahalla.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import uz.mahalla.R
import uz.mahalla.core.result.ServerError
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.ui.theme.Spacing

/**
 * Раскрываемые подробности ответа сервера (issue #34).
 *
 * До этого единственным способом узнать, что ответил бэкенд, был инспектор
 * трафика в debug-сборке: пользователь видел «что-то пошло не так», а причина
 * («включите геолокацию») лежала в теле ответа. Теперь тело доступно на самом
 * экране — и его можно скопировать целиком в сообщение поддержке.
 *
 * Свёрнуто по умолчанию: сообщение бэкенда показывается выше обычным текстом,
 * а HTTP-код и JSON нужны, только когда сообщения не хватило.
 */
@Composable
fun MahallaErrorDetails(server: ServerError, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    val status = stringResource(
        R.string.error_details_status,
        listOfNotNull(server.httpCode.toString(), server.httpMessage).joinToString(" "),
    )
    val code = server.code?.let { stringResource(R.string.error_details_code, it) }
    val request = server.requestLine?.let { stringResource(R.string.error_details_request, it) }
    val responseLabel = stringResource(R.string.error_details_response)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        MahallaButton(
            text = stringResource(
                if (expanded) R.string.error_details_hide else R.string.error_details_show,
            ),
            onClick = { expanded = !expanded },
            variant = MahallaButtonVariant.Ghost,
            fillWidth = false,
        )
        if (expanded) {
            val tone = MahallaTone.Neutral.colors()
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = tone.container,
                contentColor = tone.content,
            ) {
                Column(
                    modifier = Modifier.padding(Spacing.gap),
                    verticalArrangement = Arrangement.spacedBy(Spacing.item),
                ) {
                    Text(text = status, style = MaterialTheme.typography.bodySmall)
                    if (code != null) {
                        Text(text = code, style = MaterialTheme.typography.bodySmall)
                    }
                    if (request != null) {
                        Text(text = request, style = MaterialTheme.typography.bodySmall)
                    }
                    if (!server.body.isNullOrBlank()) {
                        Text(text = responseLabel, style = MaterialTheme.typography.labelLarge)
                        // Выделение руками — чтобы можно было забрать один
                        // код ошибки, не копируя весь ответ.
                        SelectionContainer {
                            Text(
                                text = server.body,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                    MahallaButton(
                        text = stringResource(R.string.error_details_copy),
                        onClick = {
                            clipboard.setText(
                                AnnotatedString(
                                    listOfNotNull(status, code, request, server.body)
                                        .joinToString(separator = "\n"),
                                ),
                            )
                        },
                        variant = MahallaButtonVariant.Ghost,
                        icon = Icons.Outlined.ContentCopy,
                        fillWidth = false,
                    )
                }
            }
        }
    }
}

@ThemeLanguagePreviews
@Composable
private fun MahallaErrorDetailsPreview() {
    PreviewSurface {
        MahallaErrorDetails(
            server = ServerError(
                httpCode = 403,
                httpMessage = "Forbidden",
                code = "GEO_PERMISSION_REQUIRED",
                message = "Joylashuv ruxsatini yoqing",
                requestLine = "POST https://api.mahalla.uz/auth/otp/request",
                body = "{\n    \"success\": false,\n    \"error\": {\n" +
                    "        \"code\": \"GEO_PERMISSION_REQUIRED\"\n    }\n}",
            ),
        )
    }
}
