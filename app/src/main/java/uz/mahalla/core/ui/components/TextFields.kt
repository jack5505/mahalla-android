package uz.mahalla.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextRange
import uz.mahalla.R
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.text.OtpFieldState
import uz.mahalla.core.ui.text.PhoneFieldFormatter
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums

/**
 * Текстовое поле кита: подпись, подсказка, ошибка под полем.
 *
 * Ошибку показываем текстом, а не только красной рамкой: цвет — не
 * единственный носитель смысла (2.4).
 */
@Composable
fun MahallaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    errorText: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onClear: (() -> Unit)? = null,
) {
    val clearLabel = stringResource(R.string.action_clear)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.item / 2)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = MahallaComponentDefaults.fieldMinHeight),
            enabled = enabled,
            singleLine = singleLine,
            isError = errorText != null,
            label = { Text(label) },
            placeholder = if (placeholder != null) {
                { Text(placeholder) }
            } else {
                null
            },
            trailingIcon = if (onClear != null && value.isNotEmpty()) {
                {
                    MahallaIconButton(
                        icon = Icons.Outlined.Close,
                        contentDescription = clearLabel,
                        onClick = onClear,
                    )
                }
            } else {
                null
            },
            keyboardOptions = keyboardOptions,
            shape = MaterialTheme.shapes.small,
        )
        FieldSupportingText(supportingText = supportingText, errorText = errorText)
    }
}

/**
 * Телефон Узбекистана. `+998` — неизменяемый префикс, редактируются только
 * девять национальных цифр; каретка удерживается [PhoneFieldFormatter], иначе
 * при вводе пробела курсор улетает в конец.
 */
@Composable
fun MahallaPhoneField(
    digits: String,
    onDigitsChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.onboarding_phone_label),
    errorText: String? = null,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Done,
) {
    val formatted = PhoneFieldFormatter.format(digits)
    var fieldValue by remember { mutableStateOf(TextFieldValue(formatted, TextRange(formatted.length))) }
    // Состояние-источник живёт во ViewModel; если оно изменилось снаружи
    // (очистка, восстановление после смерти процесса) — подтягиваем текст.
    if (fieldValue.text != formatted) {
        fieldValue = TextFieldValue(formatted, TextRange(formatted.length))
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.item / 2)) {
        OutlinedTextField(
            value = fieldValue,
            onValueChange = { input ->
                val masked = PhoneFieldFormatter.apply(input.text, input.selection.end)
                fieldValue = TextFieldValue(masked.text, TextRange(masked.caret))
                onDigitsChange(PhoneFieldFormatter.digitsOf(masked.text))
            },
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = MahallaComponentDefaults.fieldMinHeight),
            enabled = enabled,
            singleLine = true,
            isError = errorText != null,
            label = { Text(label) },
            prefix = { Text(text = "+$COUNTRY_CODE", style = MaterialTheme.typography.titleMedium) },
            textStyle = MaterialTheme.typography.titleMedium.merge(TabularNums),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Phone,
                imeAction = imeAction,
            ),
            shape = MaterialTheme.shapes.small,
        )
        FieldSupportingText(supportingText = null, errorText = errorText)
    }
}

/**
 * Поле OTP: одно скрытое поле ввода + нарисованные ячейки. Так работают
 * автоподстановка кода из SMS и вставка «123456» целиком — набор из шести
 * независимых полей это ломает.
 *
 * @param masked ячейки показывают точки вместо цифр — режим ввода PIN (3.4).
 * @param focusRequester вешается на само поле ввода, а не на контейнер:
 * автофокус на экране кода должен открывать клавиатуру (3.3).
 */
