package uz.mahalla.core.crash

/**
 * Отчёты о падениях (issue #74).
 *
 * Интерфейс, а не прямые вызовы SDK: во-первых, реализация подменяема в тестах
 * ([NoopCrashReporter] и фейк в `testutil`), во-вторых, замена Sentry на что-то
 * другое не должна расползаться по приложению.
 *
 * Здесь намеренно **нет** ни `setUser`, ни методов, принимающих произвольные
 * данные: в отчёт не должно попасть ничего, что человек вводил или что прислал
 * бэкенд (T8, риск из issue #34). Всё, что уходит вместе с ошибкой, — короткое
 * машинное имя операции.
 */
interface CrashReporter {

    /** Собираются ли отчёты в этой сборке. */
    val isEnabled: Boolean

    /**
     * Поднимает SDK. Вызывается один раз из `MahallaApplication`; повторные
     * вызовы обязаны быть безвредны.
     */
    fun install()

    /**
     * Ошибка, которую приложение проглотило и продолжило работать: отказ
     * Keystore, отказ записи в DataStore, провал инициализации MapKit. Для
     * пользователя это не падение, а для эксплуатации — единственный след.
     *
     * @param operation машинное имя места (`pin.save`, `mapkit.initialize`) —
     * по нему отчёты группируются в панели. Ничего пользовательского.
     */
    fun recordNonFatal(error: Throwable, operation: String)
}

/**
 * Заглушка для сборок без DSN и для тестов: сбор выключен, вызовы ничего не
 * делают. Отдельный тип, а не `if (enabled)` по всему коду — вызывающему не
 * нужно знать, настроен сбор или нет.
 */
object NoopCrashReporter : CrashReporter {

    override val isEnabled: Boolean = false

    override fun install() = Unit

    override fun recordNonFatal(error: Throwable, operation: String) = Unit
}
