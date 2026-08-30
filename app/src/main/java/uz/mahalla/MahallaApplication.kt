package uz.mahalla

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import javax.inject.Provider

/**
 * Точка входа DI (эпик 1.1). Больше в `Application` ничего не появляется:
 * инициализация подсистем — через Hilt-модули и `@Inject`, иначе старт
 * приложения превращается в свалку.
 *
 * Исключение одно — [ImageLoaderFactory] (issue #60): Coil берёт свой
 * синглтон именно из `Application`, другого места объявить его нет. Сам
 * загрузчик приезжает из графа через [Provider], поэтому создаётся не на
 * старте, а при первой картинке.
 */
@HiltAndroidApp
class MahallaApplication : Application(), ImageLoaderFactory {

    @Inject
    lateinit var imageLoader: Provider<ImageLoader>

    override fun newImageLoader(): ImageLoader = imageLoader.get()
}
