package uz.mahalla.feature.services.ui.offer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import uz.mahalla.core.format.MoneyFormatter
import uz.mahalla.core.format.RatingFormatter
import uz.mahalla.core.ui.components.ApiErrorState
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.ListSkeleton
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaPhoneField
import uz.mahalla.core.ui.components.MahallaSwitchRow
import uz.mahalla.core.ui.components.MahallaTextField
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.feature.services.domain.ServiceOffer
import uz.mahalla.feature.services.domain.ServiceOfferError
import uz.mahalla.feature.services.domain.ServiceOfferForm
import uz.mahalla.feature.services.domain.ServiceOfferValidator
import uz.mahalla.feature.services.ui.ServiceFormError
import uz.mahalla.ui.theme.Spacing

/**
 * Форма выставления услуги (issue #71) — половина исполнителя.
 *
 * Открывается из профиля строкой «Мои услуги»: человек описывает, чем
 * помогает и почём (`POST freelancers/me`), и с этого момента его находят в
 * каталоге мастеров. Переключатель «принимаю заказы» убирает анкету из
 * выдачи, не удаляя её.
 */
@Composable
fun ServiceOfferScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ServiceOfferViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                ServiceOfferEffect.NavigateBack -> onBack()
            }
        }
    }

    ServiceOfferContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun ServiceOfferContent(
    state: ServiceOfferState,
    onEvent: (ServiceOfferEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(title = stringResource(R.string.service_offer_title), onBack = onBack)

        when {
            state.isLoading -> ListSkeleton(
                modifier = Modifier.padding(horizontal = Spacing.gutter),
                itemCount = SKELETON_ITEMS,
            )

            // Анкету не прочитали — форму показывать нельзя: сохранение
            // затёрло бы ту, что лежит на сервере, пустыми полями.
            state.loadFailure != null -> Box(modifier = Modifier.weight(1f)) {
                ApiErrorState(
                    failure = state.loadFailure,
                    onRetry = { onEvent(ServiceOfferEvent.RetryRequested) },
                )
            }

            else -> ServiceOfferFormBlock(state = state, onEvent = onEvent)
        }
    }
}

