package uz.mahalla.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import uz.mahalla.R

// Шрифт варианта B — Inter (SIL OFL), 4 начертания в res/font/.
private val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

// Значения — design/android/HANDOFF.md + DESIGN-SYSTEM.md §2.
val MahallaTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 23.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 21.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 14.sp,
    ),
)

/** Суммы и номера талонов — моноширинные цифры, иначе прыгают при обновлении. */
val TabularNums = TextStyle(fontFeatureSettings = "tnum")

/**
 * Крупные декоративные и «цифровые» стили редизайна «Focus»
 * (`design_handoff_mahalla_focus/README.md`, раздел «Типографика»).
 *
 * Не заведены как слоты `Typography()`: `titleMedium`/`labelLarge`/`labelSmall`
 * и т. д. уже используются кнопками, полями и карточками на всех 45 экранах
 * приложения — изменение их размера задело бы вертикали, которые этот
 * редизайн не трогает. Эти стили применяются точечно, только в
 * переработанных экранах (онбординг, главная, карточка места, талон,
 * кошелёк).
 */

/** Номер талона на карточке очереди — «A-15». Мержить с [TabularNums]. */
val FocusDisplayTicket = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.SemiBold,
    fontSize = 48.sp,
    lineHeight = 48.sp,
)

/** Баланс кошелька. Мержить с [TabularNums]. */
val FocusDisplayBalance = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.SemiBold,
    fontSize = 40.sp,
    lineHeight = 40.sp,
)

/** Заголовок приветственного экрана онбординга. */
val FocusHeadlineWelcome = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.Bold,
    fontSize = 36.sp,
    lineHeight = 39.sp,
    letterSpacing = (-0.72).sp,
)

/** Заголовки экранов, название места, фокус-карточка на главной. */
val FocusHeadline = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.SemiBold,
    fontSize = 26.sp,
    lineHeight = 30.sp,
    letterSpacing = (-0.26).sp,
)

/** Цифра в ячейке кода из SMS (шаг 0c). Мержить с [TabularNums]. */
val FocusOtpDigit = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.SemiBold,
    fontSize = 28.sp,
    lineHeight = 32.sp,
)

/** Цифра на клавише нампада PIN (шаг 0e). */
val FocusPinKey = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.Medium,
    fontSize = 22.sp,
    lineHeight = 26.sp,
)

/** Заголовок шторки бронирования, «Активных талонов нет». */
val FocusTitleSheet = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.SemiBold,
    fontSize = 20.sp,
    lineHeight = 24.sp,
)

/**
 * Декоративное число на фокус-карточке (opacity .14 — задаётся цветом в месте
 * применения, не здесь).
 */
val FocusGhostNumeral = TextStyle(
    fontFamily = Inter,
    fontWeight = FontWeight.Normal,
    fontSize = 118.sp,
    lineHeight = 118.sp,
    letterSpacing = (-4.72).sp,
)
