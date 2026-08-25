package uz.mahalla.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Значения — design/android/HANDOFF.md.
val FocusShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp), // поля ввода, чипы, миниатюры
    medium = RoundedCornerShape(12.dp), // карточки
    large = RoundedCornerShape(14.dp), // bottom sheet
    extraLarge = RoundedCornerShape(16.dp),
)

val FocusButtonShape = RoundedCornerShape(10.dp)

// Строка списка вне list-divided.
val FocusItemShape = RoundedCornerShape(10.dp)
