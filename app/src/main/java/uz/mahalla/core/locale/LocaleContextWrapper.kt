package uz.mahalla.core.locale

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Фолбэк per-app languages для API 26–32 (эпик 1.5): системного
 * `LocaleManager` там нет, поэтому локаль подменяется на уровне `Context`
 * в `Activity.attachBaseContext`.
 */
object LocaleContextWrapper {

    fun wrap(base: Context, language: AppLanguage): Context {
        val tag = language.tag ?: return base
        val locale = Locale.forLanguageTag(tag)
        // Формат чисел/дат в java.text и java.time тоже должен поехать за UI.
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return base.createConfigurationContext(configuration)
    }
}
