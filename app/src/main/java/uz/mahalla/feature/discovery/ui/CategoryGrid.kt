package uz.mahalla.feature.discovery.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    columns: Int = DEFAULT_COLUMNS,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.item),
    ) {
        categories.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.item)) {
                row.forEach { category ->
                    CategoryTile(
                        category = category,
                        onClick = { onCategoryClick(category) },
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

@Composable
private fun CategoryTile(
    category: PlaceCategory,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(category.labelRes)
    Surface(
        modifier = modifier
            .heightIn(min = MahallaComponentDefaults.categoryTileMinHeight)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {},
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.card),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.item / 2, Alignment.CenterVertically),
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = LocalMahallaColors.current.accentSoft,
            ) {
                Icon(
                    imageVector = category.icon,
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
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val DEFAULT_COLUMNS = 3

@ThemeLanguagePreviews
@Composable
private fun CategoryGridPreview() {
    PreviewSurface {
        CategoryGrid(categories = PlaceCategory.selectable, onCategoryClick = {})
    }
}
