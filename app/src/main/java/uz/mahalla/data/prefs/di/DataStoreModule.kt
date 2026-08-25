package uz.mahalla.data.prefs.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
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

    /** Один файл на приложение: настройки, сессия и PIN-хэш живут вместе. */
    private const val FILE_NAME = "mahalla_preferences"

    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
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
