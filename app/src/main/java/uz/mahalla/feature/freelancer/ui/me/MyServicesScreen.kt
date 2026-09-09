package uz.mahalla.feature.freelancer.ui.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.format.MoneyFormatter
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.CardSkeleton
import uz.mahalla.core.ui.components.EmptyState
import uz.mahalla.core.ui.components.MahallaBadge
import uz.mahalla.core.ui.components.MahallaBottomSheet
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaDialog
import uz.mahalla.core.ui.components.MahallaPhoneField
import uz.mahalla.core.ui.components.MahallaSwitchRow
import uz.mahalla.core.ui.components.MahallaTextField
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.core.ui.components.MahallaTopBar
import uz.mahalla.core.ui.components.SectionHeader
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.state.ScreenState
import uz.mahalla.feature.booking.domain.BarberService
import uz.mahalla.feature.booking.ui.InlineFailure
import uz.mahalla.feature.freelancer.domain.Freelancer
import uz.mahalla.feature.freelancer.domain.FreelancerProfileForm
import uz.mahalla.feature.freelancer.domain.FreelancerProfileFormError
import uz.mahalla.feature.freelancer.domain.FreelancerServiceForm
import uz.mahalla.feature.freelancer.domain.FreelancerServiceFormError
import uz.mahalla.feature.freelancer.ui.profile.priceNote
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing

/**
 * «Мои услуги» — кабинет мастера (issue #71): анкета исполнителя и услуги,
 * которые он выставляет.
 *
 * Вторая форма из двух: первая — заказ услуги на карточке мастера
 * (`FreelancerProfileScreen`). Здесь та же услуга появляется на свет.
 */
@Composable
fun MyServicesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MyServicesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    MyServicesContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun MyServicesContent(
    state: MyServicesState,
    onEvent: (MyServicesEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        MahallaTopBar(title = stringResource(R.string.my_services_title), onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // Анкету набирают с клавиатуры: без этого нижние поля и кнопка
                // сохранения оказались бы под ней.
                .imePadding()
                .padding(horizontal = Spacing.gutter)
                .padding(bottom = Spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.gap),
        ) {
            when (val profile = state.profile) {
                is ScreenState.Loading -> CardSkeleton()

                is ScreenState.Error -> InlineFailure(
                    failure = profile.failure,
                    onRetry = { onEvent(MyServicesEvent.Retry) },
                )

                // Анкеты ещё нет — это ответ сервера, а не отказ: показываем,
                // зачем она нужна, и сразу форму.
                is ScreenState.Empty -> {
                    IntroCard()
                    ProfileForm(state = state, onEvent = onEvent)
                }

                is ScreenState.Content -> {
                    if (state.formOpen) {
                        ProfileForm(state = state, onEvent = onEvent)
                    } else {
                        ProfileCard(freelancer = profile.data, onEvent = onEvent)
                        AvailabilityBlock(state = state, onEvent = onEvent)
                    }

                    SectionHeader(title = stringResource(R.string.my_services_section))
                    ServicesBlock(state = state, onEvent = onEvent)
                }
            }
        }
    }

    state.serviceForm?.let { form ->
        ServiceFormSheet(state = state, form = form, onEvent = onEvent)
    }

    state.confirmDelete?.let { service ->
        MahallaDialog(
            title = stringResource(R.string.my_services_delete_title),
            text = stringResource(
                R.string.my_services_delete_message,
                service.title.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.freelancer_service_unnamed),
            ),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { onEvent(MyServicesEvent.DeleteConfirmed) },
            onDismiss = { onEvent(MyServicesEvent.DeleteDismissed) },
            destructive = true,
        )
    }
}

/** Зачем анкета вообще нужна: без неё мастера в каталоге просто нет. */
@Composable
private fun IntroCard(modifier: Modifier = Modifier) {
    MahallaCard(modifier = modifier) {
        Text(
            text = stringResource(R.string.my_services_intro_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.my_services_intro_description),
            modifier = Modifier.padding(top = Spacing.item),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalMahallaColors.current.fgMuted,
        )
    }
}

