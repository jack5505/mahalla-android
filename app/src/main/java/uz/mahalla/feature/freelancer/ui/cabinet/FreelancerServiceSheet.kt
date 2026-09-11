package uz.mahalla.feature.freelancer.ui.cabinet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import uz.mahalla.R
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.MahallaBottomSheet
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaErrorDetails
import uz.mahalla.core.ui.components.MahallaSwitchRow
import uz.mahalla.core.ui.components.MahallaTextField
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.core.ui.userMessage
import uz.mahalla.feature.freelancer.domain.FreelancerServiceDraft
import uz.mahalla.feature.freelancer.domain.FreelancerServiceFormError
import uz.mahalla.ui.theme.Spacing

/**
 * Форма своей услуги (issue #190): и добавление, и правка — одна шторка,
 * различает их только заголовок и подпись кнопки ([FreelancerServiceSheetState.isEditing]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreelancerServiceSheet(
    state: FreelancerServiceSheetState,
    onEvent: (FreelancerCabinetEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val errors = state.visibleErrors
    MahallaBottomSheet(
        onDismiss = { onEvent(FreelancerCabinetEvent.ServiceSheetDismissed) },
        modifier = modifier,
        title = stringResource(
            if (state.isEditing) {
                R.string.freelancer_service_form_edit_title
            } else {
                R.string.freelancer_service_form_add_title
            },
        ),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.gap)) {
            MahallaTextField(
                value = state.draft.title,
                onValueChange = { onEvent(FreelancerCabinetEvent.ServiceTitleChanged(it)) },
                label = stringResource(R.string.freelancer_service_field_title),
                placeholder = stringResource(R.string.freelancer_service_field_title_hint),
                errorText = errors.titleErrorText(),
                enabled = !state.submitting,
            )

            MahallaTextField(
                value = state.draft.description,
                onValueChange = { onEvent(FreelancerCabinetEvent.ServiceDescriptionChanged(it)) },
                label = stringResource(R.string.freelancer_service_field_description),
                errorText = errors.descriptionErrorText(),
                enabled = !state.submitting,
                singleLine = false,
            )

            MahallaTextField(
                value = state.draft.priceText,
                onValueChange = { onEvent(FreelancerCabinetEvent.ServicePriceChanged(it)) },
                label = stringResource(R.string.freelancer_service_field_price),
                errorText = if (FreelancerServiceFormError.PriceRequired in errors) {
                    stringResource(R.string.freelancer_service_error_price_required)
                } else {
                    null
                },
                enabled = !state.submitting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )

            MahallaTextField(
                value = state.draft.durationText,
                onValueChange = { onEvent(FreelancerCabinetEvent.ServiceDurationChanged(it)) },
                label = stringResource(R.string.freelancer_service_field_duration),
                errorText = if (FreelancerServiceFormError.DurationRequired in errors) {
                    stringResource(R.string.freelancer_service_error_duration_required)
                } else {
                    null
                },
                enabled = !state.submitting,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
            )

            MahallaSwitchRow(
                title = stringResource(R.string.freelancer_service_field_active),
                checked = state.draft.isActive,
                onCheckedChange = { onEvent(FreelancerCabinetEvent.ServiceActiveChanged(it)) },
                description = stringResource(R.string.freelancer_service_field_active_description),
                enabled = !state.submitting,
            )

            state.submitError?.let { failure ->
                Text(
                    text = failure.userMessage(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                failure.server?.let { server -> MahallaErrorDetails(server = server) }
            }

            MahallaButton(
                text = stringResource(
                    if (state.isEditing) {
                        R.string.freelancer_service_submit_save
                    } else {
                        R.string.freelancer_service_submit_add
                    },
                ),
                onClick = { onEvent(FreelancerCabinetEvent.ServiceSheetSubmitted) },
                modifier = Modifier.fillMaxWidth(),
                state = ButtonState(loading = state.submitting),
            )
        }
    }
}

@Composable
private fun List<FreelancerServiceFormError>.titleErrorText(): String? = when {
    FreelancerServiceFormError.TitleRequired in this ->
        stringResource(R.string.freelancer_service_error_title_required)

    else -> firstOrNull { it is FreelancerServiceFormError.TitleTooLong }
        ?.let { (it as FreelancerServiceFormError.TitleTooLong).max }
        ?.let { max -> pluralStringResource(R.plurals.role_error_too_long, max, max) }
}

@Composable
private fun List<FreelancerServiceFormError>.descriptionErrorText(): String? =
    firstOrNull { it is FreelancerServiceFormError.DescriptionTooLong }
        ?.let { (it as FreelancerServiceFormError.DescriptionTooLong).max }
        ?.let { max -> pluralStringResource(R.plurals.role_error_too_long, max, max) }

@ThemeLanguagePreviews
@Composable
private fun FreelancerServiceSheetPreview() {
    PreviewSurface {
        FreelancerServiceSheet(
            state = FreelancerServiceSheetState(
                draft = FreelancerServiceDraft(
                    title = "Kran almashtirish",
                    priceText = "80000",
                    durationText = "60",
                ),
            ),
            onEvent = {},
        )
    }
}
