package uz.mahalla

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp
import uz.mahalla.core.crash.CrashReporter
import uz.mahalla.core.crash.CrashReporting
import javax.inject.Inject
import javax.inject.Provider

/**
 * Точка входа DI (эпик 1.1). Больше в `Application` ничего не появляется:
 * инициализация подсистем — через Hilt-модули и `@Inject`, иначе старт
 * приложения превращается в свалку.
 *
 * Исключений два.
 *
 * Отчёты о падениях (issue #74): их обработчик обязан встать раньше кода,
 * который может упасть, а сделать это из ленивой зависимости нельзя — до
 * первого обращения к ней падение уже произошло бы невидимым.
 *
 * [ImageLoaderFactory] (issue #60): Coil берёт свой синглтон именно из
 * `Application`, другого места объявить его нет. Сам загрузчик приезжает из
 * графа через [Provider], поэтому создаётся не на старте, а при первой
 * картинке.
 */
@HiltAndroidApp
class MahallaApplication : Application(), ImageLoaderFactory {

    @Inject
    lateinit var crashReporter: CrashReporter

    @Inject
    lateinit var imageLoader: Provider<ImageLoader>

    override fun onCreate() {
        // Hilt внедряет поля Application именно здесь, поэтому раньше
        // super.onCreate() до crashReporter не добраться.
        super.onCreate()
        crashReporter.install()
        // Проглоченные ошибки сообщаются из функций верхнего уровня, которым
        // нечего внедрять, — см. CrashReporting.
        CrashReporting.install(crashReporter)
    }

    override fun newImageLoader(): ImageLoader = imageLoader.get()
}
