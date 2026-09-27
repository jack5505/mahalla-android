package uz.mahalla.feature.discovery.domain

/**
 * Какие плитки показывать (issue #378): правило одно на главную, фильтр поиска
 * и анкету продавца.
 *
 * Порядок — порядок кодов на входе (сервер сортирует по `sortOrder`), а не
 * порядок перечисления. Код без своей категории в приложении (`BAKERY`,
 * `SHOP`) пропускается: `PlaceCategory.Other` — не плитка, а место для
 * неизвестных значений. Два кода одной категории (`BARBER` и `FREELANCER` —
 * оба «мастера») дают одну плитку.
 */
object PlaceCategoryCatalog {

    /**
     * [enabledCodes] пусты — кэша ещё нет (первый запуск без сети), и главная
     * показывает прежний зашитый набор [PlaceCategory.selectable]. Непустой
     * список, в котором нет ни одного известного кода, честно даёт пустой
     * результат: так решил дашборд.
     */
    fun resolve(enabledCodes: List<String>): List<PlaceCategory> {
        if (enabledCodes.isEmpty()) return PlaceCategory.selectable
        return enabledCodes
            .map(PlaceCategory::fromApi)
            .filter { it != PlaceCategory.Other }
            .distinct()
    }
}
