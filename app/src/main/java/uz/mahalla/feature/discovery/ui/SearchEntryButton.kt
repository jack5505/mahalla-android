package uz.mahalla.feature.discovery.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import uz.mahalla.R
import uz.mahalla.core.ui.components.MahallaComponentDefaults
import uz.mahalla.core.ui.components.MahallaIconButton
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing

/**
 * Строка поиска на главной (эпик 4.1).
 *
 * Это кнопка, а не поле ввода: набор идёт на отдельном экране поиска, где
 * есть история и фильтры. Поле, которое выглядит как поле, но открывает
 * другой экран, — обычный источник недоумения, поэтому здесь явная
 * `Role.Button` для TalkBack.
 */
@Composable
fun SearchEntryButton(
    onClick: () -> Unit,
    onMapClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mapLabel = stringResource(R.string.discovery_open_map)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.item),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = MahallaComponentDefaults.searchEntryMinHeight)
                .semantics { role = Role.Button },
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
            contentColor = LocalMahallaColors.current.fgMuted,
            border = BorderStroke(
                MahallaComponentDefaults.borderWidth,
                MaterialTheme.colorScheme.outline,
            ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.card),
                horizontalArrangement = Arrangement.spacedBy(Spacing.item),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    modifier = Modifier.size(MahallaComponentDefaults.cardIconSize),
                )
                Text(
                    text = stringResource(R.string.search_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        MahallaIconButton(
            icon = Icons.Outlined.Map,
            contentDescription = mapLabel,
            onClick = onMapClick,
        )
    }
}

@ThemeLanguagePreviews
@Composable
private fun SearchEntryButtonPreview() {
    PreviewSurface {
        SearchEntryButton(onClick = {}, onMapClick = {})
    }
}
