package uz.mahalla.feature.role.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import uz.mahalla.feature.role.data.DataStoreRoleRepository
import uz.mahalla.feature.role.data.DefaultProviderRepository
import uz.mahalla.feature.role.data.ProviderApi
import uz.mahalla.feature.role.data.ProviderRepository
import uz.mahalla.feature.role.data.RoleRepository
import javax.inject.Singleton

/**
 * Анкеты покупателя и продавца (issue #84). Регистрация заведения — на
 * **основном** Retrofit: `POST /places` требует Bearer, а `@RefreshClient`
 * его не ставит.
 */
@Module
@InstallIn(SingletonComponent::class)
object RoleDataModule {

    @Provides
    @Singleton
    fun provideProviderApi(retrofit: Retrofit): ProviderApi =
        retrofit.create(ProviderApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
interface RoleBindingsModule {

    @Binds
    fun bindRoleRepository(impl: DataStoreRoleRepository): RoleRepository

    @Binds
    fun bindProviderRepository(impl: DefaultProviderRepository): ProviderRepository
}
