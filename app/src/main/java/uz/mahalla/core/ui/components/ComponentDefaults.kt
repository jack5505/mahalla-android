package uz.mahalla.core.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Размеры UI-кита (эпик 2). Вынесены из composable-функций отдельным объектом
 * по двум причинам:
 *
 * 1. доступность (2.4) проверяется обычным unit-тестом — `TouchTargetTest`
 *    сверяет каждую цель нажатия с 48dp, не поднимая Compose;
 * 2. визуальная высота по макету (`Spacing.buttonHeight` = 44dp) меньше
 *    минимальной цели нажатия, поэтому «сколько занимает» и «куда можно
 *    попасть пальцем» — разные величины и живут раздельно.
 */
object MahallaComponentDefaults {

    /** Минимальная цель нажатия — WCAG 2.5.5 / Material accessibility. */
    val minTouchTarget: Dp = 48.dp

    val buttonMinHeight: Dp = minTouchTarget
    val fieldMinHeight: Dp = 56.dp
    val chipMinTouchHeight: Dp = minTouchTarget
    val listItemMinHeight: Dp = 56.dp
    val iconButtonSize: Dp = minTouchTarget
    val switchRowMinHeight: Dp = minTouchTarget
    val checkboxRowMinHeight: Dp = minTouchTarget
    val segmentMinHeight: Dp = minTouchTarget
    val navItemMinHeight: Dp = minTouchTarget

    /** Ячейка OTP: ширина меньше 48dp, но строка целиком выше цели нажатия. */
    val otpCellWidth: Dp = 44.dp
    val otpCellHeight: Dp = minTouchTarget

    // --- Discovery (эпик 4) ---

    /** Плитка категории на главной: квадратная, иконка над подписью. */
    val categoryTileMinHeight: Dp = 88.dp

    /** Строка-кнопка «Поиск» на главной — открывает экран поиска. */
    val searchEntryMinHeight: Dp = fieldMinHeight

    /** Метка на карте: тапают по ней пальцем, а не курсором. */
    val mapMarkerMinSize: Dp = minTouchTarget

    // --- Вертикаль «Еда» (эпик 5) ---

    /** Степпер количества в корзине и в меню. */
    val stepperMinHeight: Dp = minTouchTarget

    /** Поле числа между «−» и «+»: ширина под двузначное количество. */
    val stepperValueMinWidth: Dp = 32.dp

    /** Строка позиции меню: две строки текста плюс цена. */
    val menuItemMinHeight: Dp = 64.dp

    // --- Отзывы (issue #76) ---

    /**
     * Звезда в форме отзыва. Пять целей в ряд — самое тесное место экрана,
     * поэтому размер именно цели нажатия, а не иконки: сама звезда рисуется
     * внутри.
     */
    val ratingStarMinSize: Dp = minTouchTarget

    // --- Анкеты покупателя и продавца (issue #84) ---

    /**
     * Карточка-выбор роли: заголовок, объяснение в две строки и радиокнопка.
     * Выше обычной строки списка — объяснение здесь и есть смысл карточки.
     */
    val choiceCardMinHeight: Dp = 72.dp

    /** Больше 99 порций одного блюда — опечатка (`CartCalculator.MAX_QUANTITY`). */
    const val maxStepperQuantity: Int = 99

    // --- Картинки (issue #60) ---

    /** Миниатюра в строке списка: карточка места, позиция меню. */
    val thumbnailSize: Dp = 64.dp

    /** Аватар автора отзыва. */
    val avatarSize: Dp = 40.dp

    /** Полоса фотографий на карточке места. */
    val galleryImageHeight: Dp = 160.dp

    /** Ширина фотографии в галерее: следующая видна краем, значит листается. */
    val galleryImageWidth: Dp = 240.dp

    val progressIndicatorSize: Dp = 18.dp
    val progressStrokeWidth: Dp = 2.dp
    val cardIconSize: Dp = 20.dp
    val stateIconSize: Dp = 40.dp
    val skeletonLineHeight: Dp = 12.dp
    val borderWidth: Dp = 1.dp

    /**
     * Все цели нажатия кита — для теста доступности. Любой новый интерактивный
     * компонент добавляется сюда же, иначе его никто не проверит.
     */
    val touchTargets: Map<String, Dp> = mapOf(
        "button" to buttonMinHeight,
        "field" to fieldMinHeight,
        "chip" to chipMinTouchHeight,
        "listItem" to listItemMinHeight,
        "iconButton" to iconButtonSize,
        "switchRow" to switchRowMinHeight,
        "checkboxRow" to checkboxRowMinHeight,
        "segment" to segmentMinHeight,
        "navItem" to navItemMinHeight,
        "otpCell" to otpCellHeight,
        "categoryTile" to categoryTileMinHeight,
        "searchEntry" to searchEntryMinHeight,
        "mapMarker" to mapMarkerMinSize,
        "stepper" to stepperMinHeight,
        "menuItem" to menuItemMinHeight,
        "ratingStar" to ratingStarMinSize,
        "choiceCard" to choiceCardMinHeight,
    )
}
