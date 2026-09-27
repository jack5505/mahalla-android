package uz.mahalla.core.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

/**
 * Звонить, строить маршрут, открывать трейлер умеют не все устройства (и не
 * все оболочки). Отсутствие приложения-обработчика — не повод падать.
 */
fun Context.startActivitySafely(intent: Intent) {
    try {
        startActivity(intent)
    } catch (notFound: ActivityNotFoundException) {
        // Обработчика нет — молча ничего не делаем, экран остаётся на месте.
    }
}
