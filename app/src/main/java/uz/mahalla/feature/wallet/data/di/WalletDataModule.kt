package uz.mahalla.feature.wallet.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import uz.mahalla.feature.wallet.data.DefaultPaymentsRepository
import uz.mahalla.feature.wallet.data.DefaultWalletRepository
import uz.mahalla.feature.wallet.data.PaymentsRepository
import uz.mahalla.feature.wallet.data.WalletApi
import uz.mahalla.feature.wallet.data.WalletRepository
import javax.inject.Singleton

/**
 * Кошелёк (issue #62) на **основном** Retrofit: обе ручки требуют Bearer, а
 * `@RefreshClient` его не ставит.
 *
 * `PaymentsApi` здесь не заводится: она уже провайдится в
 * `SubscriptionDataModule` (issue #103, задача 9.3), и вкладка «Платежи»
 * (issue #184) переиспользует ту же ручку через [PaymentsRepository].
 */
@Module
@InstallIn(SingletonComponent::class)
object WalletDataModule {

    @Provides
    @Singleton
    fun provideWalletApi(retrofit: Retrofit): WalletApi = retrofit.create(WalletApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
interface WalletBindingsModule {

    @Binds
    fun bindWalletRepository(impl: DefaultWalletRepository): WalletRepository

    @Binds
    fun bindPaymentsRepository(impl: DefaultPaymentsRepository): PaymentsRepository
}
