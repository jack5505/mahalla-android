package uz.mahalla.feature.onboarding.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import uz.mahalla.feature.onboarding.data.DataStoreOnboardingRepository
import uz.mahalla.feature.onboarding.data.OnboardingRepository

@Module
@InstallIn(SingletonComponent::class)
interface OnboardingDataModule {

    @Binds
    fun bindOnboardingRepository(impl: DataStoreOnboardingRepository): OnboardingRepository
}
