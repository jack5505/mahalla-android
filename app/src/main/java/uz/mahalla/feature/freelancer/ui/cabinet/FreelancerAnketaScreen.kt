package uz.mahalla.feature.freelancer.ui.cabinet

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaPhoneField
import uz.mahalla.core.ui.components.MahallaTextField
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.feature.freelancer.domain.FreelancerAnketaForm
import uz.mahalla.feature.freelancer.domain.FreelancerAnketaFormError
import uz.mahalla.feature.freelancer.domain.Freelancer
import uz.mahalla.feature.onboarding.ui.OnboardingApiError
import uz.mahalla.feature.onboarding.ui.OnboardingStep

/**
 * Анкета мастера (issue #190): одна форма и на первую отправку, и на правку —
 * своего `PUT` у бэкенда нет, обе идут в `POST freelancers/me`.
 */
@Composable
fun FreelancerAnketaScreen(
    onSubmitted: (Freelancer) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    viewModel: FreelancerAnketaViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is FreelancerAnketaEffect.Submitted -> onSubmitted(effect.profile)
            }
        }
    }

    FreelancerAnketaContent(
        state = state,
        onEvent = viewModel::onEvent,
        modifier = modifier,
        onBack = onBack,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun FreelancerAnketaContent(
    state: FreelancerAnketaState,
    onEvent: (FreelancerAnketaEvent) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    OnboardingStep(
        title = stringResource(R.string.freelancer_anketa_title),
        modifier = modifier,
        subtitle = stringResource(R.string.freelancer_anketa_subtitle),
        onBack = onBack,
        footer = {
            state.submitError?.let { OnboardingApiError(failure = it) }
            MahallaButton(
                text = stringResource(R.string.freelancer_anketa_submit),
                onClick = { onEvent(FreelancerAnketaEvent.SubmitClicked) },
                state = ButtonState(enabled = !state.submitting, loading = state.submitting),
            )
        },
    ) {
        MahallaTextField(
            value = state.form.name,
            onValueChange = { onEvent(FreelancerAnketaEvent.NameChanged(it)) },
            label = stringResource(R.string.freelancer_anketa_field_name),
            errorText = state.nameErrorText(),
            enabled = !state.submitting,
        )

        MahallaTextField(
            value = state.form.profession,
            onValueChange = { onEvent(FreelancerAnketaEvent.ProfessionChanged(it)) },
            label = stringResource(R.string.freelancer_anketa_field_profession),
            placeholder = stringResource(R.string.freelancer_anketa_field_profession_hint),
            errorText = state.professionErrorText(),
            enabled = !state.submitting,
        )

        MahallaTextField(
            value = state.form.bio,
            onValueChange = { onEvent(FreelancerAnketaEvent.BioChanged(it)) },
            label = stringResource(R.string.freelancer_anketa_field_bio),
            errorText = state.bioErrorText(),
            enabled = !state.submitting,
            singleLine = false,
        )

        MahallaTextField(
            value = state.form.city,
            onValueChange = { onEvent(FreelancerAnketaEvent.CityChanged(it)) },
            label = stringResource(R.string.freelancer_anketa_field_city),
            enabled = !state.submitting,
        )

        MahallaPhoneField(
            digits = state.form.phoneDigits,
            onDigitsChange = { onEvent(FreelancerAnketaEvent.PhoneChanged(it)) },
            label = stringResource(R.string.freelancer_anketa_field_phone),
            errorText = state.error { it is FreelancerAnketaFormError.PhoneInvalid }
                ?.let { stringResource(R.string.role_error_phone_invalid) },
            enabled = !state.submitting,
        )

        MahallaTextField(
            value = state.form.hourlyRateText,
            onValueChange = { onEvent(FreelancerAnketaEvent.HourlyRateChanged(it)) },
            label = stringResource(R.string.freelancer_anketa_field_hourly_rate),
            enabled = !state.submitting,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )

        MahallaTextField(
            value = state.form.experienceYearsText,
            onValueChange = { onEvent(FreelancerAnketaEvent.ExperienceYearsChanged(it)) },
            label = stringResource(R.string.freelancer_anketa_field_experience),
            enabled = !state.submitting,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}

@Composable
private fun FreelancerAnketaState.nameErrorText(): String? = when (
    val error = error {
        it is FreelancerAnketaFormError.NameRequired ||
            it is FreelancerAnketaFormError.NameTooShort ||
            it is FreelancerAnketaFormError.NameTooLong
    }
) {
    FreelancerAnketaFormError.NameRequired -> stringResource(R.string.role_error_name_required)
    is FreelancerAnketaFormError.NameTooShort ->
        pluralStringResource(R.plurals.role_error_too_short, error.min, error.min)

    is FreelancerAnketaFormError.NameTooLong ->
        pluralStringResource(R.plurals.role_error_too_long, error.max, error.max)

    else -> null
}

@Composable
private fun FreelancerAnketaState.professionErrorText(): String? = when (
    val error = error {
        it is FreelancerAnketaFormError.ProfessionRequired ||
            it is FreelancerAnketaFormError.ProfessionTooLong
    }
) {
    FreelancerAnketaFormError.ProfessionRequired ->
        stringResource(R.string.freelancer_anketa_error_profession_required)

    is FreelancerAnketaFormError.ProfessionTooLong ->
        pluralStringResource(R.plurals.role_error_too_long, error.max, error.max)

    else -> null
}

@Composable
private fun FreelancerAnketaState.bioErrorText(): String? =
    when (val error = error { it is FreelancerAnketaFormError.BioTooLong }) {
        is FreelancerAnketaFormError.BioTooLong ->
            pluralStringResource(R.plurals.role_error_too_long, error.max, error.max)

        else -> null
    }

@ThemeLanguagePreviews
@Composable
private fun FreelancerAnketaPreview() {
    PreviewSurface {
        FreelancerAnketaContent(
            state = FreelancerAnketaState(
                form = FreelancerAnketaForm(
                    name = "Aziz Karimov",
                    profession = "Santexnik",
                    bio = "Quvurlar, isitish, avariya chaqiruvi.",
                    city = "Toshkent",
                    phoneDigits = "901234567",
                    hourlyRateText = "50000",
                    experienceYearsText = "7",
                ),
                editing = true,
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
