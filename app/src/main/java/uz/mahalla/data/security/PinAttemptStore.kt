package uz.mahalla.data.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import uz.mahalla.data.prefs.PreferenceKeys
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Сколько раз подряд на экране блокировки ввели неверный PIN.
 *
 * Счётчик переехал сюда из памяти ViewModel, как предписывает ADR 0004
 * («при появлении app-lock переносить в DataStore»). В памяти он обходился
 * тривиально: снять приложение из недавних — и снова пять попыток. Пока PIN
 * спрашивали только на входе, это стоило немного (лимит там всё равно ведёт
 * бэкенд, issue #51), но замок проверяет код **локально**, и считать попытки
 * серверу нечем.
 *
 * Счётчик принадлежит сохранённому коду: [KeystorePinStorage] стирает его и
 * при `save`, и при `clear`, поэтому вход, выход и смена PIN всегда начинаются
 * с полного набора попыток.
 */
interface PinAttemptStore {

    /** Сколько неверных попыток уже накоплено. */
    suspend fun failedAttempts(): Int

    /** Записать неверную попытку и вернуть новое значение счётчика. */
    suspend fun recordFailure(): Int

    /** Верный код или новый вход: счётчик обнуляется. */
    suspend fun reset()
}

/**
 * Счётчик в том же DataStore, где PIN и сессия.
 *
 * Шифровать нечего: значение и так видно по поведению экрана, а ключ Keystore
 * ради одного целого числа добавил бы ещё один способ отказать — и тогда
 * отказ хранилища решал бы, пускать человека в приложение.
 */
@Singleton
class DataStorePinAttemptStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : PinAttemptStore {

    override suspend fun failedAttempts(): Int =
        dataStore.data.first()[PreferenceKeys.PinFailedAttempts] ?: 0

    override suspend fun recordFailure(): Int {
        var stored = 0
        dataStore.edit { preferences ->
            stored = (preferences[PreferenceKeys.PinFailedAttempts] ?: 0) + 1
            preferences[PreferenceKeys.PinFailedAttempts] = stored
        }
        return stored
    }

    override suspend fun reset() {
        dataStore.edit { preferences -> preferences.remove(PreferenceKeys.PinFailedAttempts) }
    }
}
