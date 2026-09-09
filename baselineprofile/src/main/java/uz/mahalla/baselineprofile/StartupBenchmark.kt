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
 * Запуск (нужно устройство или эмулятор), команда целиком — в
 * `docs/PERFORMANCE.md`: сначала `:app:generateBaselineProfile`, иначе
 * [startupWithBaselineProfile] честно падает на отсутствующем профиле, и
 * обязательно с фильтром `androidx.benchmark.enabledRules=Macrobenchmark` —
 * без него в том же прогоне запустится генератор профиля, которому нужен
 * неминифицированный вариант сборки.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    // `None`, а не `Partial(Disable, warmupIterations = 0)`: такая пара
    // запрещена самим benchmark'ом («Must set baselineProfileMode != Ignore,
    // or warmup iterations > 0») и тест падал бы, не начавшись. `None` — это
    // и есть состояние приложения сразу после установки, без профиля.
    @Test
    fun startupWithoutBaselineProfile() = measureStartup(CompilationMode.None())

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
