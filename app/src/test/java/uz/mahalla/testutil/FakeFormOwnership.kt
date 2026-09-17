package uz.mahalla.testutil

import uz.mahalla.data.prefs.FormOwnership

/** Владелец анкеты в памяти: тестам входа DataStore не нужен (issue #243). */
class FakeFormOwnership : FormOwnership {

    /** Кого называл каждый вход — по порядку. */
    val claims: MutableList<String?> = mutableListOf()

    /** Отказ хранилища: вход обязан его пережить. */
    var failure: Exception? = null

    /** Что сделать в момент сверки — так тест видит, что к ней уже записано. */
    var onClaim: suspend () -> Unit = {}

    override suspend fun claimFor(userId: String?) {
        onClaim()
        failure?.let { throw it }
        claims += userId
    }
}
