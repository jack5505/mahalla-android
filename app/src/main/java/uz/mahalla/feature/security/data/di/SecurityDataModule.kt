package uz.mahalla.feature.security.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import uz.mahalla.data.network.auth.SessionApi
import uz.mahalla.data.network.pin.PinApi
import uz.mahalla.feature.security.data.DefaultSecurityRepository
import uz.mahalla.feature.security.data.SecurityRepository
import javax.inject.Singleton

/**
 * Аккаунтный PIN и app-lock (issue #102) на **основном** Retrofit: все ручки
 * контроллера `pin-code`, а также `auth/session/check` и `auth/pin-resume`,
 * требуют Bearer-токена — без него отвечают `401` (проверено на стенде).
 *
 * (Написать здесь путь со звёздочкой нельзя: блочные комментарии в Kotlin
 * вложенные, и последовательность внутри KDoc оставляет файл незакрытым — эта
 * грабля уже стоила проекту красного `main`, см. `CLAUDE.md`.)
 *
 * Тем они и отличаются от `auth/pin-login` и `auth/setup-pin`, которые
 * анонимны и живут на «голом» `@RefreshClient`.
 */
@Module
@InstallIn(SingletonComponent::class)
object SecurityDataModule {

    @Provides
    @Singleton
    fun providePinApi(retrofit: Retrofit): PinApi = retrofit.create(PinApi::class.java)

    @Provides
    @Singleton
    fun provideSessionApi(retrofit: Retrofit): SessionApi =
        retrofit.create(SessionApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
interface SecurityBindingsModule {

    @Binds
    fun bindSecurityRepository(impl: DefaultSecurityRepository): SecurityRepository
}