@Composable
private fun ServiceOfferFormBlock(
    state: ServiceOfferState,
    onEvent: (ServiceOfferEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currency = stringResource(R.string.currency_uzs)
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = Spacing.gutter)
            .padding(bottom = Spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(Spacing.gap),
    ) {
        Text(
            text = stringResource(R.string.service_offer_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.offer?.let { offer ->
            MahallaSwitchRow(
                title = stringResource(R.string.service_offer_available),
                description = stringResource(R.string.service_offer_available_description),
                checked = offer.isAvailable,
                // Значение приходит от сервера, поэтому переключатель не
                // принимает желаемое состояние — он просто просит его сменить.
                onCheckedChange = { onEvent(ServiceOfferEvent.AvailabilityToggled) },
                enabled = !state.availabilityPending,
            )
            offer.rating()?.let { rating ->
                MahallaCard {
                    Text(
                        text = rating,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(
                            R.string.service_offer_rating_count,
                            offer.ratingCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        MahallaTextField(
            value = state.form.name,
            onValueChange = { onEvent(ServiceOfferEvent.NameChanged(it)) },
            label = stringResource(R.string.service_offer_name),
            errorText = state.error { it is ServiceOfferError.NameRequired }
                ?.let { stringResource(R.string.service_offer_error_name) }
                ?: state.lengthError<ServiceOfferError.NameTooLong>(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )

        MahallaTextField(
            value = state.form.profession,
            onValueChange = { onEvent(ServiceOfferEvent.ProfessionChanged(it)) },
            label = stringResource(R.string.service_offer_profession),
            placeholder = stringResource(R.string.service_offer_profession_placeholder),
            errorText = state.error { it is ServiceOfferError.ProfessionRequired }
                ?.let { stringResource(R.string.service_offer_error_profession) }
                ?: state.lengthError<ServiceOfferError.ProfessionTooLong>(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )

        MahallaTextField(
            value = state.form.city,
            onValueChange = { onEvent(ServiceOfferEvent.CityChanged(it)) },
            label = stringResource(R.string.service_offer_city),
            errorText = state.error { it is ServiceOfferError.CityRequired }
                ?.let { stringResource(R.string.service_offer_error_city) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )

        MahallaTextField(
            value = state.form.hourlyRate,
            onValueChange = { onEvent(ServiceOfferEvent.RateChanged(it)) },
            label = stringResource(R.string.service_offer_rate),
            supportingText = stringResource(R.string.service_offer_rate_hint),
            errorText = state.error { it is ServiceOfferError.RateInvalid }?.let {
                stringResource(
                    R.string.service_offer_error_rate,
                    MoneyFormatter.withCurrency(ServiceOfferValidator.MAX_RATE_SUM, currency),
                )
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next,
            ),
        )

        MahallaTextField(
            value = state.form.experienceYears,
            onValueChange = { onEvent(ServiceOfferEvent.ExperienceChanged(it)) },
            label = stringResource(R.string.service_offer_experience),
            errorText = state.error { it is ServiceOfferError.ExperienceInvalid }?.let {
                stringResource(
                    R.string.service_offer_error_experience,
                    ServiceOfferValidator.MAX_EXPERIENCE_YEARS,
                )
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next,
            ),
        )

        MahallaPhoneField(
            digits = state.form.phoneDigits,
            onDigitsChange = { onEvent(ServiceOfferEvent.PhoneChanged(it)) },
            label = stringResource(R.string.service_offer_phone),
            errorText = state.error { it is ServiceOfferError.PhoneInvalid }
                ?.let { stringResource(R.string.service_offer_error_phone) },
            imeAction = ImeAction.Next,
        )

        MahallaTextField(
            value = state.form.bio,
            onValueChange = { onEvent(ServiceOfferEvent.BioChanged(it)) },
            label = stringResource(R.string.service_offer_bio),
            placeholder = stringResource(R.string.service_offer_bio_placeholder),
            errorText = state.lengthError<ServiceOfferError.BioTooLong>(),
            singleLine = false,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )

        MahallaButton(
            text = stringResource(R.string.service_offer_save),
            onClick = { onEvent(ServiceOfferEvent.SaveClicked) },
            state = ButtonState(loading = state.isSaving),
        )

        if (state.saved) {
            Text(
                text = stringResource(R.string.service_offer_saved),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        state.saveFailure?.let { ServiceFormError(failure = it) }
    }
}

/**
 * Общий текст «слишком длинно» для трёх полей: предел у каждого свой, а
 * формулировка одна.
 */
@Composable
private inline fun <reified T : ServiceOfferError> ServiceOfferState.lengthError(): String? {
    val maxLength = when (val error = error { it is T }) {
        is ServiceOfferError.NameTooLong -> error.maxLength
        is ServiceOfferError.ProfessionTooLong -> error.maxLength
        is ServiceOfferError.BioTooLong -> error.maxLength
        else -> return null
    }
    return stringResource(R.string.service_offer_error_too_long, maxLength)
}

@Composable
private fun ServiceOffer.rating(): String? =
    RatingFormatter.format(ratingAverage ?: 0.0, ratingCount)
        ?.let { stringResource(R.string.service_offer_rating, it) }

private const val SKELETON_ITEMS = 4

@ThemeLanguagePreviews
@Composable
private fun ServiceOfferPreview() {
    PreviewSurface {
        ServiceOfferContent(
            state = ServiceOfferState(
                isLoading = false,
                offer = ServiceOffer(
                    id = "f-1",
                    name = "Jahongir",
                    profession = "Sartarosh",
                    isAvailable = true,
                    ratingAverage = 4.8,
                    ratingCount = 12,
                ),
                form = ServiceOfferForm(
                    name = "Jahongir",
                    profession = "Sartarosh",
                    city = "Toshkent",
                    phoneDigits = "901234567",
                    hourlyRate = "80000",
                    experienceYears = "5",
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
