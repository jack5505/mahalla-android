package uz.mahalla.feature.discovery.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import uz.mahalla.R
import uz.mahalla.core.format.DistanceFormatter
import uz.mahalla.core.format.RatingFormatter
import uz.mahalla.core.ui.components.PlaceCardUi
import uz.mahalla.feature.discovery.domain.Place

/**
 * Домен → карточка UI-кита (эпик 2.2).
 *
 * Единственное место, где место превращается в карточку: и главная, и поиск,
 * и карта показывают одинаковые подписи расстояния и рейтинга.
 */
@Composable
fun Place.toCardUi(): PlaceCardUi = PlaceCardUi(
    id = id,
    title = name,
    category = stringResource(category.labelRes),
    ratingLabel = RatingFormatter.format(rating, reviewCount),
    distanceLabel = distanceLabel(distanceMeters),
    isOpen = isOpenNow,
)

/** `450 m` / `1,2 km` — число из форматтера, единица из ресурсов. */
@Composable
fun distanceLabel(meters: Int): String = stringResource(
    if (DistanceFormatter.isKilometers(meters)) {
        R.string.distance_kilometers
    } else {
        R.string.distance_meters
    },
    DistanceFormatter.value(meters),
)
