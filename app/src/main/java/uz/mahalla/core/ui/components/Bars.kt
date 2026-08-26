package uz.mahalla.core.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import uz.mahalla.R
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.ui.theme.LocalMahallaColors

/**
 * Верхняя панель экрана. Заголовок помечен `heading()` — с ним TalkBack
 * начинает обход экрана с названия, а не с кнопки «назад».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MahallaTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val backLabel = stringResource(R.string.action_back)
    TopAppBar(
        title = {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = modifier,
        navigationIcon = {
            if (onBack != null) {
                MahallaIconButton(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = backLabel,
                    onClick = onBack,
                )
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
            actionIconContentColor = MaterialTheme.colorScheme.onBackground,
        ),
    )
}

@Immutable
data class NavItemUi(
    val id: String,
    val label: String,
    val icon: ImageVector,
)

/**
 * Нижняя навигация. Подпись показывается всегда: иконка без текста хуже
 * читается и при крупном шрифте, и в TalkBack.
 */
@Composable
fun MahallaBottomNav(
    items: List<NavItemUi>,
    selectedId: String?,
    onSelect: (NavItemUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val mahalla = LocalMahallaColors.current
    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        items.forEach { item ->
            NavigationBarItem(
                selected = item.id == selectedId,
                onClick = { onSelect(item) },
                icon = {
                    // Подпись рядом уже несёт смысл — иконку TalkBack пропускает.
                    Icon(imageVector = item.icon, contentDescription = null)
                },
                label = { Text(text = item.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                alwaysShowLabel = true,
                modifier = Modifier.heightIn(min = MahallaComponentDefaults.navItemMinHeight),
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = mahalla.accentSoft,
                    unselectedIconColor = mahalla.fgMuted,
                    unselectedTextColor = mahalla.fgMuted,
                ),
            )
        }
    }
}

@ThemeLanguagePreviews
@Composable
private fun MahallaBarsPreview() {
    PreviewSurface {
        MahallaTopBar(title = stringResource(R.string.place_title), onBack = {})
        MahallaBottomNav(
            items = listOf(
                NavItemUi("discovery", stringResource(R.string.nav_discovery), Icons.Outlined.Home),
                NavItemUi("profile", stringResource(R.string.nav_profile), Icons.Outlined.Person),
            ),
            selectedId = "discovery",
            onSelect = {},
        )
    }
}
