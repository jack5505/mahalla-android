package uz.mahalla.data.security.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import uz.mahalla.data.security.AndroidKeystorePinCipher
import uz.mahalla.data.security.KeystorePinStorage
import uz.mahalla.data.security.PinCipher
import uz.mahalla.data.security.PinStorage

@Module
@InstallIn(SingletonComponent::class)
interface SecurityModule {

    @Binds
    fun bindPinCipher(impl: AndroidKeystorePinCipher): PinCipher

    @Binds
    fun bindPinStorage(impl: KeystorePinStorage): PinStorage
}
