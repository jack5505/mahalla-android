package uz.mahalla.resources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Паритет строковых ресурсов uz/ru (эпик 1.5).
 *
 * Забытый перевод не ломает сборку — приложение просто молча покажет
 * узбекский текст в русском интерфейсе. Плюс несовпадение
 * placeholder'ов (`%1$s`) роняет экран уже в рантайме, поэтому проверяется и
 * оно.
 */
class StringResourceParityTest {

    @Test
    fun `uz and ru declare the same keys`() {
        val uz = stringsOf("values")
        val ru = stringsOf("values-ru")

        assertEquals("нет перевода на ru", emptySet<String>(), uz.keys - ru.keys)
        assertEquals("лишние ключи в values-ru", emptySet<String>(), ru.keys - uz.keys)
    }

    @Test
    fun `placeholders match between locales`() {
        val uz = stringsOf("values")
        val ru = stringsOf("values-ru")

        uz.forEach { (key, value) ->
            assertEquals(
                "placeholder'ы не совпадают для '$key'",
                placeholders(value),
                placeholders(ru.getValue(key)),
            )
        }
    }

    @Test
    fun `no string is blank`() {
        listOf("values", "values-ru").forEach { directory ->
            stringsOf(directory).forEach { (key, value) ->
                assertTrue("$directory/$key пустая", value.isNotBlank())
            }
        }
    }

    @Test
    fun `default locale is uzbek`() {
        // values/ — язык по умолчанию (ТЗ §1). Русская локаль отдельно.
        assertTrue(stringsOf("values").getValue("nav_wallet") == "Hamyon")
        assertTrue(stringsOf("values-ru").getValue("nav_wallet") == "Кошелёк")
    }

    private fun stringsOf(directory: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(resourceFile("$directory/strings.xml"))
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .filter { it.getAttribute("translatable") != "false" }
            .associate { it.getAttribute("name") to it.textContent }
    }

    /** `%1$s`, `%2$d`, … — позиционные аргументы формата. */
    private fun placeholders(value: String): Set<String> =
        PLACEHOLDER.findAll(value).map { it.value }.toSet()

    /**
     * Рабочая директория Gradle-тестов — папка модуля, но при запуске из
     * корня репозитория путь другой; проверяем оба.
     */
    private fun resourceFile(relative: String): File =
        listOf(
            File("src/main/res/$relative"),
            File("app/src/main/res/$relative"),
        ).firstOrNull(File::exists)
            ?: error("Не найден ресурс $relative (cwd=${File("").absolutePath})")

    private companion object {
        /**
         * `%1$s`, `%2$d`, … Знак доллара собран из кода символа: в исходнике
         * он иначе требует экранирования и легко ломается при правке.
         */
        val PLACEHOLDER = Regex("%\\d+" + Char(0x24) + "[sdf]")
    }
}
