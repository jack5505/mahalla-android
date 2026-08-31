package uz.mahalla

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import uz.mahalla.core.crash.CrashReporter
import uz.mahalla.core.crash.CrashReporting
import javax.inject.Inject

/**
 * Точка входа DI (эпик 1.1). Больше в `Application` ничего не появляется:
 * инициализация подсистем — через Hilt-модули и `@Inject`, иначе старт
 * приложения превращается в свалку.
 *
 * Исключение одно — отчёты о падениях (issue #74). Их обработчик обязан встать
 * раньше кода, который может упасть, а сделать это из ленивой зависимости
 * нельзя: до первого обращения к ней падение уже произошло бы невидимым.
 */
@HiltAndroidApp
class MahallaApplication : Application() {

    @Inject
    lateinit var crashReporter: CrashReporter

    override fun onCreate() {
        // Hilt внедряет поля Application именно здесь, поэтому раньше
        // super.onCreate() до crashReporter не добраться.
        super.onCreate()
        crashReporter.install()
        // Проглоченные ошибки сообщаются из функций верхнего уровня, которым
        // нечего внедрять, — см. CrashReporting.
        CrashReporting.install(crashReporter)
    }
}
