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
    displaySmall = TextStyle(Inter, FontWeight.Bold, 24.sp, lineHeight = 28.sp, letterSpacing = (-0.6).sp),
    headlineSmall = TextStyle(Inter, FontWeight.Bold, 18.sp, lineHeight = 23.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(Inter, FontWeight.SemiBold, 16.sp, lineHeight = 21.sp),
    titleSmall = TextStyle(Inter, FontWeight.Bold, 15.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(Inter, FontWeight.Normal, 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(Inter, FontWeight.SemiBold, 11.sp, lineHeight = 15.sp, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(Inter, FontWeight.Normal, 10.sp, lineHeight = 14.sp),
)

/** Суммы и номера талонов — моноширинные цифры, иначе прыгают при обновлении. */
val TabularNums = TextStyle(fontFeatureSettings = "tnum")
