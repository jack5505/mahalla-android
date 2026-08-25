package uz.mahalla.feature.onboarding.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import uz.mahalla.R
import uz.mahalla.core.ui.components.ScreenAction
import uz.mahalla.core.ui.components.ScreenSkeleton

/**
 * Экраны онбординга: welcome → phone → otp → pin → biometric → geo.
 * Пока это скелеты — вёрстка по макету идёт эпиком 2, но переходы и
 * аргументы маршрутов настоящие.
 */

@Composable
fun WelcomeScreen(onContinue: () -> Unit, modifier: Modifier = Modifier) {
    ScreenSkeleton(
        title = stringResource(R.string.onboarding_welcome_title),
        modifier = modifier,
        subtitle = stringResource(R.string.onboarding_welcome_subtitle),
        actions = listOf(
            ScreenAction(stringResource(R.string.onboarding_welcome_action), onClick = onContinue),
        ),
    )
}

@Composable
fun OtpScreen(phone: String, onVerified: () -> Unit, modifier: Modifier = Modifier) {
    ScreenSkeleton(
        title = stringResource(R.string.onboarding_otp_title, phone),
        modifier = modifier,
        actions = listOf(
            ScreenAction(stringResource(R.string.onboarding_otp_action), onClick = onVerified),
        ),
    )
}

@Composable
fun PinScreen(onPinSet: () -> Unit, modifier: Modifier = Modifier) {
    ScreenSkeleton(
        title = stringResource(R.string.onboarding_pin_title),
        modifier = modifier,
        actions = listOf(
            ScreenAction(stringResource(R.string.onboarding_pin_action), onClick = onPinSet),
        ),
    )
}

@Composable
fun BiometricScreen(
    onEnabled: () -> Unit,
    onSkipped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenSkeleton(
        title = stringResource(R.string.onboarding_biometric_title),
        modifier = modifier,
        actions = listOf(
            ScreenAction(stringResource(R.string.onboarding_biometric_action), onClick = onEnabled),
            ScreenAction(
                label = stringResource(R.string.onboarding_biometric_skip),
                primary = false,
                onClick = onSkipped,
            ),
        ),
    )
}

@Composable
fun GeoScreen(onFinished: () -> Unit, modifier: Modifier = Modifier) {
    ScreenSkeleton(
        title = stringResource(R.string.onboarding_geo_title),
        modifier = modifier,
        subtitle = stringResource(R.string.onboarding_geo_subtitle),
        actions = listOf(
            ScreenAction(stringResource(R.string.onboarding_geo_action), onClick = onFinished),
        ),
    )
}
