package uz.mahalla.feature.role.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.FilterChipUi
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaFilterRow
import uz.mahalla.core.ui.components.MahallaPhoneField
import uz.mahalla.core.ui.components.MahallaTextField
import uz.mahalla.core.ui.components.SectionHeader
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.map.domain.MapPoint
import uz.mahalla.feature.onboarding.domain.City
import uz.mahalla.feature.onboarding.ui.OnboardingApiError
import uz.mahalla.feature.onboarding.ui.OnboardingError
import uz.mahalla.feature.onboarding.ui.OnboardingNotice
import uz.mahalla.feature.onboarding.ui.OnboardingStep
import uz.mahalla.feature.onboarding.ui.labelRes
import uz.mahalla.feature.role.domain.PlaceModerationStatus
import uz.mahalla.feature.role.domain.ProviderForm
import uz.mahalla.feature.role.domain.ProviderFormError
import uz.mahalla.feature.role.domain.RegisteredPlace
import uz.mahalla.ui.theme.Spacing

/**
 * Анкета продавца (issue #84): заявка на регистрацию заведения, которое
 * оказывает услуги.
 *
 * Заявка уходит в `POST /api/v1/places` и попадает на модерацию, поэтому
 * успех — это не «готово», а отдельный экран с объяснением: в каталоге
 * заведение появится не сразу.
 */
