package uz.mahalla.feature.map.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import uz.mahalla.R
import uz.mahalla.core.ui.components.MahallaButton
import uz.mahalla.core.ui.components.MahallaButtonVariant
import uz.mahalla.core.ui.components.MahallaIconButton
import uz.mahalla.ui.theme.Spacing

/**
 * Надстройка над полотном карты, общая для экрана карты (issue #65) и выбора
 * точки (issue #90).
 *
 * Вынесено сюда, потому что оба экрана рисуют поверх карты одно и то же:
 * узкую плашку с текстом и кнопки масштаба. Две копии разошлись бы при первой
 * же правке — а расхождение здесь видно как разные карты в одном приложении.
 */

/** Плашки лежат поверх карты — без тени они сливаются с тайлами. */
val MapOverlayElevation = Spacing.item / 4

@Composable
fun MapBannerSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = MapOverlayElevation,
        shadowElevation = MapOverlayElevation,
        content = content,
    )
}

@Composable
fun MapBannerRow(
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.padding(
            start = Spacing.card,
            end = if (actionLabel == null) Spacing.card else Spacing.item,
            top = Spacing.item,
            bottom = Spacing.item,
        ),
        horizontalArrangement = Arrangement.spacedBy(Spacing.item),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (actionLabel != null && onAction != null) {
            MahallaButton(
                text = actionLabel,
                onClick = onAction,
                variant = MahallaButtonVariant.Ghost,
                fillWidth = false,
            )
        }
    }
}

/**
 * Масштаб и «моё местоположение». Кнопки свои, а не встроенные в MapKit: у SDK
 * их нет вовсе, а размер цели нажатия и тема должны совпадать с остальным
 * приложением.
 */
@Composable
fun MapControls(
    isLocating: Boolean,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onMyLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = MapOverlayElevation,
        shadowElevation = MapOverlayElevation,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.item / 2)) {
            MahallaIconButton(
                icon = Icons.Outlined.Add,
                contentDescription = stringResource(R.string.map_zoom_in),
                onClick = onZoomIn,
            )
            MahallaIconButton(
                icon = Icons.Outlined.Remove,
                contentDescription = stringResource(R.string.map_zoom_out),
                onClick = onZoomOut,
            )
            MahallaIconButton(
                icon = Icons.Outlined.MyLocation,
                contentDescription = stringResource(R.string.map_my_location),
                onClick = onMyLocation,
                // Пока координаты ищутся, второй тап только запустил бы второй
                // запрос: MapKit отвечает не мгновенно.
                enabled = !isLocating,
            )
        }
    }
}

/**
 * Разрешение на геолокацию: грубых координат хватает и слою «моё
 * местоположение», и запросам к бэкенду, поэтому годится любое из двух.
 */
fun Context.hasLocationPermission(): Boolean =
    isPermissionGranted(Manifest.permission.ACCESS_COARSE_LOCATION) ||
        isPermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION)

/** Обе точности сразу: какую выдать, решает пользователь. */
val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.ACCESS_FINE_LOCATION,
)

private fun Context.isPermissionGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** Текст отказа геолокации: у обоих экранов он один и тот же. */
@Composable
fun locationNoticeText(notice: LocationNotice): String = stringResource(
    when (notice) {
        LocationNotice.PermissionDenied -> R.string.map_location_denied
        LocationNotice.Unavailable -> R.string.map_location_unavailable
    },
)
