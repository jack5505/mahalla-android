package uz.mahalla.core.crash

/**
 * Процессная точка доступа к [CrashReporter] (issue #74).
 *
 * Единственное место в приложении, где зависимость берётся не из Hilt, и это
 * сознательно: сообщать о проглоченной ошибке нужно из
 * [uz.mahalla.core.result.runCatchingCancellable] — обычной функции верхнего
 * уровня, которой нечего внедрять, — и из `.reportSwallowed()` рядом с ней.
 * Тянуть `CrashReporter` параметром через сорок вызовов значило бы переписать
 * половину ViewModel'ей ради одной строки в каждой.
 *
 * До [install] и в тестах здесь стоит [NoopCrashReporter], поэтому код,
 * вызывающий [recordNonFatal], работает одинаково и без настроенного сбора.
 */
object CrashReporting {

    @Volatile
    private var reporter: CrashReporter = NoopCrashReporter

    /** Ставится один раз из `MahallaApplication` после сборки графа. */
    fun install(reporter: CrashReporter) {
        this.reporter = reporter
    }

    /** Возврат к заглушке. Нужен тестам, чтобы прогоны не влияли друг на друга. */
    fun reset() {
        reporter = NoopCrashReporter
    }

    fun recordNonFatal(error: Throwable, operation: String) {
        reporter.recordNonFatal(error, operation)
    }
}

/**
 * Отправляет проглоченную ошибку в отчёты, не меняя сам [Result].
 *
 * Ставится точечно, а не внутрь `runCatchingCancellable`: тем же
 * `runCatchingCancellable` закрыты и штатные ситуации — сервер не ответил на
 * `HEAD` ([uz.mahalla.data.network.BackendReachability]), Telegram не
 * установлен, тело ошибки не разбирается как JSON, разрешения на геолокацию
 * нет. Сообщать о них значило бы утопить настоящие отказы в шуме.
 *
 * @param operation машинное имя места; попадает в отчёт тегом `operation` и
 * задаёт группировку.
 */
fun <T> Result<T>.reportSwallowed(operation: String): Result<T> =
    onFailure { CrashReporting.recordNonFatal(it, operation) }
