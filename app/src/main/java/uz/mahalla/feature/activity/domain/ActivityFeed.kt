package uz.mahalla.feature.activity.domain

import uz.mahalla.core.result.ApiFailure

/**
 * Ответ всех источников сразу (issue #73, задача T7).
 *
 * Возвращается **не** `ApiResult`, и это главное решение задачи: пять
 * независимых ручек не сводятся к одному «получилось / не получилось».
 * Если четыре источника ответили данными, а один — ошибкой, экран обязан
 * показать данные и отметить сбойный раздел, а не уйти в `Error` целиком.
 * Решение «а если не ответил вообще никто» принимает ViewModel — здесь для
 * этого есть [isTotalFailure].
 *
 * @param items активности всех ответивших источников, ещё не отфильтрованные
 * по вкладке: фильтр переключается без обращения к сети, иначе каждый тап по
 * «истории» стоил бы пяти запросов.
 * @param failures отказы по источникам — вместе с ответом сервера, чтобы
 * причину было видно текстом бэкенда (issue #34).
 * @param nextPages номер следующей страницы для тех источников, у которых она
 * есть. Пустая карта — догружать больше нечего. Курсор именно по источникам:
 * страницы у пяти ручек кончаются в разное время, и просить у исчерпанного
 * источника шестую страницу значит получать один и тот же хвост заново.
 * @param requested какие источники опрашивались. Нужен для [isTotalFailure]:
 * при догрузке спрашивают уже не всех, и «отказали все» там означает «все
 * двое», а не «все пять».
 */
data class ActivityFeed(
    val items: List<Activity> = emptyList(),
    val failures: Map<ActivitySource, ApiFailure> = emptyMap(),
    val nextPages: Map<ActivitySource, Int> = emptyMap(),
    val requested: Set<ActivitySource> = emptySet(),
) {

    /**
     * Не ответил ни один опрошенный источник. Только это — настоящая ошибка
     * экрана: так выглядит истёкшая сессия (401 у всех пяти) и отсутствие
     * сети, и показывать в этом случае «вы ещё ничего не заказывали» значит
     * врать.
     */
    val isTotalFailure: Boolean
        get() = items.isEmpty() && failures.isNotEmpty() && failures.size == requested.size

    /** Хотя бы один источник не ответил, но список всё равно есть. */
    val isPartial: Boolean get() = failures.isNotEmpty() && !isTotalFailure

    val hasMore: Boolean get() = nextPages.isNotEmpty()

    companion object {
        /**
         * Первая загрузка: нулевая страница у каждого источника. Считается по
         * перечислению, а не списком вручную — подключение новой вертикали не
         * должно требовать правки в двух местах.
         */
        val FIRST_PAGES: Map<ActivitySource, Int> =
            ActivitySource.entries.associateWith { 0 }
    }
}
