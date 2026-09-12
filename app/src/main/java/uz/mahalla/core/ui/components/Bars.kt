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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
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
 * Кегль подписи таба — 9 / SemiBold из ТЗ (`design/android/TZ-ANDROID.md`,
 * компонент `navbar`: «подпись 9/600 `ellipsis`»). По умолчанию M3 подставил бы
 * `labelMedium` 12sp — в `MahallaTypography` он не задан, поэтому подпись таба
 * заодно уезжала в дефолтную семью вместо Inter.
 *
 * Разница не косметическая. Бокс подписи в M3 — это слот item'а минус
 * `NavigationBarItemHorizontalPadding` (8 dp): на базовых 393 dp это
 * `(393 − 3×8) / 4 − 8 = 84.25 dp`, на 360 dp — 76 dp. При 12sp с трекингом
 * 0.5sp «Активности» занимают 72 dp и не влезают в бокс уже при системном
 * fontScale 1.15 на 360 dp и при 1.3 на 393 dp (93.6 dp) — то есть обрезаются
 * ровно там, где шрифт увеличивают. При 9/600 те же строки дают 53 dp
 * (68.9 dp при 1.3) и ложатся в макет: в Figma-экспортах самая длинная
 * подпись, «Buyurtmalar», — 53.5 dp.
 */
private val NavLabelTextStyle: TextStyle
    @Composable get() = MaterialTheme.typography.labelLarge.copy(
        fontSize = 9.sp,
        letterSpacing = 0.sp,
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
                label = {
                    Text(
                        text = item.label,
                        style = NavLabelTextStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
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
