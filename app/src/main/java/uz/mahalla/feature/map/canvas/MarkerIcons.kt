package uz.mahalla.feature.map.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Иконки маркеров рисуются кодом, а не лежат в `res/drawable` (эпик 4.2).
 *
 * MapKit принимает картинку, а не Compose-цвет, и цвет маркера обязан
 * следовать теме: два набора PNG под светлую и тёмную тему разъезжались бы с
 * палитрой при каждой правке дизайна. Рисование дешёвое, результат кэширует
 * вызывающий (`remember` по цветам и размеру).
 */
internal object MarkerIcons {

    /** Кружок маркера с белой обводкой — читается и на снимках, и на воде. */
    fun place(sizePx: Int, fillColor: Int, strokeColor: Int, selected: Boolean): Bitmap {
        val size = sizePx.coerceAtLeast(MIN_SIZE_PX)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = size / 2f
        val stroke = size * STROKE_RATIO
        // Выбранный маркер крупнее: цветом отличать нельзя — часть пользователей
        // его не различит, а размер и обводка видны всем.
        val radius = center - stroke / 2 - if (selected) 0f else size * UNSELECTED_INSET_RATIO

        canvas.drawCircle(center, center, radius, fillPaint(fillColor))
        canvas.drawCircle(center, center, radius, strokePaint(strokeColor, stroke))
        return bitmap
    }

    /** Кластер: тот же кружок крупнее и с числом мест внутри. */
    fun cluster(
        sizePx: Int,
        fillColor: Int,
        strokeColor: Int,
        textColor: Int,
        count: Int,
    ): Bitmap {
        val label = clusterLabel(count)
        val size = sizePx.coerceAtLeast(MIN_SIZE_PX)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = size / 2f
        val stroke = size * STROKE_RATIO

        canvas.drawCircle(center, center, center - stroke / 2, fillPaint(fillColor))
        canvas.drawCircle(center, center, center - stroke / 2, strokePaint(strokeColor, stroke))

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            // Чем длиннее число, тем мельче шрифт: «120+» обязано влезать.
            textSize = size * TEXT_RATIO / maxOf(1f, label.length * LENGTH_PENALTY)
        }
        val baseline = center - (text.descent() + text.ascent()) / 2
        canvas.drawText(label, center, baseline, text)
        return bitmap
    }

    /**
     * Подпись кластера. Больше двух знаков в кружок не влезает, а точное число
     * мест в куче пользователю и не нужно — нужен масштаб («много»).
     */
    fun clusterLabel(count: Int): String = when {
        count > MAX_SHOWN_COUNT -> "$MAX_SHOWN_COUNT+"
        count < 0 -> "0"
        else -> count.toString()
    }

    private fun fillPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    private fun strokePaint(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
    }

    private const val MIN_SIZE_PX = 8
    private const val STROKE_RATIO = 0.1f
    private const val UNSELECTED_INSET_RATIO = 0.1f
    private const val TEXT_RATIO = 0.5f
    private const val LENGTH_PENALTY = 0.62f
    private const val MAX_SHOWN_COUNT = 99
}
