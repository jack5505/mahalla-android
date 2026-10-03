package uz.mahalla.feature.business.domain

import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.role.domain.MyPlace
import uz.mahalla.feature.role.domain.PlaceModerationStatus
import uz.mahalla.feature.role.domain.PlaceStaffRole

/**
 * Разделы бизнес-панели (эпик #16).
 *
 * Раздела «дашборд» здесь нет намеренно: метрики — это сама панель, а не
 * пункт внутри неё, и запретить их, оставив панель открытой, было бы нечего
 * показывать.
 */
enum class BusinessSection {
    /** Живая очередь: принять, вызвать, завершить, отказать. */
    Queue,

    /** Входящие заказы и смена их статуса. */
    Orders,

    /** Меню, стоп-лист, новые позиции. */
    Menu,

    /**
     * Баланс бизнес-кошелька, история начислений и заявка на вывод (issue
     * #290). В отличие от остальных разделов не зависит от категории —
     * `wallet/business` общий для всех заведений владельца, а не привязан к
     * конкретному, поэтому раздел есть у любой категории.
     */
    Earnings,
}

/**
 * Право человека на бизнес-панель конкретного заведения (эпик #16).
 *
 * **Витрина клиента и панель бизнеса разделены здесь, а не в навигации.**
 * Единственный источник правды о доступе — `GET places/my`: бэкенд возвращает
 * только те заведения, в которых человек владелец, управляющий или сотрудник,
 * вместе с ролью (`Mine.role`). Заведения нет в этом списке — панели нет
 * вовсе, и это проверяется до открытия экрана, а не пунктом меню, который
 * можно обойти deep link'ом.
 *
 * Клиентская проверка при этом **не заменяет серверную**: последнее слово за
 * бэкендом, и его отказ экран показывает текстом (issue #34). Смысл проверки в
 * другом — не показывать кнопку, которая гарантированно ответит «нельзя».
 *
 * @param category решает, какие разделы вообще существуют у заведения, —
 * см. [sections].
 * @param role кем человек числится в заведении ([PlaceStaffRole]).
 * @param status решение модерации: у заявки `PENDING` карточки в каталоге ещё
 * нет, значит нет ни заказов, ни очереди.
 */
data class BusinessAccess(
    val placeId: String,
    val placeName: String,
    val category: PlaceCategory,
    val role: PlaceStaffRole,
    val status: PlaceModerationStatus,
    val isAvailable: Boolean = false,
) {

    /**
     * Заведение работает: модерация пропустила его, и в каталоге оно есть.
     *
     * До этого момента панель открыть можно (владелец приходит смотреть, что с
     * заявкой), но разделы пусты по построению — заказать в неопубликованном
     * заведении никто не может.
     */
    val isOperational: Boolean get() = status == PlaceModerationStatus.Active

    /**
     * Разделы, которые есть у **этого** заведения.
     *
     * Набор задаёт категория, а не роль: очередь живёт в
     * `walkin/barber/dashboard`, заказы и меню — в `food/places/{id}/...`,
     * заказы одежды — в `fashion/stores/{id}/orders` (issue #187). Показать
     * парикмахерской «входящие заказы» значило бы позвать чужую ручку за
     * чужое заведение и получить отказ вместо списка.
     *
     * У «Одежды» меню нет вовсе: витрина и товары — отдельная задача (#188,
     * #280), здесь только заказы.
     *
     * Сотруднику ([PlaceStaffRole.Staff]) меню не редактируется: стоп-лист и
     * цены — решение владельца. [PlaceStaffRole.Unknown] считается владельцем:
     * все поля `Mine` необязательны (issue #94), и молчание сервера о роли не
     * повод запереть человека в его собственном заведении — отказать всё равно
     * успеет бэкенд.
     */
    val sections: List<BusinessSection>
        get() = buildList {
            when (category) {
                PlaceCategory.Food -> {
                    add(BusinessSection.Orders)
                    if (canManageMenu) add(BusinessSection.Menu)
                }

                PlaceCategory.Fashion -> add(BusinessSection.Orders)

                PlaceCategory.Master -> add(BusinessSection.Queue)

                PlaceCategory.Pharmacy, PlaceCategory.Hospital, PlaceCategory.Cinema,
                PlaceCategory.Playground, PlaceCategory.Other,
                -> Unit
            }
            // Деньги — решение владельца, не рядового сотрудника (то же
            // правило, что у canManageMenu).
            if (role != PlaceStaffRole.Staff) add(BusinessSection.Earnings)
        }

    /** Стоп-лист и новые позиции — не работа рядового сотрудника. */
    val canManageMenu: Boolean get() = role != PlaceStaffRole.Staff

    /**
     * «Пауза» из задачи 12.2 — это `PUT places/{id}/availability`, то есть тот
     * же переключатель «открыто сейчас», что и в «моих заведениях» (issue
     * #94). Правило доступа поэтому одно и то же: действие владельца, и только
     * у опубликованного заведения — закрывать на обед то, чего в каталоге нет,
     * незачем.
     */
    val canPause: Boolean get() = isOperational && role != PlaceStaffRole.Staff

    /**
     * Открыт ли раздел прямо сейчас: и существует у заведения, и разрешён.
     *
     * «Заработок» — исключение из требования [isOperational]: бизнес-кошелёк
     * общий для всех заведений владельца, а не для этого конкретного, и уже
     * заработанные деньги не становятся недоступны из-за модерации именно
     * этой карточки (issue #290) — иначе кнопка на панели была бы нажимаемой
     * на вид и мёртвой по факту.
     */
    fun canOpen(section: BusinessSection): Boolean = when (section) {
        BusinessSection.Earnings -> section in sections
        else -> isOperational && section in sections
    }

    companion object {
        /**
         * Заведения нет среди «моих» — панели нет. Код отдаётся как
         * `ApiError.Business`, чтобы экран показал внятный текст, а не
         * «технический сбой»: чаще всего это не сбой, а чужая ссылка.
         */
        const val NO_ACCESS_CODE = "BUSINESS_NO_ACCESS"

        fun from(place: MyPlace): BusinessAccess = BusinessAccess(
            placeId = place.id,
            placeName = place.name,
            category = place.category,
            role = place.staffRole,
            status = place.status,
            isAvailable = place.isAvailable,
        )
    }
}
