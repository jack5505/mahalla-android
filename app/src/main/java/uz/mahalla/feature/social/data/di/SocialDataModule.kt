package uz.mahalla.feature.social.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import uz.mahalla.feature.social.data.DefaultSocialRepository
import uz.mahalla.feature.social.data.SocialApi
import uz.mahalla.feature.social.data.SocialRepository
import javax.inject.Singleton

/**
 * Социальные действия (issue #75) на **основном** Retrofit: все ручки
 * контроллера `social` требуют Bearer, а `@RefreshClient` его не ставит.
 */
@Module
@InstallIn(SingletonComponent::class)
object SocialDataModule {

    @Provides
    @Singleton
    fun provideSocialApi(retrofit: Retrofit): SocialApi = retrofit.create(SocialApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
interface SocialBindingsModule {

    @Binds
    fun bindSocialRepository(impl: DefaultSocialRepository): SocialRepository
}
