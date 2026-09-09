package uz.mahalla.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Замер холодного старта (эпик 13.3) — время до первого кадра (TTID) и до
 * полной отрисовки (TTFD).
 *
 * Два прогона на один вопрос: «профиль вообще что-то даёт?».
 * [startupWithoutBaselineProfile] — приложение без профиля (как при первой
 * установке из магазина до прогрева), [startupWithBaselineProfile] — с
 * профилем из репозитория. Разница между ними и есть польза эпика 13.3;
 * абсолютные миллисекунды сами по себе ни о чём не говорят — они зависят от
 * устройства.
 *
 * Запуск (нужно устройство или эмулятор):
 * `./gradlew :baselineprofile:pixel6Api34BenchmarkReleaseAndroidTest`.
 * Числа — в `baselineprofile/build/outputs/connected_android_test_additional_output/`.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun startupWithoutBaselineProfile() = measureStartup(
        CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Disable, warmupIterations = 0),
    )

    @Test
    fun startupWithBaselineProfile() = measureStartup(
        CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
    )

    private fun measureStartup(compilationMode: CompilationMode) = benchmarkRule.measureRepeated(
        packageName = APP_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        // Пять итераций — минимум, на котором разброс холодного старта
        // перестаёт перекрывать разницу между режимами компиляции.
        iterations = 5,
        startupMode = StartupMode.COLD,
        compilationMode = compilationMode,
    ) {
        pressHome()
        startActivityAndWait()
    }
}
