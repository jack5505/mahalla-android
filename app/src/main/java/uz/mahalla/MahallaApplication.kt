package uz.mahalla

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Точка входа DI (эпик 1.1). Больше в `Application` ничего не появляется:
 * инициализация подсистем — через Hilt-модули и `@Inject`, иначе старт
 * приложения превращается в свалку.
 */
@HiltAndroidApp
class MahallaApplication : Application()
