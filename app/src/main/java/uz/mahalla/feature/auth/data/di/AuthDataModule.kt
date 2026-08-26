package uz.mahalla.feature.auth.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import uz.mahalla.feature.auth.data.AuthRepository
import uz.mahalla.feature.auth.data.DefaultAuthRepository

@Module
@InstallIn(SingletonComponent::class)
interface AuthDataModule {

    @Binds
    fun bindAuthRepository(impl: DefaultAuthRepository): AuthRepository
}
