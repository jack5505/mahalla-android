package uz.mahalla.data.db.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import uz.mahalla.data.db.MahallaDatabase
import uz.mahalla.data.db.MahallaMigrations
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
            // Обновление приложения не должно стирать черновик корзины
            // (issue #64): вместо fallbackToDestructiveMigration идут явные
            // миграции. Нет миграции для версии — падение при открытии БД, и
            // это осознанно: молча потерять собранную корзину хуже.
            .addMigrations(*MahallaMigrations.ALL)
            // Понижение версии — другое дело: это установка сборки постарше
            // поверх новой (обычно у разработчика или тестировщика), и старый
            // код физически не знает схемы, которая уже в файле. Пересоздать
            // БД — единственный выход, который не превращает откат в кирпич.
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

    @Provides
    fun providePlaceDao(database: MahallaDatabase): PlaceDao = database.placeDao()

    @Provides
    fun provideOrderDao(database: MahallaDatabase): OrderDao = database.orderDao()

    @Provides
    fun provideCartDraftDao(database: MahallaDatabase): CartDraftDao = database.cartDraftDao()
}
