package uz.mahalla.feature.freelancer.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import uz.mahalla.feature.freelancer.data.DefaultFreelancerRepository
import uz.mahalla.feature.freelancer.data.FreelancerApi
import uz.mahalla.feature.freelancer.data.FreelancerRepository
import javax.inject.Singleton

/**
 * Мастера (issue #107). API — на **основном** Retrofit: заказ и список своих
 * заказов требуют Bearer, а «голый» `@RefreshClient` его не ставит. Каталог,
 * профиль и услуги анонимны, но лишний заголовок им не мешает.
 */
@Module
@InstallIn(SingletonComponent::class)
object FreelancerDataModule {

    @Provides
    @Singleton
    fun provideFreelancerApi(retrofit: Retrofit): FreelancerApi =
        retrofit.create(FreelancerApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
interface FreelancerBindingsModule {

    @Binds
    fun bindFreelancerRepository(impl: DefaultFreelancerRepository): FreelancerRepository
}