@Composable
fun MahallaOtpField(
    state: OtpFieldState,
    onCodeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    errorText: String? = null,
    masked: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    val description = if (masked) {
        stringResource(R.string.pin_input_description, state.length)
    } else {
        stringResource(R.string.otp_input_description, state.length)
    }
    // Форма согласуется с длиной кода («введено 1 из 6 цифр»), поэтому
    // quantity — state.length, а оба числа уходят аргументами формата.
    val progress = pluralStringResource(
        R.plurals.otp_input_progress,
        state.length,
        state.filledCount,
        state.length,
    )
    val colors = MaterialTheme.colorScheme
    val mahalla = LocalMahallaColors.current

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.item / 2)) {
        BasicTextField(
            value = state.code,
            onValueChange = { onCodeChange(it.filter(Char::isDigit).take(state.length)) },
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (focusRequester != null) {
                        Modifier.focusRequester(focusRequester)
                    } else {
                        Modifier
                    },
                )
                .semantics {
                    contentDescription = description
                    stateDescription = progress
                },
            enabled = enabled,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
            ),
            singleLine = true,
            textStyle = TextStyle.Default,
            cursorBrush = SolidColor(colors.primary),
            decorationBox = { innerTextField ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    // Само поле остаётся в дереве, но невидимо: в нём живут
                    // ввод, курсор и автоподстановка кода из SMS. Видимые
                    // ячейки — только отрисовка состояния.
                    Box(modifier = Modifier.alpha(0f)) { innerTextField() }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.item),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        state.cells().forEachIndexed { index, digit ->
                            val focused = enabled && state.focusedIndex == index
                            val borderColor = when {
                                state.isError || errorText != null -> colors.error
                                focused -> colors.primary
                                else -> mahalla.outlineSoft
                            }
                            Box(
                                modifier = Modifier
                                    .width(MahallaComponentDefaults.otpCellWidth)
                                    .height(MahallaComponentDefaults.otpCellHeight)
                                    .background(colors.surfaceVariant, MaterialTheme.shapes.small)
                                    .border(
                                        MahallaComponentDefaults.borderWidth,
                                        borderColor,
                                        MaterialTheme.shapes.small,
                                    )
                                    // Ячейки — отрисовка одного поля, TalkBack не
                                    // должен читать их по отдельности.
                                    .clearAndSetSemantics {},
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = when {
                                        digit == null -> ""
                                        masked -> MASK_CHARACTER
                                        else -> digit.toString()
                                    },
                                    style = MaterialTheme.typography.titleMedium.merge(TabularNums),
                                    color = colors.onSurface,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            },
        )
        FieldSupportingText(supportingText = null, errorText = errorText)
    }
}

/** Поиск по каталогу: иконка слева, крестик очистки справа. */
@Composable
fun MahallaSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.search_hint),
    enabled: Boolean = true,
    onSearch: (() -> Unit)? = null,
) {
    val clearLabel = stringResource(R.string.search_clear)
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MahallaComponentDefaults.fieldMinHeight),
        enabled = enabled,
        singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = {
            androidx.compose.material3.Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
            )
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                MahallaIconButton(
                    icon = Icons.Outlined.Close,
                    contentDescription = clearLabel,
                    onClick = { onQueryChange("") },
                )
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = if (onSearch != null) ImeAction.Search else ImeAction.Done,
        ),
        shape = MaterialTheme.shapes.small,
    )
}

@Composable
private fun FieldSupportingText(supportingText: String?, errorText: String?) {
    val text = errorText ?: supportingText ?: return
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = Spacing.card),
        style = MaterialTheme.typography.labelSmall,
        color = if (errorText != null) {
            MaterialTheme.colorScheme.error
        } else {
            LocalMahallaColors.current.fgMuted
        },
    )
}

private const val COUNTRY_CODE = "998"

/** Точка вместо цифры PIN: сам код не должен читаться с экрана через плечо. */
private const val MASK_CHARACTER = "•"

@ThemeLanguagePreviews
@Composable
private fun MahallaFieldsPreview() {
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.gap)) {
            MahallaTextField(
                value = "Alisher",
                onValueChange = {},
                label = stringResource(R.string.profile_title),
                onClear = {},
            )
            MahallaPhoneField(digits = "901234567", onDigitsChange = {})
            MahallaPhoneField(
                digits = "9012",
                onDigitsChange = {},
                errorText = stringResource(R.string.onboarding_phone_error),
            )
            MahallaOtpField(state = OtpFieldState(code = "1234"), onCodeChange = {})
            MahallaSearchField(query = "", onQueryChange = {})
        }
    }
}
