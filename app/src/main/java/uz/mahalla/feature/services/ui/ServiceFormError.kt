package uz.mahalla.feature.services.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.ui.components.MahallaErrorDetails
import uz.mahalla.core.ui.userMessage
import uz.mahalla.ui.theme.Spacing

/**
 * Отказ сервера под формой: его собственный текст плюс раскрываемые
 * подробности ответа (issue #34).
 *
 * Не [uz.mahalla.core.ui.components.ApiErrorState]: тот заменяет собой весь
 * экран и несёт собственную прокрутку, а здесь форма обязана остаться на
 * месте — заполненные поля после отказа теряться не должны.
 */
@Composable
fun ServiceFormError(failure: ApiFailure, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        Text(
            text = failure.userMessage(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        failure.server?.let { MahallaErrorDetails(server = it) }
    }
}
