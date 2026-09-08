package uz.mahalla.feature.hospital.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import uz.mahalla.feature.hospital.data.DefaultHospitalRepository
import uz.mahalla.feature.hospital.data.HospitalApi
import uz.mahalla.feature.hospital.data.HospitalRepository
import javax.inject.Singleton

/**
 * Больницы (issue #99). API — на **основном** Retrofit: запись, список своих
 * записей и отмена требуют Bearer, а «голый» `@RefreshClient` его не ставит.
 * Список врачей анонимен, но лишний заголовок ему не мешает.
 */
@Module
@InstallIn(SingletonComponent::class)
object HospitalDataModule {

    @Provides
    @Singleton
    fun provideHospitalApi(retrofit: Retrofit): HospitalApi =
        retrofit.create(HospitalApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
interface HospitalBindingsModule {

    @Binds
    fun bindHospitalRepository(impl: DefaultHospitalRepository): HospitalRepository
}
