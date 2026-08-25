package uz.mahalla.core.locale

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import uz.mahalla.data.prefs.SettingsDataStore

/**
 * Доступ к настройкам из `Activity.attachBaseContext` — там инъекция полей
 * ещё не выполнена, а язык нужен до создания `Context` (фолбэк per-app
 * languages для API 26–32, эпик 1.5).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface LocaleEntryPoint {
    fun settingsDataStore(): SettingsDataStore
}
