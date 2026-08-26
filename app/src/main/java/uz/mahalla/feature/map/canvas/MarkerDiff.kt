package uz.mahalla.feature.map.canvas

/**
 * Что изменилось в наборе маркеров между кадрами (эпик 4.2).
 *
 * Дифф нужен по двум причинам. Первая: рекомпозиция приходит на любое изменение
 * состояния экрана — на смену текста в шите, на появление снекбара, — а
 * пересборка карты на каждой из них выглядит как моргание маркеров. Вторая:
 * `clusterPlacemarks` перестраивает дерево кластеров целиком, и звать его на
 * смену выделения одного маркера незачем — состав кучек от неё не меняется.
 */
data class MarkerDiff(
    val added: List<MapMarkerUi>,
    val removed: List<String>,
    /**
     * Маркер остался, но переехал. Отдельно от [changed]: новые координаты
     * меняют состав кластеров, то есть быстрым путём такое не обновить.
     */
    val moved: List<MapMarkerUi>,
    /** Маркер на месте, изменился только его вид (выделение, подпись). */
    val changed: List<MapMarkerUi>,
) {
    /** Ничего не поменялось — карту не трогаем. */
    val isEmpty: Boolean
        get() = added.isEmpty() && removed.isEmpty() && moved.isEmpty() && changed.isEmpty()

    /**
     * Достаточно ли перерисовать иконки вместо полной пересборки: состав и
     * координаты маркеров те же, поменялось только выделение или подпись. Это
     * частый случай — тап по маркеру, — и кластеры от него не меняются.
     */
    val isAppearanceOnly: Boolean
        get() = added.isEmpty() && removed.isEmpty() && moved.isEmpty() && changed.isNotEmpty()

    companion object {
        val EMPTY = MarkerDiff(emptyList(), emptyList(), emptyList(), emptyList())
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

    val moved = mutableListOf<MapMarkerUi>()
    val changed = mutableListOf<MapMarkerUi>()
    next.forEach { marker ->
        val before = previousById[marker.id] ?: return@forEach
        when {
            before.point != marker.point -> moved += marker
            before != marker -> changed += marker
        }
    }

    return MarkerDiff(added = added, removed = removed, moved = moved, changed = changed)
}
