package uz.mahalla.feature.onboarding.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationCity
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
import uz.mahalla.core.ui.components.MahallaCard
import uz.mahalla.core.ui.components.MahallaListItem
import uz.mahalla.core.ui.components.SectionHeader
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.ui.theme.Spacing

/**
 * Геолокация (3.6): объяснение, запрос разрешения и выбор города руками, если
 * разрешение не дали.
 */
@Composable
fun GeoScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GeoViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        // Точность выбирает пользователь: для «рядом со мной» достаточно
        // приблизительных координат, поэтому просим обе и радуемся любой.
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        viewModel.onEvent(GeoEvent.PermissionResult(granted.values.any { it }))
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                GeoEffect.RequestLocationPermission -> permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ),
                )

                GeoEffect.Finished -> onFinished()
            }
        }
    }

    GeoContent(state = state, onEvent = viewModel::onEvent, modifier = modifier)
}

@Composable
private fun GeoContent(
    state: GeoState,
    onEvent: (GeoEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    OnboardingStep(
        title = stringResource(R.string.onboarding_geo_title),
        modifier = modifier,
        subtitle = stringResource(R.string.onboarding_geo_subtitle),
        footer = {
            if (state.stage == GeoStage.Explain) {
                MahallaButton(
                    text = stringResource(R.string.onboarding_geo_action),
                    onClick = { onEvent(GeoEvent.AllowRequested) },
                    state = ButtonState(enabled = !state.busy),
                )
                MahallaButton(
                    text = stringResource(R.string.onboarding_geo_manual),
                    onClick = { onEvent(GeoEvent.ChooseCityRequested) },
                    variant = MahallaButtonVariant.Ghost,
                    state = ButtonState(enabled = !state.busy),
                )
            }
        },
    ) {
        if (state.stage == GeoStage.CityPicker) {
            if (state.permissionDenied) {
                OnboardingError(stringResource(R.string.onboarding_geo_denied))
            }
            SectionHeader(title = stringResource(R.string.onboarding_geo_city_title))
            MahallaCard {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.item / 2)) {
                    state.cities.forEach { city ->
                        MahallaListItem(
                            title = stringResource(city.labelRes()),
                            leadingIcon = Icons.Outlined.LocationCity,
                            onClick = { onEvent(GeoEvent.CitySelected(city)) },
                        )
                    }
                }
            }
        }
    }
}

@ThemeLanguagePreviews
@Composable
private fun GeoScreenPreview() {
    PreviewSurface {
        GeoContent(
            state = GeoState(stage = GeoStage.CityPicker, permissionDenied = true),
            onEvent = {},
        )
    }
}
