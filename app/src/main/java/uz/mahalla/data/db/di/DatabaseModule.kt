package uz.mahalla.data.db.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import uz.mahalla.data.db.MahallaDatabase
import uz.mahalla.data.db.dao.CartDraftDao
import uz.mahalla.data.db.dao.OrderDao
import uz.mahalla.data.db.dao.PlaceDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MahallaDatabase =
        Room.databaseBuilder(context, MahallaDatabase::class.java, MahallaDatabase.NAME)
            // Кэш можно потерять без последствий, поэтому до первого релиза
            // пересоздаём БД вместо написания миграций.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun providePlaceDao(database: MahallaDatabase): PlaceDao = database.placeDao()

    @Provides
    fun provideOrderDao(database: MahallaDatabase): OrderDao = database.orderDao()

    @Provides
    fun provideCartDraftDao(database: MahallaDatabase): CartDraftDao = database.cartDraftDao()
}
