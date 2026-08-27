package uz.mahalla.feature.onboarding.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiFailure
import uz.mahalla.core.result.ServerError
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaCheckboxRow
import uz.mahalla.core.ui.components.MahallaPhoneField
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.feature.auth.domain.OtpChallenge

/**
 * Ввод номера телефона (3.2): маска `+998`, валидация и согласие с офертой.
 *
 * Каретку в поле держит `PhoneFieldFormatter` — форматирование строки прямо
 * в `OutlinedTextField` уводило курсор в конец на каждом пробеле.
 */
@Composable
fun PhoneInputScreen(
    onCodeRequested: (String, OtpChallenge) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PhoneInputViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val offerUrl = stringResource(R.string.onboarding_offer_url)

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is PhoneInputEffect.CodeRequested ->
                    onCodeRequested(effect.phoneE164, effect.challenge)

                PhoneInputEffect.OpenOffer -> {
                    // Браузера может не быть (кастомная прошивка, kiosk-режим) —
                    // это не повод падать на экране входа.
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(offerUrl)))
                    }.onFailure { if (it !is ActivityNotFoundException) throw it }
                }
            }
        }
    }

    PhoneInputContent(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
private fun PhoneInputContent(
    state: PhoneInputState,
    onEvent: (PhoneInputEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OnboardingStep(
        title = stringResource(R.string.onboarding_phone_title),
        modifier = modifier,
        subtitle = stringResource(R.string.onboarding_phone_subtitle),
        onBack = onBack,
        footer = {
            MahallaButton(
                text = stringResource(R.string.onboarding_phone_action),
                onClick = { onEvent(PhoneInputEvent.Submit) },
                state = ButtonState(
                    enabled = state.canSubmit,
                    loading = state.submitting,
                ),
            )
        },
    ) {
        MahallaPhoneField(
            digits = state.nationalDigits,
            onDigitsChange = { onEvent(PhoneInputEvent.PhoneChanged(it)) },
            errorText = if (state.error == PhoneInputError.INVALID_NUMBER) {
                stringResource(R.string.onboarding_phone_error)
            } else {
                null
            },
            enabled = !state.submitting,
            imeAction = ImeAction.Done,
        )
        MahallaCheckboxRow(
            title = stringResource(R.string.onboarding_phone_consent),
            checked = state.consentAccepted,
            onCheckedChange = { onEvent(PhoneInputEvent.ConsentChanged(it)) },
            enabled = !state.submitting,
            isError = state.error == PhoneInputError.CONSENT_REQUIRED,
            linkLabel = stringResource(R.string.onboarding_phone_consent_link),
            onLinkClick = { onEvent(PhoneInputEvent.OfferRequested) },
        )
        if (state.error == PhoneInputError.CONSENT_REQUIRED) {
            OnboardingError(stringResource(R.string.onboarding_phone_consent_required))
        }
        state.apiFailure?.let { OnboardingApiError(it) }
    }
}

@ThemeLanguagePreviews
@Composable
private fun PhoneInputScreenPreview() {
    PreviewSurface {
        PhoneInputContent(
            state = PhoneInputState(
                nationalDigits = "901234",
                consentAccepted = true,
                // Тот самый случай из issue #34: бэкенд объяснил причину, а
                // экран показывал «нет прав на это действие».
                apiFailure = ApiFailure(
                    error = ApiError.Forbidden,
                    server = ServerError(
                        httpCode = 403,
                        code = "GEO_PERMISSION_REQUIRED",
                        message = "Mahalla ilovasidan foydalanish uchun joylashuv " +
                            "ruxsatini yoqing.",
                        requestLine = "POST https://api.mahalla.uz/auth/otp/request",
                    ),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
