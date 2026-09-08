package uz.mahalla.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import uz.mahalla.R
import uz.mahalla.core.ui.preview.PreviewSurface
import uz.mahalla.core.ui.preview.ThemeLanguagePreviews
import uz.mahalla.ui.theme.LocalMahallaColors
import uz.mahalla.ui.theme.Spacing

/**
 * Ввод оценки звёздами (issue #76).
 *
 * Звёзды — группа выбора (`selectableGroup` + роль `RadioButton`), а не пять
 * независимых кнопок: TalkBack должен сообщать «3 из 5 выбрано», а не читать
 * пять безымянных картинок. Подпись каждой звезды приходит строкой с числом —
 * «четыре звезды» вслух понятнее, чем «звезда, звезда, звезда, звезда».
 *
 * Цвет — не единственный носитель смысла (2.4): выбранная звезда заливается
 * целиком, невыбранная остаётся контуром.
 */
@Composable
fun MahallaRatingInput(
    rating: Int,
    onRatingChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalMahallaColors.current
    Row(
        modifier = modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.item / 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (value in 1..MAX_RATING) {
            val selected = value <= rating
            Icon(
                imageVector = if (selected) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = pluralStringResource(
                    R.plurals.rating_star_description,
                    value,
                    value,
                ),
                modifier = Modifier
                    .size(MahallaComponentDefaults.ratingStarMinSize)
                    .selectable(
                        // «Выбрана» вся звезда до оценки включительно: человек
                        // ставит «четыре звезды», а не четвёртую звезду.
                        selected = selected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onRatingChange(value) },
                    ),
                tint = if (selected) colors.accent else colors.fgMuted,
            )
        }
    }
}

/** Столько звёзд принимает бэкенд (`@Max(5)`), и столько же рисует выдача. */
const val MAX_RATING: Int = 5

@ThemeLanguagePreviews
@Composable
private fun RatingInputPreview() {
    PreviewSurface {
        MahallaRatingInput(rating = 0, onRatingChange = {})
        MahallaRatingInput(rating = 3, onRatingChange = {})
        MahallaRatingInput(rating = MAX_RATING, onRatingChange = {}, enabled = false)
    }
}
