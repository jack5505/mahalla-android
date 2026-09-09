package uz.mahalla.data.network.contract

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import uz.mahalla.data.network.NetworkFactory

/**
 * Ответы живого стенда, снятые скриптами из `contract/`, — вторая половина
 * контрактной сверки.
 *
 * Скрипт отвечает на вопрос «ручка жива и каким кодом», а разбор полей идёт
 * здесь, на сохранённом ответе: тест гоняется обычным `testDebugUnitTest`,
 * без бэкенда, и потому переживает прогон, в который снимался.
 *
 * **Почему сверяем имена полей, а не просто «разобралось ли».** Разбор в этом
 * проекте нарочно мягкий: [NetworkFactory.json] стоит с `ignoreUnknownKeys`,
 * а поля DTO нullable с дефолтами (`.claude/rules/network.md` — одно битое
 * поле не должно ронять список). Значит `decodeFromString` смолчит и когда
 * сервер прислал что-то новое, и когда не прислал ничего: экран останется
 * пустым, а тест — зелёным. Поэтому имена сверяются явно.
 */
object ContractSample {

    /** `null` — проба ещё не снята: тест такое пропускает, а не валит. */
    fun load(vertical: String, name: String): JsonObject? {
        val stream = ContractSample::class.java.classLoader
            ?.getResourceAsStream("contract/$vertical/$name.json")
            ?: return null
        val text = stream.bufferedReader().use { it.readText() }
        return NetworkFactory.json().parseToJsonElement(text).jsonObject
    }

    /**
     * Объекты, по которым имеет смысл сверять поля. Массив строк (слоты) даёт
     * пустой список — сверять там нечего.
     */
    fun objectsIn(element: JsonElement?): List<JsonObject> = when (element) {
        is JsonObject -> listOf(element)
        is JsonArray -> element.filterIsInstance<JsonObject>()
        else -> emptyList()
    }

    /**
     * Что сервер прислал, а DTO не объявил, — то самое, что тихо теряется на
     * мягком разборе.
     *
     * Считается по объединению ключей всех объектов: у одной записи поле может
     * быть опущено, у другой — прийти.
     */
    fun unknownToClient(objects: List<JsonObject>, serializer: KSerializer<*>): Set<String> =
        objects.flatMapTo(mutableSetOf()) { it.keys } - declaredNames(serializer)

    /**
     * Что DTO объявил, а сервер ни разу не прислал: либо поле убрали с
     * бэкенда, либо его там не было никогда и экран под него пуст.
     *
     * Законные исключения перечисляет вызывающий: `ServiceDto` нарочно
     * объявляет и `isActive`, и `active` — Jackson отдаёт то одно, то другое,
     * поэтому одного из двух в ответе не будет всегда.
     */
    fun declaredButAbsent(objects: List<JsonObject>, serializer: KSerializer<*>): Set<String> =
        declaredNames(serializer) - objects.flatMapTo(mutableSetOf()) { it.keys }

    @OptIn(ExperimentalSerializationApi::class)
    private fun declaredNames(serializer: KSerializer<*>): Set<String> =
        serializer.descriptor.elementNames.toSet()
}
