package uz.mahalla.core.analytics.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import retrofit2.Retrofit
import uz.mahalla.core.analytics.AnalyticsRepository
import uz.mahalla.core.analytics.AnalyticsTracker
import uz.mahalla.core.analytics.DefaultAnalyticsTracker
import uz.mahalla.data.network.analytics.AnalyticsApi
import uz.mahalla.data.network.analytics.DefaultAnalyticsRepository
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Область [provideAnalyticsScope], а не `viewModelScope`: `AnalyticsTracker`
 * живёт с графом (см. его же doc), и квалификатор нужен ровно затем, чтобы
 * `AnalyticsTrackerTest` мог взять ровно эту область — с настоящим
 * `Dispatchers.IO` — и отменить её по завершении теста (issue #228, п. 3).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AnalyticsCoroutineScope

/**
 * Продуктовая аналитика в графе (issue #169).
 *
 * API — на **основном** Retrofit: ручка требует токен, а значит ей нужны и
 * `AuthInterceptor`, и `TokenAuthenticator`.
 *
 * Обратная сторона: провал refresh на фоновом событии закрывает сессию так же,
 * как на запросе, который сделал человек (`TokenAuthenticator` →
 * `sessionStore.clear()`), и уводит на вход из середины его дела. Принято
 * осознанно: мёртвая сессия мёртва независимо от того, кто её первым
 * обнаружил, а клиент без authenticator'а терял бы событие на каждом
 * истёкшем access-токене. Обсуждение — issue #228, п. 1.
 */
@Module
@InstallIn(SingletonComponent::class)
object AnalyticsModule {

    @Provides
    @Singleton
    fun provideAnalyticsApi(retrofit: Retrofit): AnalyticsApi =
        retrofit.create(AnalyticsApi::class.java)

    /**
     * Область живёт с графом, а не с экраном: событие «заказ создан»
     * отправляется в тот же момент, когда экран закрывается, и на
     * `viewModelScope` запрос отменился бы раньше, чем ушёл.
     *
     * Отдельный `@Provides`, а не выражение внутри [provideAnalyticsTracker]:
     * `AnalyticsTrackerTest` берёт область саму по себе, чтобы проверить
     * доставку на настоящем `Dispatchers.IO` и отменить её по завершении, не
     * трогая синглтон графа (issue #228, п. 3).
     *
     * [SupervisorJob] — страховка на случай, если `runCatchingCancellable` в
     * `DefaultAnalyticsTracker.track` когда-нибудь убрать: сейчас до Job не
     * доходит ничего, кроме `Error` и исключения из самого `log` (а `Log.d`
     * не бросает), поэтому тестом живучесть области не закреплена
     * (issue #228, п. 2). С обычным `Job` первое сбежавшее
     * исключение погасило бы область, и аналитика молчала бы до перезапуска
     * приложения.
     */
    @Provides
    @Singleton
    @AnalyticsCoroutineScope
    fun provideAnalyticsScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideAnalyticsTracker(
        repository: AnalyticsRepository,
        @AnalyticsCoroutineScope scope: CoroutineScope,
    ): AnalyticsTracker = DefaultAnalyticsTracker(repository = repository, scope = scope)
}

@Module
@InstallIn(SingletonComponent::class)
interface AnalyticsBindingsModule {

    @Binds
    fun bindAnalyticsRepository(impl: DefaultAnalyticsRepository): AnalyticsRepository
}
