package uz.mahalla.testutil

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import uz.mahalla.feature.discovery.data.SearchHistoryStore
import uz.mahalla.feature.discovery.domain.SearchHistory

/**
 * История поиска в памяти. Правила порядка и дублей берутся из того же
 * [SearchHistory], что и в проде, — фейк отличается только хранилищем.
 */
class FakeSearchHistoryStore(initial: List<String> = emptyList()) : SearchHistoryStore {

    private val state = MutableStateFlow(initial)

    override val queries: Flow<List<String>> = state

    fun current(): List<String> = state.value

    override suspend fun add(query: String) {
        state.value = SearchHistory.add(state.value, query)
    }

    override suspend fun remove(query: String) {
        state.value = SearchHistory.remove(state.value, query)
    }

    override suspend fun clear() {
        state.value = emptyList()
    }
}
