package uz.mahalla.feature.discovery.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import uz.mahalla.data.prefs.PreferenceKeys
import uz.mahalla.feature.discovery.domain.SearchHistory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * История поиска (эпик 4.3).
 *
 * Интерфейс — как у [uz.mahalla.data.prefs.SessionStore]: ViewModel поиска
 * тестируется с фейком в памяти, без DataStore и Robolectric.
 */
interface SearchHistoryStore {

    val queries: Flow<List<String>>

    suspend fun add(query: String)

    suspend fun remove(query: String)

    suspend fun clear()
}

/**
 * Порядок и дедупликация живут в [SearchHistory] — здесь только чтение и
 * запись. Ошибка чтения гасится пустым списком: история это удобство, из-за
 * неё экран поиска открываться не перестанет.
 */
@Singleton
class DataStoreSearchHistoryStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SearchHistoryStore {

    override val queries: Flow<List<String>> = dataStore.data
        .map { SearchHistory.decode(it[PreferenceKeys.SearchHistory]) }
        .catch { emit(emptyList()) }

    override suspend fun add(query: String) = update { SearchHistory.add(it, query) }

    override suspend fun remove(query: String) = update { SearchHistory.remove(it, query) }

    override suspend fun clear() = update { emptyList() }

    private suspend fun update(transform: (List<String>) -> List<String>) {
        dataStore.edit { preferences ->
            val current = SearchHistory.decode(preferences[PreferenceKeys.SearchHistory])
            preferences[PreferenceKeys.SearchHistory] = SearchHistory.encode(transform(current))
        }
    }
}
