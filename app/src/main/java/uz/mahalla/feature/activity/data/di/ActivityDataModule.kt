package uz.mahalla.feature.activity.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import uz.mahalla.feature.activity.data.ActivityRepository
import uz.mahalla.feature.activity.data.DefaultActivityRepository
import uz.mahalla.feature.activity.data.DefaultPlaceNameResolver
import uz.mahalla.feature.activity.data.PlaceNameResolver

/**
 * «Мои активности» (issue #73) — только привязка репозитория.
 *
 * Своего `Api` у фичи нет (issue #142): все пять источников читаются
 * существующими `FashionApi` / `GamingApi` / `BookingApi` / `HospitalApi` /
 * `CinemaApi`, а их предоставляют модули своих вертикалей — все на
 * **основном** Retrofit, потому что все пять ручек требуют Bearer.
 *
 * `PlaceNameResolver` (issue #182) берёт `CatalogApi` того же Retrofit —
 * `GET places?ids=` тоже требует Bearer.
 */
@Module
@InstallIn(SingletonComponent::class)
interface ActivityBindingsModule {

    @Binds
    fun bindActivityRepository(impl: DefaultActivityRepository): ActivityRepository

    @Binds
    fun bindPlaceNameResolver(impl: DefaultPlaceNameResolver): PlaceNameResolver
}
