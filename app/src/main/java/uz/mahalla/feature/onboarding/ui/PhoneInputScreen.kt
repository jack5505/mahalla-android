package uz.mahalla.feature.onboarding.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaPhoneField
import uz.mahalla.ui.theme.Spacing

/**
 * Первый экран, собранный из UI-кита (эпик 2): поле телефона с маской и
 * основная кнопка. Каретку в поле держит `PhoneFieldFormatter` — прежняя
 * версия форматировала строку прямо в `OutlinedTextField`, и курсор прыгал
 * в конец при каждом пробеле.
 */
@Composable
fun PhoneInputScreen(
    onCodeRequested: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PhoneInputViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is PhoneInputEffect.CodeRequested -> onCodeRequested(effect.phoneE164)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(Spacing.gap),
    ) {
        Text(
            text = stringResource(R.string.onboarding_phone_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        MahallaPhoneField(
            digits = state.nationalDigits,
            onDigitsChange = { viewModel.onEvent(PhoneInputEvent.PhoneChanged(it)) },
            errorText = if (state.error == PhoneInputError.INVALID_NUMBER) {
                stringResource(R.string.onboarding_phone_error)
            } else {
                null
            },
            imeAction = ImeAction.Done,
        )
        MahallaButton(
            text = stringResource(R.string.onboarding_phone_action),
            onClick = { viewModel.onEvent(PhoneInputEvent.Submit) },
        )
    }
}
