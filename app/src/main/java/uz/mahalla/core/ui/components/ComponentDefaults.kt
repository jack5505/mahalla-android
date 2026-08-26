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
    )
}
