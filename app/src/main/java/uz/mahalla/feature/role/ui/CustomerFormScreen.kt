package uz.mahalla.feature.role.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.FilterChipUi
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaFilterRow
import uz.mahalla.core.ui.components.MahallaTextField
import uz.mahalla.core.ui.components.SectionHeader
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.feature.onboarding.domain.City
import uz.mahalla.feature.onboarding.ui.OnboardingError
import uz.mahalla.feature.onboarding.ui.OnboardingStep
import uz.mahalla.feature.onboarding.ui.labelRes
import uz.mahalla.feature.role.domain.CustomerForm
import uz.mahalla.feature.role.domain.CustomerFormError

/**
 * Анкета покупателя (issue #84): имя, город и адрес по умолчанию.
 *
 * Всё, что здесь вводят, приложение использует само: имя — шапка профиля,
 * город — координаты запросов к каталогу, адрес — подстановка в оформление
 * заказа. Серверу отправить это нечем: профиля пользователя у бэкенда нет
 * (issue #61).
 */
@Composable
fun CustomerFormScreen(
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    viewModel: CustomerFormViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                CustomerFormEffect.Saved -> onSaved()
            }
        }
    }

    CustomerFormContent(
        state = state,
        onEvent = viewModel::onEvent,
        modifier = modifier,
        onBack = onBack,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun CustomerFormContent(
    state: CustomerFormState,
    onEvent: (CustomerFormEvent) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    OnboardingStep(
        title = stringResource(R.string.role_customer_form_title),
        modifier = modifier,
        subtitle = stringResource(R.string.role_customer_form_subtitle),
        onBack = onBack,
        footer = {
            if (state.storageFailed) {
                OnboardingError(stringResource(R.string.role_form_storage_error))
            }
            MahallaButton(
                text = stringResource(R.string.action_save),
                onClick = { onEvent(CustomerFormEvent.SubmitClicked) },
                state = ButtonState(enabled = !state.saving, loading = state.saving),
            )
        },
    ) {
        MahallaTextField(
            value = state.form.fullName,
            onValueChange = { onEvent(CustomerFormEvent.NameChanged(it)) },
            label = stringResource(R.string.role_field_name),
            placeholder = stringResource(R.string.role_field_name_hint),
            errorText = state.nameErrorText(),
            enabled = !state.saving,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        )

        SectionHeader(title = stringResource(R.string.role_field_city))
        MahallaFilterRow(
            items = state.cities.map { city ->
                FilterChipUi(id = city.id, label = stringResource(city.labelRes()))
            },
            selectedId = state.form.city?.id,
            onSelect = { id ->
                City.fromId(id)?.let { onEvent(CustomerFormEvent.CitySelected(it)) }
            },
        )
        if (state.error { it is CustomerFormError.CityRequired } != null) {
            OnboardingError(stringResource(R.string.role_error_city_required))
        }

        MahallaTextField(
            value = state.form.address,
            onValueChange = { onEvent(CustomerFormEvent.AddressChanged(it)) },
            label = stringResource(R.string.role_field_address),
            placeholder = stringResource(R.string.role_field_address_hint),
            // Адрес необязателен: заказы можно забирать самому, и требовать
            // его от того, кто не пользуется доставкой, незачем.
            supportingText = stringResource(R.string.role_field_address_hint_note),
            errorText = state.addressErrorText(),
            enabled = !state.saving,
            singleLine = false,
        )
    }
}

@Composable
private fun CustomerFormState.nameErrorText(): String? =
    when (val error = error { it is CustomerFormError.NameRequired || it is CustomerFormError.NameTooLong }) {
        CustomerFormError.NameRequired -> stringResource(R.string.role_error_name_required)
        is CustomerFormError.NameTooLong ->
            pluralStringResource(R.plurals.role_error_too_long, error.max, error.max)

        else -> null
    }

@Composable
private fun CustomerFormState.addressErrorText(): String? =
    when (val error = error { it is CustomerFormError.AddressTooLong }) {
        is CustomerFormError.AddressTooLong ->
            pluralStringResource(R.plurals.role_error_too_long, error.max, error.max)
        else -> null
    }

@ThemeLanguagePreviews
@Composable
private fun CustomerFormPreview() {
    PreviewSurface {
        CustomerFormContent(
            state = CustomerFormState(
                form = CustomerForm(
                    fullName = "Jahongir Sabirov",
                    city = City.TASHKENT,
                    address = "Chilonzor, 12-kvartal, 4-uy",
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
