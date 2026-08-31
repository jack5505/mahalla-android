package uz.mahalla.testutil

import uz.mahalla.core.crash.CrashReporter

/** Запоминает, о чём сообщили: тесты проверяют факт и имя операции. */
class FakeCrashReporter(
    override val isEnabled: Boolean = true,
) : CrashReporter {

    data class Report(val error: Throwable, val operation: String)

    val reports = mutableListOf<Report>()
    var installCount = 0
        private set

    override fun install() {
        installCount++
    }

    override fun recordNonFatal(error: Throwable, operation: String) {
        reports += Report(error, operation)
    }
}
