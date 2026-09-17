package uz.mahalla.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import uz.mahalla.R
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.text.OtpFieldState
import uz.mahalla.ui.theme.FocusPinKey
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums

/**
 * Набранные цифры PIN — точками (макет 0e).
 *
 * Точки, а не ячейки с маской: PIN набирают в людном месте, и четыре
 * одинаковых кружка не дают прочитать через плечо даже длину введённого
 * куска по ширине символов.
 *
 * Семантика собрана на всём ряду: TalkBack читает «PIN из 4 цифр, введено 2»,
 * а не четыре безымянных кружка подряд.
 */
@Composable
fun MahallaPinDots(
    state: OtpFieldState,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    val description = stringResource(R.string.pin_input_description, state.length)
    val progress = pluralStringResource(
        R.plurals.otp_input_progress,
        state.length,
        state.filledCount,
        state.length,
    )
    val color = if (isError || state.isError) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = description
                stateDescription = progress
            },
        horizontalArrangement = Arrangement.spacedBy(Spacing.item, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.cells().forEach { digit ->
            Box(
                modifier = Modifier
                    .size(DotSize)
                    .border(DotBorderWidth, color, CircleShape)
                    .background(if (digit == null) Color.Transparent else color, CircleShape)
                    .clearAndSetSemantics {},
            )
        }
    }
}

/**
 * Нампад PIN (макет 0e): три колонки, 1–9, ноль и стирание.
 *
 * Свой нампад, а не системная клавиатура: PIN — единственное место, где
 * цифровая клавиатура занимает пол-экрана ради четырёх нажатий, а её
 * `NumberPassword`-раскладка на разных прошивках выглядит по-разному.
 * Автоподстановки здесь терять нечего — в отличие от кода из SMS, PIN
 * приходить извне не может.
 *
 * Клавиша 60dp — больше цели нажатия (48dp), поэтому отдельного запаса не
 * добавляем.
 */
@Composable
fun MahallaPinPad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val backspaceLabel = stringResource(R.string.pin_pad_backspace)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        PadRows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.item, Alignment.CenterHorizontally),
            ) {
                row.forEach { key ->
                    when (key) {
                        // Пустая ячейка слева от нуля — место держит, нажатий
                        // не принимает и для TalkBack не существует.
                        null -> Box(
                            modifier = Modifier
                                .size(MahallaComponentDefaults.pinPadKeySize)
                                .clearAndSetSemantics {},
                        )

                        BACKSPACE -> PadKey(
                            onClick = onBackspace,
                            enabled = enabled,
                            contentDescription = backspaceLabel,
                            filled = false,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Backspace,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        else -> PadKey(
                            onClick = { onDigit(key) },
                            enabled = enabled,
                            contentDescription = key.toString(),
                            filled = true,
                        ) {
                            Text(
                                text = key.toString(),
                                style = FocusPinKey.merge(TabularNums),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PadKey(
    onClick: () -> Unit,
    enabled: Boolean,
    contentDescription: String,
    filled: Boolean,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(MahallaComponentDefaults.pinPadKeySize)
            .background(
                if (filled) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                MaterialTheme.shapes.medium,
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

internal const val BACKSPACE = ''

/**
 * Раскладка нампада: три колонки, снизу пусто — ноль — стирание.
 *
 * `internal`, а не `private`, ради `PinPadTest`: опечатка в раскладке (две
 * пятёрки, потерянная семёрка) из кода не видна, а на экране это PIN, который
 * невозможно набрать.
 */
internal val PadRows: List<List<Char?>> = listOf(
    listOf('1', '2', '3'),
    listOf('4', '5', '6'),
    listOf('7', '8', '9'),
    listOf(null, '0', BACKSPACE),
)

private val DotSize = 16.dp
private val DotBorderWidth = 2.dp

@ThemeLanguagePreviews
@Composable
private fun MahallaPinPadPreview() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.gap)) {
            MahallaPinDots(state = OtpFieldState(code = "12", length = 4))
            MahallaPinPad(onDigit = {}, onBackspace = {})
        }
    }
}
