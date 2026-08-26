package uz.mahalla.feature.map.canvas

/**
 * Что изменилось в наборе маркеров между кадрами (эпик 4.2).
 *
 * Кластеризованная коллекция MapKit пересобирается целиком (`clusterPlacemarks`
 * после каждой правки перестраивает дерево кластеров), поэтому полотну важен не
 * список правок, а ответ на вопрос «пересобирать ли вообще»: рекомпозиция
 * приходит на любое изменение состояния экрана — на смену текста в шите, на
 * появление снекбара, — и пересборка карты на каждой из них выглядит как
 * моргание маркеров.
 */
data class MarkerDiff(
    val added: List<MapMarkerUi>,
    val removed: List<String>,
    val changed: List<MapMarkerUi>,
) {
    /** Ничего не поменялось — карту не трогаем. */
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()

    /**
     * Достаточно ли перерисовать иконки вместо полной пересборки: состав
     * маркеров тот же, поменялось только выделение или подпись. Это частый
     * случай — тап по маркеру, — и кластеры от него не меняются.
     */
    val isAppearanceOnly: Boolean get() = added.isEmpty() && removed.isEmpty() && changed.isNotEmpty()

    companion object {
        val EMPTY = MarkerDiff(emptyList(), emptyList(), emptyList())
    }
}

/**
 * Порядок маркеров значения не имеет: на карте они лежат по координатам, а не
 * списком, и перестановка выдачи не должна пересобирать полотно.
 */
fun diffMarkers(previous: List<MapMarkerUi>, next: List<MapMarkerUi>): MarkerDiff {
    if (previous.isEmpty() && next.isEmpty()) return MarkerDiff.EMPTY

    val previousById = previous.associateBy { it.id }
    val nextById = next.associateBy { it.id }

    val added = next.filter { it.id !in previousById }
    val removed = previous.map { it.id }.filter { it !in nextById }
    val changed = next.filter { marker ->
        val before = previousById[marker.id]
        before != null && before != marker
    }

    return MarkerDiff(added = added, removed = removed, changed = changed)
}