/** Сохранённая анкета: то же, что увидит клиент в каталоге. */
@Composable
private fun ProfileCard(
    freelancer: Freelancer,
    onEvent: (MyServicesEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMahallaColors.current
    MahallaCard(modifier = modifier) {
        Text(
            text = freelancer.name.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.freelancer_unnamed),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        listOfNotNull(freelancer.profession, freelancer.city).forEach { line ->
            Text(
                text = line,
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        freelancer.hourlyRateSum.takeIf { it > 0 }?.let { rate ->
            Text(
                text = stringResource(
                    R.string.freelancer_rate,
                    MoneyFormatter.withCurrency(rate, stringResource(R.string.currency_uzs)),
                ),
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.fgMuted,
            )
        }
        MahallaButton(
            text = stringResource(R.string.my_services_edit_profile),
            onClick = { onEvent(MyServicesEvent.EditProfileClicked) },
            modifier = Modifier.padding(top = Spacing.item),
            variant = MahallaButtonVariant.Secondary,
        )
    }
}

/**
 * «Принимаю заказы». Выключенный мастер остаётся в каталоге, но помечен
 * занятым — об этом и говорит подпись: спрятать себя переключателем нельзя.
 */
@Composable
private fun AvailabilityBlock(
    state: MyServicesState,
    onEvent: (MyServicesEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
        MahallaSwitchRow(
            title = stringResource(R.string.my_services_available_title),
            checked = state.freelancer?.isAvailable ?: false,
            onCheckedChange = { onEvent(MyServicesEvent.AvailabilityToggled) },
            description = stringResource(R.string.my_services_available_description),
            enabled = !state.togglingAvailability,
        )
        state.availabilityFailure?.let { InlineFailure(failure = it) }
    }
}

/** Анкета: то, что уходит в `POST freelancers/me`. */
@Composable
private fun ProfileForm(
    state: MyServicesState,
    onEvent: (MyServicesEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val form = state.form
    val enabled = !state.savingProfile
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
        MahallaTextField(
            value = form.name,
            onValueChange = { onEvent(MyServicesEvent.NameChanged(it)) },
            label = stringResource(R.string.my_services_name_label),
            errorText = state.formError { it.isNameError() }?.text(),
            enabled = enabled,
        )
        MahallaTextField(
            value = form.profession,
            onValueChange = { onEvent(MyServicesEvent.ProfessionChanged(it)) },
            label = stringResource(R.string.my_services_profession_label),
            placeholder = stringResource(R.string.my_services_profession_placeholder),
            errorText = state.formError { it.isProfessionError() }?.text(),
            enabled = enabled,
        )
        MahallaTextField(
            value = form.city,
            onValueChange = { onEvent(MyServicesEvent.CityChanged(it)) },
            label = stringResource(R.string.my_services_city_label),
            placeholder = stringResource(R.string.my_services_city_placeholder),
            errorText = state.formError { it is FreelancerProfileFormError.CityTooLong }?.text(),
            enabled = enabled,
        )
        MahallaPhoneField(
            digits = form.phoneDigits,
            onDigitsChange = { onEvent(MyServicesEvent.PhoneChanged(it)) },
            errorText = state.formError { it is FreelancerProfileFormError.PhoneInvalid }?.text(),
            enabled = enabled,
        )
        MahallaTextField(
            value = form.hourlyRateText,
            onValueChange = { onEvent(MyServicesEvent.HourlyRateChanged(it)) },
            label = stringResource(R.string.my_services_rate_label),
            supportingText = stringResource(R.string.my_services_rate_hint),
            errorText = state
                .formError { it is FreelancerProfileFormError.HourlyRateInvalid }
                ?.text(),
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        MahallaTextField(
            value = form.experienceYearsText,
            onValueChange = { onEvent(MyServicesEvent.ExperienceChanged(it)) },
            label = stringResource(R.string.my_services_experience_label),
            errorText = state
                .formError { it is FreelancerProfileFormError.ExperienceInvalid }
                ?.text(),
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        MahallaTextField(
            value = form.bio,
            onValueChange = { onEvent(MyServicesEvent.BioChanged(it)) },
            label = stringResource(R.string.my_services_bio_label),
            placeholder = stringResource(R.string.my_services_bio_placeholder),
            errorText = state.formError { it is FreelancerProfileFormError.BioTooLong }?.text(),
            enabled = enabled,
            singleLine = false,
        )

        state.profileFailure?.let { InlineFailure(failure = it) }

        MahallaButton(
            text = stringResource(
                if (state.hasNoProfile) {
                    R.string.my_services_become_master
                } else {
                    R.string.action_save
                },
            ),
            onClick = { onEvent(MyServicesEvent.SaveProfileClicked) },
            state = ButtonState(loading = state.savingProfile),
        )
        // Отменить можно только правку уже сохранённой анкеты: когда её нет,
        // отменять нечего — экран без формы был бы пустым.
        if (!state.hasNoProfile) {
            MahallaButton(
                text = stringResource(R.string.action_cancel),
                onClick = { onEvent(MyServicesEvent.CancelProfileEditClicked) },
                variant = MahallaButtonVariant.Secondary,
                state = ButtonState(enabled = !state.savingProfile),
            )
        }
    }
}

/** Свои услуги: состав, который мастер показывает клиентам. */
@Composable
private fun ServicesBlock(
    state: MyServicesState,
    onEvent: (MyServicesEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.item)) {
        when (val services = state.services) {
            is ScreenState.Loading -> CardSkeleton()

            is ScreenState.Empty -> EmptyState(
                title = stringResource(R.string.my_services_empty_title),
                description = stringResource(R.string.my_services_empty_description),
                icon = Icons.Outlined.Handyman,
            )

            is ScreenState.Error -> InlineFailure(
                failure = services.failure,
                onRetry = { onEvent(MyServicesEvent.ServicesRetry) },
            )

            is ScreenState.Content -> services.data.forEach { service ->
                ServiceRow(
                    service = service,
                    deleting = state.deletingServiceId == service.id,
                    onEvent = onEvent,
                )
            }
        }

        // Отказ по услуге живёт рядом со списком, а не в форме: сохранение её
        // закрывает, а отказ удаления к форме не относится вовсе.
        state.serviceFailure
            ?.takeIf { state.serviceForm == null }
            ?.let { InlineFailure(failure = it) }

        // Кнопка всегда активна: блок целиком показывается только когда
        // анкета есть, а без неё выставлять услуги было бы некуда — их ручка
        // ходит по `id` анкеты.
        MahallaButton(
            text = stringResource(R.string.my_services_add),
            onClick = { onEvent(MyServicesEvent.AddServiceClicked) },
        )
    }
}

@Composable
private fun ServiceRow(
    service: BarberService,
    deleting: Boolean,
    onEvent: (MyServicesEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMahallaColors.current
    MahallaCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = service.title.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.freelancer_service_unnamed),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // Выключенную услугу клиент не видит — мастеру про это лучше
            // сказать, чем молча показать строку, которой ни у кого нет.
            if (!service.isActive) {
                MahallaBadge(
                    text = stringResource(R.string.my_services_inactive),
                    tone = MahallaTone.Neutral,
                )
            }
        }

        service.priceNote()?.let { note ->
            Text(
                text = note,
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.fgMuted,
            )
        }

        service.description?.let { description ->
            Text(
                text = description,
                modifier = Modifier.padding(top = Spacing.item),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Row(
            modifier = Modifier.padding(top = Spacing.item),
            horizontalArrangement = Arrangement.spacedBy(Spacing.item),
        ) {
            MahallaButton(
                text = stringResource(R.string.my_services_edit_service),
                onClick = { onEvent(MyServicesEvent.EditServiceClicked(service.id)) },
                modifier = Modifier.weight(1f),
                variant = MahallaButtonVariant.Secondary,
                state = ButtonState(enabled = !deleting),
            )
            MahallaButton(
                text = stringResource(R.string.action_delete),
                onClick = { onEvent(MyServicesEvent.DeleteServiceClicked(service.id)) },
                modifier = Modifier.weight(1f),
                variant = MahallaButtonVariant.Destructive,
                state = ButtonState(loading = deleting),
            )
        }
    }
}

/**
 * Форма услуги — в шторке, а не на отдельном экране: полей четыре, а список
 * услуг за ней остаётся видимым, и понятно, к чему добавляется строка.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceFormSheet(
    state: MyServicesState,
    form: FreelancerServiceForm,
    onEvent: (MyServicesEvent) -> Unit,
) {
    val enabled = !state.savingService
    MahallaBottomSheet(
        onDismiss = { onEvent(MyServicesEvent.ServiceFormDismissed) },
        title = stringResource(
            if (form.isNew) R.string.my_services_new_service else R.string.my_services_edit_service,
        ),
    ) {
        Column(
            // Полей четыре, и с открытой клавиатурой кнопка сохранения иначе
            // уезжает за край шторки: она не прокручивается сама.
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.item),
        ) {
            MahallaTextField(
                value = form.title,
                onValueChange = { onEvent(MyServicesEvent.ServiceTitleChanged(it)) },
                label = stringResource(R.string.my_services_service_title_label),
                placeholder = stringResource(R.string.my_services_service_title_placeholder),
                errorText = state.serviceError { it.isTitleError() }?.text(),
                enabled = enabled,
            )
            MahallaTextField(
                value = form.priceText,
                onValueChange = { onEvent(MyServicesEvent.ServicePriceChanged(it)) },
                label = stringResource(R.string.my_services_service_price_label),
                errorText = state.serviceError { it.isPriceError() }?.text(),
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            MahallaTextField(
                value = form.durationText,
                onValueChange = { onEvent(MyServicesEvent.ServiceDurationChanged(it)) },
                label = stringResource(R.string.my_services_service_duration_label),
                supportingText = stringResource(R.string.my_services_service_duration_hint),
                errorText = state
                    .serviceError { it is FreelancerServiceFormError.DurationInvalid }
                    ?.text(),
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            MahallaTextField(
                value = form.description,
                onValueChange = { onEvent(MyServicesEvent.ServiceDescriptionChanged(it)) },
                label = stringResource(R.string.my_services_service_description_label),
                errorText = state
                    .serviceError { it is FreelancerServiceFormError.DescriptionTooLong }
                    ?.text(),
                enabled = enabled,
                singleLine = false,
            )

            state.serviceFailure?.let { InlineFailure(failure = it) }

            MahallaButton(
                text = stringResource(R.string.action_save),
                onClick = { onEvent(MyServicesEvent.SaveServiceClicked) },
                state = ButtonState(loading = state.savingService),
            )
        }
    }
}

private fun FreelancerProfileFormError.isNameError(): Boolean =
    this is FreelancerProfileFormError.NameRequired ||
        this is FreelancerProfileFormError.NameTooShort ||
        this is FreelancerProfileFormError.NameTooLong

private fun FreelancerProfileFormError.isProfessionError(): Boolean =
    this is FreelancerProfileFormError.ProfessionRequired ||
        this is FreelancerProfileFormError.ProfessionTooLong

private fun FreelancerServiceFormError.isTitleError(): Boolean =
    this is FreelancerServiceFormError.TitleRequired ||
        this is FreelancerServiceFormError.TitleTooLong

private fun FreelancerServiceFormError.isPriceError(): Boolean =
    this is FreelancerServiceFormError.PriceRequired ||
        this is FreelancerServiceFormError.PriceInvalid

/** Текст ошибки анкеты. Длины — общим плюралом вертикали. */
@Composable
private fun FreelancerProfileFormError.text(): String = when (this) {
    FreelancerProfileFormError.NameRequired ->
        stringResource(R.string.my_services_error_name_required)

    is FreelancerProfileFormError.NameTooShort ->
        stringResource(R.string.my_services_error_name_short, min)

    is FreelancerProfileFormError.NameTooLong -> tooLong(max)
    FreelancerProfileFormError.ProfessionRequired ->
        stringResource(R.string.my_services_error_profession_required)

    is FreelancerProfileFormError.ProfessionTooLong -> tooLong(max)
    is FreelancerProfileFormError.CityTooLong -> tooLong(max)
    is FreelancerProfileFormError.BioTooLong -> tooLong(max)
    FreelancerProfileFormError.PhoneInvalid ->
        stringResource(R.string.my_services_error_phone_invalid)

    FreelancerProfileFormError.HourlyRateInvalid ->
        stringResource(R.string.my_services_error_number_invalid)

    FreelancerProfileFormError.ExperienceInvalid ->
        stringResource(R.string.my_services_error_number_invalid)
}

@Composable
private fun FreelancerServiceFormError.text(): String = when (this) {
    FreelancerServiceFormError.TitleRequired ->
        stringResource(R.string.my_services_error_service_title_required)

    is FreelancerServiceFormError.TitleTooLong -> tooLong(max)
    is FreelancerServiceFormError.DescriptionTooLong -> tooLong(max)
    FreelancerServiceFormError.PriceRequired ->
        stringResource(R.string.my_services_error_price_required)

    FreelancerServiceFormError.PriceInvalid ->
        stringResource(R.string.my_services_error_number_invalid)

    FreelancerServiceFormError.DurationInvalid ->
        stringResource(R.string.my_services_error_number_invalid)
}

@Composable
private fun tooLong(max: Int): String =
    pluralStringResource(R.plurals.freelancer_text_too_long, max, max)

@ThemeLanguagePreviews
@Composable
private fun MyServicesEmptyPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        MyServicesContent(
            state = MyServicesState(
                profile = ScreenState.Empty,
                form = FreelancerProfileForm(name = "Aziz Karimov", phoneDigits = "901234567"),
                formOpen = true,
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@ThemeLanguagePreviews
@Composable
private fun MyServicesFilledPreview() {
    PreviewSurface(modifier = Modifier.fillMaxSize()) {
        MyServicesContent(
            state = MyServicesState(
                profile = ScreenState.Content(
                    Freelancer(
                        id = "f-1",
                        name = "Aziz Karimov",
                        profession = "Santexnik",
                        city = "Toshkent",
                        hourlyRateSum = 80_000,
                        experienceYears = 7,
                    ),
                ),
                services = ScreenState.Content(
                    listOf(
                        BarberService(
                            id = "s-1",
                            title = "Kran almashtirish",
                            description = "Materiallar mijoznikidan",
                            priceSum = 150_000,
                            durationMinutes = 60,
                        ),
                        BarberService(
                            id = "s-2",
                            title = "Isitish tizimi",
                            priceSum = 400_000,
                            isActive = false,
                        ),
                    ),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
