package uz.mahalla.feature.discovery.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import uz.mahalla.R
import uz.mahalla.core.ui.components.MahallaComponentDefaults
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing

/**
 * Плитка категорий главной (эпик 4.1).
 *
 * Ряды собираются вручную через [chunked], а не `LazyVerticalGrid`: плитка
 * живёт внутри вертикального `LazyColumn`, и вложенная ленивая сетка по той
 * же оси падает с «Nested scroll of the same direction».
 */
@Composable
fun CategoryGrid(
    categories: List<PlaceCategory>,
    onCategoryClick: (PlaceCategory) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Плитка «Все» первой (макет 1a): каталог без фильтра. `null` — плитки нет
     * (в поиске, где категория уже выбрана, она была бы лишней).
     */
    onAllClick: (() -> Unit)? = null,
    columns: Int = DEFAULT_COLUMNS,
) {
    val tiles: List<Tile> = buildList {
        if (onAllClick != null) add(Tile(R.string.category_all, Icons.Outlined.Apps, onAllClick))
        categories.forEach { add(Tile(it.labelRes, it.icon) { onCategoryClick(it) }) }
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        tiles.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.item)) {
                row.forEach { tile ->
                    CategoryTile(
                        labelRes = tile.labelRes,
                        icon = tile.icon,
                        onClick = tile.onClick,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Добивка пустыми ячейками: без неё последний неполный ряд
                // растягивается на всю ширину и плитка выглядит сломанной.
                repeat(columns - row.size) {
                    Column(modifier = Modifier.weight(1f)) {}
                }
            }
        }
    }
}

/** Плитка сетки: категория каталога либо «Все». */
private data class Tile(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

@Composable
private fun CategoryTile(
    @StringRes labelRes: Int,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(labelRes)
    Surface(
        modifier = modifier
            .heightIn(min = MahallaComponentDefaults.categoryTileMinHeight)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {},
        shape = MaterialTheme.shapes.medium,
        // Плитка категории в макете — #f1ecf7 на фоне экрана #fdf8ff.
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        // Отступ плитки 12dp, а не 18: при четырёх колонках на 393dp плитка
        // ≈79dp, и с отступом карточки подписи не осталось бы места.
        Column(
            modifier = Modifier.padding(horizontal = Spacing.item / 2, vertical = Spacing.item),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.item / 2, Alignment.CenterVertically),
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = LocalMahallaColors.current.accentSoft,
            ) {
                Icon(
                    imageVector = icon,
                    // Подпись под иконкой уже названа — TalkBack не должен
                    // читать одно и то же дважды.
                    contentDescription = null,
                    modifier = Modifier
                        .padding(Spacing.item / 2)
                        .size(MahallaComponentDefaults.cardIconSize),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                text = label,
                // Label S по макету (подписи категорий 10–10.5).
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Четыре колонки — как в макете 1a («Рядом с домом»). */
private const val DEFAULT_COLUMNS = 4

@ThemeLanguagePreviews
@Composable
private fun CategoryGridPreview() {
    PreviewSurface {
        CategoryGrid(categories = PlaceCategory.selectable, onCategoryClick = {}, onAllClick = {})
    }
}
