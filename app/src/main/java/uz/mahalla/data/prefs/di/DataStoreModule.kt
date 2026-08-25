package uz.mahalla.data.prefs.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import uz.mahalla.core.locale.AndroidAppLocaleManager
import uz.mahalla.core.locale.AppLocaleManager
import uz.mahalla.data.prefs.DataStoreSessionStore
import uz.mahalla.data.prefs.SessionStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    /**
     * Один файл на приложение: настройки, сессия и PIN-хэш живут вместе.
     * Из бэкапа и device-to-device transfer он исключён (`backup_rules.xml`,
     * `data_extraction_rules.xml`) — токены не должны уезжать в облако.
     */
    private const val FILE_NAME = "mahalla_preferences"

    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        // Битый файл (обрыв записи, кривой restore) без обработчика — исключение
        // на первом же чтении настроек, т.е. краш на старте под splash'ем.
        // Настройки восстановимы, поэтому сбрасываем их и идём дальше.
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        produceFile = { context.preferencesDataStoreFile(FILE_NAME) },
    )
}

@Module
@InstallIn(SingletonComponent::class)
interface StorageBindingsModule {

    @Binds
    fun bindSessionStore(impl: DataStoreSessionStore): SessionStore

    @Binds
    fun bindAppLocaleManager(impl: AndroidAppLocaleManager): AppLocaleManager
}
