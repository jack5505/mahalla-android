package uz.mahalla.feature.role.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaChoiceCard
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.feature.onboarding.ui.OnboardingStep
import uz.mahalla.feature.role.domain.UserRole

/**
 * «Кто вы» (issue #84): покупатель или продавец, и дальше — своя анкета.
 *
 * Экран один на два входа: последний шаг онбординга и строка в профиле.
 * Различие ровно одно — [onSkip]: в онбординге анкету можно отложить (иначе
 * регистрация упирается в форму, которую человек пока не хочет заполнять), а
 * из профиля отказываться не от чего, оттуда выходят кнопкой «назад».
 *
 * @param onSkip `null` — экран открыт из профиля.
 */
@Composable
fun RoleScreen(
    onCustomerForm: () -> Unit,
    onProviderForm: () -> Unit,
    modifier: Modifier = Modifier,
    onSkip: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    viewModel: RoleViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                RoleEffect.OpenCustomerForm -> onCustomerForm()
                RoleEffect.OpenProviderForm -> onProviderForm()
            }
        }
    }

    RoleContent(
        state = state,
        onEvent = viewModel::onEvent,
        modifier = modifier,
        onSkip = onSkip,
        onBack = onBack,
    )
}

/** Разделено ради превью: сюда не попадает ни Hilt, ни навигация. */
@Composable
fun RoleContent(
    state: RoleState,
    onEvent: (RoleEvent) -> Unit,
    modifier: Modifier = Modifier,
    onSkip: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    OnboardingStep(
        title = stringResource(R.string.role_title),
        modifier = modifier,
        subtitle = stringResource(R.string.role_subtitle),
        onBack = onBack,
        footer = {
            MahallaButton(
                text = stringResource(R.string.action_continue),
                onClick = { onEvent(RoleEvent.ContinueClicked) },
                state = ButtonState(enabled = state.canContinue, loading = state.busy),
            )
            if (onSkip != null) {
                MahallaButton(
                    text = stringResource(R.string.role_skip),
                    onClick = onSkip,
                    variant = MahallaButtonVariant.Ghost,
                    state = ButtonState(enabled = !state.busy),
                )
            }
        },
    ) {
        MahallaChoiceCard(
            title = stringResource(R.string.role_customer_title),
            selected = state.selected == UserRole.Customer,
            onClick = { onEvent(RoleEvent.RoleSelected(UserRole.Customer)) },
            description = stringResource(R.string.role_customer_description),
            icon = Icons.Outlined.ShoppingBag,
            // Отметка только у заполненной анкеты: у продавца её нет — заявку
            // на заведение подтверждает модерация, а не запись в настройках.
            note = stringResource(R.string.role_form_filled).takeIf { state.customerFilled },
            enabled = !state.busy,
        )
        MahallaChoiceCard(
            title = stringResource(R.string.role_provider_title),
            selected = state.selected == UserRole.Provider,
            onClick = { onEvent(RoleEvent.RoleSelected(UserRole.Provider)) },
            description = stringResource(R.string.role_provider_description),
            icon = Icons.Outlined.Storefront,
            enabled = !state.busy,
        )
    }
}

@ThemeLanguagePreviews
@Composable
private fun RoleScreenPreview() {
    PreviewSurface {
        RoleContent(
            state = RoleState(
                selected = UserRole.Customer,
                saved = UserRole.Customer,
                customerFilled = true,
            ),
            onEvent = {},
            onSkip = {},
        )
    }
}