@Composable
fun ProviderFormScreen(
    onFinished: () -> Unit,
    onPickLocation: (MapPoint?) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    pickedLocation: MapPoint? = null,
    onPickedLocationHandled: () -> Unit = {},
    viewModel: ProviderFormViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Точка приезжает с карты через `SavedStateHandle` записи стека (граф), а
    // не эффектом: экран выбора не знает, кто его позвал. Ключ гасится сразу
    // после применения — иначе та же точка вернулась бы в форму после каждого
    // возврата на неё.
    LaunchedEffect(pickedLocation) {
        if (pickedLocation != null) {
            viewModel.onEvent(ProviderFormEvent.LocationPicked(pickedLocation))
            onPickedLocationHandled()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                ProviderFormEffect.Finished -> onFinished()
                is ProviderFormEffect.OpenMapPicker -> onPickLocation(effect.point)
            }
        }
    }

    ProviderFormContent(
        state = state,
        onEvent = viewModel::onEvent,
        modifier = modifier,
        onBack = onBack,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun ProviderFormContent(
    state: ProviderFormState,
    onEvent: (ProviderFormEvent) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val registered = state.registered
    if (registered != null) {
        ProviderSubmittedContent(
            place = registered,
            onDone = { onEvent(ProviderFormEvent.DoneClicked) },
            modifier = modifier,
        )
        return
    }

    OnboardingStep(
        title = stringResource(R.string.role_provider_form_title),
        modifier = modifier,
        subtitle = stringResource(R.string.role_provider_form_subtitle),
        onBack = onBack,
        footer = {
            state.submitError?.let { OnboardingApiError(failure = it) }
            MahallaButton(
                text = stringResource(R.string.role_provider_submit),
                onClick = { onEvent(ProviderFormEvent.SubmitClicked) },
                state = ButtonState(enabled = !state.submitting, loading = state.submitting),
            )
        },
    ) {
        MahallaTextField(
            value = state.form.name,
            onValueChange = { onEvent(ProviderFormEvent.NameChanged(it)) },
            label = stringResource(R.string.role_field_place_name),
            placeholder = stringResource(R.string.role_field_place_name_hint),
            errorText = state.nameErrorText(),
            enabled = !state.submitting,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        )

        SectionHeader(title = stringResource(R.string.role_field_category))
        MahallaFilterRow(
            items = state.categories.map { category ->
                FilterChipUi(
                    id = category.apiValue,
                    label = stringResource(category.labelRes),
                    icon = category.icon,
                )
            },
            selectedId = state.form.category?.apiValue,
            onSelect = { id ->
                onEvent(ProviderFormEvent.CategorySelected(PlaceCategory.fromApi(id)))
            },
        )
        if (state.error { it is ProviderFormError.CategoryRequired } != null) {
            OnboardingError(stringResource(R.string.role_error_category_required))
        }

        SectionHeader(title = stringResource(R.string.role_field_city))
        MahallaFilterRow(
            items = state.cities.map { city ->
                FilterChipUi(id = city.id, label = stringResource(city.labelRes()))
            },
            selectedId = state.form.city?.id,
            onSelect = { id ->
                City.fromId(id)?.let { onEvent(ProviderFormEvent.CitySelected(it)) }
            },
        )
        if (state.error { it is ProviderFormError.CityRequired } != null) {
            OnboardingError(stringResource(R.string.role_error_city_required))
        }

        MahallaTextField(
            value = state.form.address,
            onValueChange = { onEvent(ProviderFormEvent.AddressChanged(it)) },
            label = stringResource(R.string.role_field_place_address),
            placeholder = stringResource(R.string.role_field_address_hint),
            // Адрес человек пишет словами, а точку показывает на карте:
            // геокодера в проекте нет (MapKit взят в варианте `lite`), и одно
            // из другого не выводится.
            supportingText = stringResource(R.string.role_field_place_address_note),
            errorText = state.addressErrorText(),
            enabled = !state.submitting,
            singleLine = false,
        )

        PlaceLocationBlock(
            point = state.form.location,
            enabled = !state.submitting,
            onPick = { onEvent(ProviderFormEvent.PickLocationClicked) },
        )

        MahallaPhoneField(
            digits = state.form.phoneDigits,
            onDigitsChange = { onEvent(ProviderFormEvent.PhoneChanged(it)) },
            label = stringResource(R.string.role_field_place_phone),
            errorText = state.error { it is ProviderFormError.PhoneInvalid }
                ?.let { stringResource(R.string.role_error_phone_invalid) },
            enabled = !state.submitting,
        )

        MahallaTextField(
            value = state.form.description,
            onValueChange = { onEvent(ProviderFormEvent.DescriptionChanged(it)) },
            label = stringResource(R.string.role_field_description),
            placeholder = stringResource(R.string.role_field_description_hint),
            errorText = state.descriptionErrorText(),
            enabled = !state.submitting,
            singleLine = false,
        )

        MahallaTextField(
            value = state.form.website,
            onValueChange = { onEvent(ProviderFormEvent.WebsiteChanged(it)) },
            label = stringResource(R.string.role_field_website),
            placeholder = stringResource(R.string.role_field_website_hint),
            errorText = state.error { it is ProviderFormError.WebsiteInvalid }
                ?.let { stringResource(R.string.role_error_website_invalid) },
            enabled = !state.submitting,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
    }
}

/**
 * Точка заведения на карте (issue #90).
 *
 * Необязательна: без неё координаты берутся от устройства или по городу — и
 * тогда это прямо написано, а не молча подставлено. Выбранная точка
 * показывается цифрами: адреса у неё нет (геокодера в проекте нет), и
 * проверить выбор человеку иначе нечем.
 */
@Composable
private fun PlaceLocationBlock(
    point: MapPoint?,
    enabled: Boolean,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        SectionHeader(title = stringResource(R.string.role_field_place_point))
        Text(
            text = point?.formatted() ?: stringResource(R.string.role_place_point_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MahallaButton(
            text = stringResource(
                if (point == null) R.string.role_place_point_pick else R.string.role_place_point_change,
            ),
            onClick = onPick,
            variant = MahallaButtonVariant.Secondary,
            state = ButtonState(enabled = enabled),
        )
    }
}

/**
 * Подтверждение заявки. Статус проговаривается словами: «принято» и
 * «опубликовано» — разные вещи, и ждать заведение в каталоге через минуту не
 * стоит.
 */
@Composable
private fun ProviderSubmittedContent(
    place: RegisteredPlace,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OnboardingStep(
        title = stringResource(R.string.role_provider_submitted_title),
        modifier = modifier,
        subtitle = stringResource(R.string.role_provider_submitted_subtitle, place.name),
        footer = {
            MahallaButton(text = stringResource(R.string.action_done), onClick = onDone)
        },
    ) {
        OnboardingNotice(
            text = when (place.status) {
                PlaceModerationStatus.Active ->
                    stringResource(R.string.role_provider_status_active)

                // Неизвестный статус описываем как «на проверке»: заявка
                // принята, а чем она кончится, решает не приложение.
                else -> stringResource(R.string.role_provider_status_pending)
            },
        )
    }
}

@Composable
private fun ProviderFormState.nameErrorText(): String? = when (
    val error = error {
        it is ProviderFormError.NameRequired ||
            it is ProviderFormError.NameTooShort ||
            it is ProviderFormError.NameTooLong
    }
) {
    ProviderFormError.NameRequired -> stringResource(R.string.role_error_name_required)
    is ProviderFormError.NameTooShort ->
        pluralStringResource(R.plurals.role_error_too_short, error.min, error.min)

    is ProviderFormError.NameTooLong ->
        pluralStringResource(R.plurals.role_error_too_long, error.max, error.max)
    else -> null
}

@Composable
private fun ProviderFormState.addressErrorText(): String? = when (
    val error = error {
        it is ProviderFormError.AddressRequired || it is ProviderFormError.AddressTooLong
    }
) {
    ProviderFormError.AddressRequired -> stringResource(R.string.role_error_address_required)
    is ProviderFormError.AddressTooLong ->
        pluralStringResource(R.plurals.role_error_too_long, error.max, error.max)
    else -> null
}

@Composable
private fun ProviderFormState.descriptionErrorText(): String? =
    when (val error = error { it is ProviderFormError.DescriptionTooLong }) {
        is ProviderFormError.DescriptionTooLong ->
            pluralStringResource(R.plurals.role_error_too_long, error.max, error.max)

        else -> null
    }

@ThemeLanguagePreviews
@Composable
private fun ProviderFormPreview() {
    PreviewSurface {
        ProviderFormContent(
            state = ProviderFormState(
                form = ProviderForm(
                    name = "Osh Markazi",
                    category = PlaceCategory.Food,
                    city = City.TASHKENT,
                    address = "Chilonzor, 12-kvartal",
                    phoneDigits = "901234567",
                    location = MapPoint(latitude = 41.311081, longitude = 69.240562),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@ThemeLanguagePreviews
@Composable
private fun ProviderSubmittedPreview() {
    PreviewSurface {
        ProviderSubmittedContent(
            place = RegisteredPlace(
                id = "p-1",
                name = "Osh Markazi",
                status = PlaceModerationStatus.Pending,
            ),
            onDone = {},
        )
    }
}
