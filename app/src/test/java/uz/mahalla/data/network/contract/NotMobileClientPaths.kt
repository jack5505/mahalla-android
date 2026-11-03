package uz.mahalla.data.network.contract

/**
 * Группы путей бэкенда, которые мобильный клиент осознанно не зовёт — «не
 * наши», а не забытые (T17, `docs/TASKS-BACKLOG.md`).
 *
 * [PathsContractTest] использует это, чтобы «у бэкенда есть, клиент не
 * зовёт» был читаемым отчётом по группам, а не стеной из полутора сотен
 * строк: путь, не попавший ни в одну группу, виден в отчёте отдельно —
 * тогда его дописывают сюда с причиной, а не молча проглатывают.
 */
object NotMobileClientPaths {

    class Group(val reason: String, private val prefixes: List<String>) {
        fun matches(path: String): Boolean {
            val withoutMethod = path.substringAfter(' ')
            return prefixes.any(withoutMethod::startsWith)
        }
    }

    val GROUPS: List<Group> = listOf(
        Group(
            reason = "admin/* — админка платформы, не мобильное приложение",
            prefixes = listOf("admin/"),
        ),
        Group(
            reason = "вебхуки платёжных провайдеров — зовёт Payme/Click, не приложение",
            prefixes = listOf("payments/click/callback", "payments/payme/callback"),
        ),
    )

    fun reasonFor(path: String): String? = GROUPS.firstOrNull { it.matches(path) }?.reason
}
