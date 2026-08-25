package uz.mahalla.feature.onboarding.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import uz.mahalla.R
import uz.mahalla.core.ui.components.ScreenAction
import uz.mahalla.core.ui.components.ScreenSkeleton

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

    ScreenSkeleton(
        title = stringResource(R.string.onboarding_phone_title),
        modifier = modifier,
        actions = listOf(
            ScreenAction(
                label = stringResource(R.string.onboarding_phone_action),
                onClick = { viewModel.onEvent(PhoneInputEvent.Submit) },
            ),
        ),
    ) {
        OutlinedTextField(
            value = state.formatted,
            onValueChange = { viewModel.onEvent(PhoneInputEvent.PhoneChanged(it)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = state.error != null,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Phone,
                imeAction = ImeAction.Done,
            ),
            label = { Text(stringResource(R.string.onboarding_phone_label)) },
        )
        if (state.error == PhoneInputError.INVALID_NUMBER) {
            Text(
                text = stringResource(R.string.onboarding_phone_error),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
