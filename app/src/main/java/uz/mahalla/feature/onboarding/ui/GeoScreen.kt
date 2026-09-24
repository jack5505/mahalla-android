package uz.mahalla.feature.onboarding.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationCity
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uz.mahalla.R
import uz.mahalla.core.ui.components.ButtonState
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaComponentDefaults
import uz.mahalla.core.ui.components.SectionHeader
import uz.mahalla.core.ui.permission.canRequestPermissionAgain
import uz.mahalla.core.ui.permission.findActivity
import uz.mahalla.core.ui.permission.openAppSettings
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
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        // Точность выбирает пользователь: для «рядом со мной» достаточно
        // приблизительных координат, поэтому просим обе и радуемся любой.
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val anyGranted = granted.values.any { it }
        // shouldShowRequestPermissionRationale — только после того, как диалог
        // уже был показан: до этого он тоже вернул бы false и не отличался бы
        // от «Больше не спрашивать».
        val permanentlyDenied = !anyGranted &&
            context.findActivity()?.canRequestPermissionAgain(GEO_PERMISSIONS) == false
        viewModel.onEvent(
            GeoEvent.PermissionResult(granted = anyGranted, permanentlyDenied = permanentlyDenied),
        )
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                GeoEffect.RequestLocationPermission -> permissionLauncher.launch(GEO_PERMISSIONS)

                GeoEffect.Finished -> onFinished()
            }
        }
    }

    GeoContent(
        state = state,
        onEvent = viewModel::onEvent,
        onOpenSettings = { context.openAppSettings() },
        modifier = modifier,
    )
}

private val GEO_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.ACCESS_FINE_LOCATION,
)

@Composable
private fun GeoContent(
    state: GeoState,
    onEvent: (GeoEvent) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
) {
    OnboardingStep(
        title = stringResource(R.string.onboarding_geo_title),
        modifier = modifier,
        subtitle = stringResource(R.string.onboarding_geo_subtitle),
        stepLabel = OnboardingStepNumber.Geo.label(),
        footer = {
            when (state.stage) {
                GeoStage.Explain -> {
                    MahallaButton(
                        text = stringResource(R.string.onboarding_geo_action),
                        onClick = { onEvent(GeoEvent.AllowRequested) },
                        variant = MahallaButtonVariant.Secondary,
                        state = ButtonState(enabled = !state.busy),
                        icon = Icons.Outlined.MyLocation,
                    )
                    MahallaButton(
                        text = stringResource(R.string.onboarding_geo_manual),
                        onClick = { onEvent(GeoEvent.ChooseCityRequested) },
                        variant = MahallaButtonVariant.Ghost,
                        state = ButtonState(enabled = !state.busy),
                    )
                }

                // Кнопка неактивна, пока город не отмечен (макет 0d): так
                // видно, что от человека ещё чего-то ждут.
                GeoStage.CityPicker -> MahallaButton(
                    text = stringResource(R.string.action_continue),
                    onClick = { onEvent(GeoEvent.ContinueClicked) },
                    state = ButtonState(
                        enabled = state.selectedCity != null,
                        loading = state.busy,
                    ),
                )
            }
        },
    ) {
        if (state.stage == GeoStage.CityPicker) {
            if (state.permissionDenied) {
                OnboardingError(stringResource(R.string.onboarding_geo_denied))
                // «Больше не спрашивать»: системный диалог больше не покажется,
                // единственный путь назад — настройки приложения (issue #348).
                if (state.permissionPermanentlyDenied) {
                    MahallaButton(
                        text = stringResource(R.string.notification_permission_open_settings),
                        onClick = onOpenSettings,
                        variant = MahallaButtonVariant.Ghost,
                        fillWidth = false,
                    )
                }
            }
            SectionHeader(title = stringResource(R.string.onboarding_geo_city_title))
            Column(
                modifier = Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(Spacing.item),
            ) {
                state.cities.forEach { city ->
                    CityRow(
                        label = stringResource(city.labelRes()),
                        selected = city == state.selectedCity,
                        enabled = !state.busy,
                        onClick = { onEvent(GeoEvent.CitySelected(city)) },
                    )
                }
            }
        }
    }
}

/**
 * Строка выбора города (макет 0d): отмеченная — заливкой и обводкой.
 *
 * Роль `RadioButton` и `selectableGroup` вокруг — TalkBack объявляет «выбрано
 * 2 из 8», а не восемь одинаковых кнопок; цвет тут не единственный носитель
 * смысла.
 */
@Composable
private fun CityRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MahallaComponentDefaults.listItemMinHeight)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        border = BorderStroke(
            MahallaComponentDefaults.borderWidth,
            if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.card, vertical = Spacing.item),
            horizontalArrangement = Arrangement.spacedBy(Spacing.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.LocationCity,
                contentDescription = null,
                modifier = Modifier.size(MahallaComponentDefaults.cardIconSize),
            )
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
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
