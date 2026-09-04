package uz.mahalla.core.crash.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import uz.mahalla.BuildConfig
import uz.mahalla.core.crash.CrashReporter
import uz.mahalla.core.crash.CrashReportingConfig
import uz.mahalla.core.crash.NoopCrashReporter
import uz.mahalla.core.crash.SentryCrashReporter
import javax.inject.Singleton

/**
 * Отчёты о падениях в графе (issue #74).
 *
 * Единственное место, где читается `BuildConfig`: дальше по коду решение уже
 * принято и лежит в [CrashReportingConfig], поэтому его можно проверить тестом.
 */
@Module
@InstallIn(SingletonComponent::class)
object CrashModule {

    @Provides
    @Singleton
    fun provideCrashReportingConfig(): CrashReportingConfig = CrashReportingConfig(
        dsn = BuildConfig.SENTRY_DSN,
        enabledForBuild = BuildConfig.CRASH_REPORTING_ENABLED,
        environment = BuildConfig.BUILD_TYPE,
        // `uz.mahalla@0.1.0+1` — формат релиза, который Sentry разбирает сам:
        // версия для человека, код версии для точного сопоставления сборок.
        release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}",
    )

    /**
     * Без DSN (секрет не задан) и в debug без явного флага возвращается
     * заглушка: SDK тогда не поднимается вовсе, а не поднимается «вхолостую».
     */
    @Provides
    @Singleton
    fun provideCrashReporter(
        @ApplicationContext context: Context,
        config: CrashReportingConfig,
    ): CrashReporter =
        if (config.isEnabled) SentryCrashReporter(context, config) else NoopCrashReporter
}
