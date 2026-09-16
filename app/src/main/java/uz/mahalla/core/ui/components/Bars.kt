package uz.mahalla.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uz.mahalla.R
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.ui.theme.FocusTitleHeader
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing
import uz.mahalla.ui.theme.TabularNums

/**
 * Верхняя панель экрана. Заголовок помечен `heading()` — с ним TalkBack
 * начинает обход экрана с названия, а не с кнопки «назад».
 *
 * Шапка таба по макету («Шапка (общая)»): круглый знак M, над заголовком
 * кикер, справа мета. Экраны-детали с «назад» этим не пользуются — у них
 * заголовок либо в панели, либо в теле.
 *
 * @param brandMark знак M слева от заголовка — только на четырёх табах.
 * @param kicker подпись над заголовком (Label). Опциональна: честного текста
 * для неё есть не у каждого таба.
 * @param meta подпись справа (Label, tnum) — «Mahalla+ до 12 окт» и подобное.
 * Ставится перед [actions], чтобы не мешать кнопкам.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MahallaTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    brandMark: Boolean = false,
    kicker: String? = null,
    meta: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val backLabel = stringResource(R.string.action_back)
    TopAppBar(
        title = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.item),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (brandMark) BrandMark()
                Column {
                    if (kicker != null) {
                        Text(
                            text = kicker,
                            style = MaterialTheme.typography.labelLarge,
                            color = LocalMahallaColors.current.fgMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Пустой заголовок — это не заголовок: на экранах, где
                    // название переехало в тело (карточка места, шаги
                    // онбординга), TalkBack иначе объявляет безымянный
                    // заголовок перед кнопкой «назад».
                    if (title.isNotBlank()) {
                        Text(
                            text = title,
                            modifier = Modifier.semantics { heading() },
                            style = if (brandMark) {
                                FocusTitleHeader
                            } else {
                                MaterialTheme.typography.headlineSmall
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
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
        actions = {
            if (meta != null) {
                Text(
                    text = meta,
                    modifier = Modifier.padding(end = Spacing.gutter),
                    style = MaterialTheme.typography.labelLarge.merge(TabularNums),
                    color = LocalMahallaColors.current.fgMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            actions()
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
            actionIconContentColor = MaterialTheme.colorScheme.onBackground,
        ),
    )
}

/**
 * Знак M — круг 38dp цветом `primary` с белой буквой (макет: «Шапка (общая)»).
 * Буква — литерал, а не ресурс: это глиф бренда, он один на все языки, как и
 * на приветственном экране. Для TalkBack знак пуст: рядом стоит заголовок.
 */
@Composable
private fun BrandMark(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(BrandMarkSize)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "M",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

private val BrandMarkSize = 38.dp

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
    val scheme = MaterialTheme.colorScheme
    NavigationBar(
        modifier = modifier,
        // surfaceVariant = surfaceContainer из design_handoff_mahalla_focus/README.md.
        containerColor = scheme.surfaceVariant,
        contentColor = scheme.onSurface,
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
                    selectedIconColor = scheme.onPrimaryContainer,
                    selectedTextColor = scheme.onPrimaryContainer,
                    indicatorColor = scheme.primaryContainer,
                    unselectedIconColor = scheme.onSurfaceVariant,
                    unselectedTextColor = scheme.onSurfaceVariant,
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
        MahallaTopBar(
            title = stringResource(R.string.wallet_title),
            brandMark = true,
            kicker = "Hamyon",
            meta = "Mahalla+ · 12.10",
        )
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
