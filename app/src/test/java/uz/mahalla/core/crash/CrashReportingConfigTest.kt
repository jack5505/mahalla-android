package uz.mahalla.core.crash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Решение «слать отчёты или нет» (issue #74). Ошибка здесь стоит либо тишины в
 * панели, либо падения на старте у всех — поэтому оно одно и проверяемое.
 */
class CrashReportingConfigTest {

    private fun config(
        dsn: String = "https://abc123@sentry.example.com/42",
        enabledForBuild: Boolean = true,
    ) = CrashReportingConfig(
        dsn = dsn,
        enabledForBuild = enabledForBuild,
        environment = "release",
        release = "uz.mahalla@0.1.0+1",
    )

    @Test
    fun `release build with a dsn collects reports`() {
        assertTrue(config().isEnabled)
    }

    @Test
    fun `no dsn means no collecting and no crash on start`() {
        // Секрет SENTRY_DSN может быть не задан: форк, локальная сборка,
        // сборка до заведения проекта в Sentry. Приложение обязано работать.
        assertFalse(config(dsn = "").isEnabled)
        assertFalse(config(dsn = "   ").isEnabled)
    }

    @Test
    fun `a build that did not ask for reports does not send them`() {
        // Debug по умолчанию: падение на машине разработчика — это работа, а не
        // инцидент, и панель от таких отчётов засоряется.
        assertFalse(config(enabledForBuild = false).isEnabled)
    }

    @Test
    fun `a mistyped dsn switches collecting off instead of crashing the app`() {
        // Sentry.init бросает IllegalArgumentException на мусоре — опечатка в
        // секрете иначе роняла бы старт приложения у всех.
        listOf(
            "не адрес вовсе",
            "abc123@sentry.example.com/42",
            "ftp://abc123@sentry.example.com/42",
            "https://sentry.example.com/42",
            "https://abc123@sentry.example.com",
        ).forEach { dsn -> assertFalse(dsn, config(dsn = dsn).isEnabled) }
    }
}
