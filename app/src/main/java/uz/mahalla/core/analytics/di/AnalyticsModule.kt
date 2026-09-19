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
import uz.mahalla.core.analytics.AnalyticsEventQueue
import uz.mahalla.core.analytics.AnalyticsTracker
import uz.mahalla.core.analytics.DefaultAnalyticsTracker
import uz.mahalla.data.network.analytics.AnalyticsApi
import uz.mahalla.data.network.analytics.AnalyticsEventsRepository
import uz.mahalla.data.network.analytics.AnalyticsRepository
import uz.mahalla.data.network.analytics.DefaultAnalyticsEventQueue
import uz.mahalla.data.network.analytics.DefaultAnalyticsEventsRepository
import uz.mahalla.data.network.analytics.DefaultAnalyticsRepository
import javax.inject.Singleton

/**
 * Продуктовая аналитика в графе (issue #169, #226).
 *
 * `AnalyticsApi` (обе ручки, `track` и `events`) — на **основном** Retrofit,
 * значит под `AuthInterceptor` и `TokenAuthenticator`.
 *
 * Обратная сторона: провал refresh на фоновом событии закрывает сессию так же,
 * как на запросе, который сделал человек (`TokenAuthenticator` →
 * `sessionStore.clear()`), и уводит на вход из середины его дела. Принято
 * осознанно: мёртвая сессия мертва независимо от того, кто её первым
 * обнаружил, а клиент без authenticator'а терял бы событие на каждом
 * истёкшем access-токене. Обсуждение — issue #228, п. 1. Для `events` это
 * мягче, чем для `track`: ручка работает и без `Authorization`, поэтому
 * `DefaultAnalyticsEventQueue` не откладывает отправку из-за сессии — только
 * протухший `Authorization` иногда потянет за собой refresh.
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
    fun provideAnalyticsTracker(
        repository: AnalyticsRepository,
        queue: AnalyticsEventQueue,
    ): AnalyticsTracker =
        DefaultAnalyticsTracker(
            repository = repository,
            queue = queue,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
}

@Module
@InstallIn(SingletonComponent::class)
interface AnalyticsBindingsModule {

    @Binds
    fun bindAnalyticsRepository(impl: DefaultAnalyticsRepository): AnalyticsRepository

    @Binds
    fun bindAnalyticsEventsRepository(impl: DefaultAnalyticsEventsRepository): AnalyticsEventsRepository

    @Binds
    fun bindAnalyticsEventQueue(impl: DefaultAnalyticsEventQueue): AnalyticsEventQueue
}
