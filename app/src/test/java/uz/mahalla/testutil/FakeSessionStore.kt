package uz.mahalla.testutil

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import uz.mahalla.data.prefs.Session
import uz.mahalla.data.prefs.SessionStore

/** Сессия в памяти: сетевые тесты не должны зависеть от DataStore. */
class FakeSessionStore(initial: Session? = null) : SessionStore {

    private val state = MutableStateFlow(initial)

    /** Сколько раз сессию перезаписали — так видно лишние refresh-запросы. */
    var saveCount: Int = 0
        private set

    override val session: Flow<Session?> = state

    override suspend fun current(): Session? = state.value

    override suspend fun save(session: Session) {
        saveCount++
        state.value = session
    }

    override suspend fun clear() {
        state.value = null
    }
}
