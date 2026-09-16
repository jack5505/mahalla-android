package uz.mahalla.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Значения — design_handoff_mahalla_focus/README.md, раздел «Радиусы».
val FocusShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), // чипы
    small = RoundedCornerShape(12.dp), // поля ввода, плитка карты/фото
    medium = RoundedCornerShape(16.dp), // плитки категорий
    large = RoundedCornerShape(24.dp), // карточка талона, «пусто»-карточка
    extraLarge = RoundedCornerShape(28.dp), // фокус-карточка, шторка сверху
)

val FocusButtonShape = RoundedCornerShape(20.dp)

// Строка списка вне list-divided.
val FocusItemShape = RoundedCornerShape(10.dp)
