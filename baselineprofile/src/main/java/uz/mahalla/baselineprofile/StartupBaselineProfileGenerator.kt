package uz.mahalla.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Снятие Baseline Profile (эпик 13.3).
 *
 * Профиль — это список классов и методов, которые нужны приложению на старте.
 * ART компилирует их заранее, при установке, а не интерпретирует на первом
 * запуске: холодный старт становится заметно быстрее ровно на тех дешёвых
 * устройствах, ради которых выбран minSdk 26.
 *
 * Запуск: `./gradlew :app:generateBaselineProfile` (нужен эмулятор — Gradle
 * поднимает его сам, см. `pixel6Api34` в build.gradle.kts). Результат
 * приезжает в `app/src/release/generated/baselineProfiles/` и коммитится:
 * иначе каждая сборка релиза требовала бы эмулятора.
 *
 * Профиль обходит только то, что видит человек в первые секунды: старт,
 * прорисовка первого экрана, первая прокрутка. Дальше начинается разное
 * поведение (авторизован/нет), и профиль пришлось бы держать под каждое.
 */
@RunWith(AndroidJUnit4::class)
class StartupBaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startup() {
        baselineProfileRule.collect(packageName = APP_PACKAGE) {
            pressHome()
            startActivityAndWait()
            // startActivityAndWait возвращает управление на первом кадре, а он
            // ещё splash (Theme.Mahalla.Splash в манифесте). Без ожидания
            // простоя в профиль не попадут классы Compose, ради которых всё и
            // затевалось.
            device.waitForIdle()
        }
    }
}

/** Тот же `applicationId`, что в app/build.gradle.kts. */
internal const val APP_PACKAGE = "uz.mahalla"
