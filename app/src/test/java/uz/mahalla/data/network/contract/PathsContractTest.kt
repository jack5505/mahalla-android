package uz.mahalla.data.network.contract

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Сверка путей `*Api.kt` с бэкендом (T17, `docs/TASKS-BACKLOG.md`, issue #417).
 *
 * Список вызовов клиента разбирается здесь же из исходников — тем же
 * регулярным выражением, что и `contract/paths.sh` — а не берётся вторым
 * списком из фикстуры: второй список расходится с кодом при первой же
 * правке `*Api.kt`.
 *
 * Список путей бэкенда идёт из закоммиченной фикстуры `contract/paths/paths.json`
 * — её снимает `contract/paths.sh --write` (нужен `CONTRACT_REFRESH_TOKEN`,
 * см. `contract/lib.sh`: с issue #358 `/v3/api-docs` отдаёт схему только под
 * Bearer). Фикстура может быть синтетической (`_meta.json` рядом,
 * `synthetic: true`) — тогда первый тест всё равно ловит выдуманные ручки
 * клиента, а второй ничего не находит нового, пока фикстуру не снимут с
 * живого стенда.
 */
class PathsContractTest {

    @Test
    fun `client does not call a path the backend does not have`() {
        val invented = (clientPaths() - serverPaths()).sorted()

        assertEquals(
            "клиент зовёт путь, которого нет у бэкенда, — выдуманная ручка " +
                "(дважды стоило переделки вертикали, см. docs/TASKS-BACKLOG.md, " +
                "T17). Если путь на самом деле есть — сними фикстуру заново: " +
                "contract/paths.sh --write app/src/test/resources/contract/paths/paths.json",
            emptyList<String>(),
            invented,
        )
    }

    @Test
    fun `backend paths the client does not call are reported, not failed`() {
        val client = clientPaths()
        val unused = (serverPaths() - client).sorted()
        val unknown = unused.filter { NotMobileClientPaths.reasonFor(it) == null }

        println("У бэкенда есть, клиент не зовёт: ${unused.size}")
        NotMobileClientPaths.GROUPS.forEach { group ->
            val count = unused.count(group::matches)
            if (count > 0) println("  · ${group.reason}: $count")
        }
        if (unknown.isNotEmpty()) {
            println(
                "  · без объяснённой группы (добавь в NotMobileClientPaths.kt, " +
                    "если это норма, или вызови из клиента): ${unknown.size}",
            )
            unknown.forEach { println("      $it") }
        }
    }

    /** Санитарная проверка самого разбора: путь с геопараметром узнаётся. */
    @Test
    fun `retrofit placeholders normalize the same way the shell script does`() {
        val client = clientPaths()
        assertTrue("ожидали путь с нормализованным {}", "GET places/{}" in client)
        assertTrue("ожидали DELETE с нормализованным {}", "DELETE reviews/{}" in client)
    }

    private fun clientPaths(): Set<String> =
        apiFiles()
            .flatMap { file -> RETROFIT_ANNOTATION.findAll(file.readText()) }
            .map { normalize(it.groupValues[1], it.groupValues[2]) }
            .toSet()

    private fun serverPaths(): Set<String> {
        val root = ContractSample.load("paths", "paths")
        assumeTrue(
            "фикстура contract/paths/paths.json не снята — запусти contract/paths.sh --write " +
                "app/src/test/resources/contract/paths/paths.json",
            root != null,
        )
        return root!!.getValue("server").jsonArray.map { it.jsonPrimitive.content }.toSet()
    }

    /** Рабочая директория Gradle-тестов — папка модуля, но запуск из корня репозитория даёт другой путь. */
    private fun apiFiles(): List<File> {
        val sourceRoot = listOf(File("src/main"), File("app/src/main"))
            .firstOrNull(File::exists)
            ?: error("не нашёл src/main (cwd=${File("").absolutePath})")
        return sourceRoot.walkTopDown().filter { it.isFile && it.name.endsWith("Api.kt") }.toList()
    }

    private fun normalize(method: String, rawPath: String): String {
        val withoutQuery = rawPath.substringBefore('?')
        val withPlaceholders = PLACEHOLDER.replace(withoutQuery, "{}")
        return "$method ${withPlaceholders.trimEnd('/')}"
    }

    private companion object {
        val RETROFIT_ANNOTATION = Regex("@(GET|POST|PUT|DELETE|PATCH)\\(\"([^\"]+)\"\\)")
        val PLACEHOLDER = Regex("\\{[^}]*}")
    }
}
