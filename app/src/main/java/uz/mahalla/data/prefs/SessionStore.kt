package uz.mahalla.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Пара токенов текущей сессии.
 *
 * @param expiresAtEpochSeconds [UNKNOWN_EXPIRY], если сервер не сообщил срок
 * жизни: тогда о просроченности судить нельзя и решает 401 от сервера.
 */
data class Session(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long = UNKNOWN_EXPIRY,
) {
    companion object {
        const val UNKNOWN_EXPIRY = 0L
    }
}

/**
 * Хранилище сессии (эпик 1.4). Вынесено за интерфейс: сетевой слой
 * (`AuthInterceptor`, `TokenAuthenticator`) зависит от него, а тесты
 * подставляют фейк без DataStore.
 */
interface SessionStore {
    val session: Flow<Session?>
    suspend fun current(): Session?
    suspend fun save(session: Session)
    suspend fun clear()
}

@Singleton
class DataStoreSessionStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SessionStore {

    override val session: Flow<Session?> = dataStore.data
        .map { preferences ->
            val access = preferences[PreferenceKeys.SessionAccessToken]
            val refresh = preferences[PreferenceKeys.SessionRefreshToken]
            if (access.isNullOrEmpty() || refresh.isNullOrEmpty()) {
                null
            } else {
                Session(
                    accessToken = access,
                    refreshToken = refresh,
                    expiresAtEpochSeconds = preferences[PreferenceKeys.SessionExpiresAt]
                        ?: Session.UNKNOWN_EXPIRY,
                )
            }
        }
        .distinctUntilChanged()
        // Прочитать файл не удалось — считаем, что сессии нет (пользователь
        // залогинится заново). Кидать исключение в сетевой слой и в UI нельзя:
        // этот flow читается из интерсептора и из состояния корня.
        .catch { emit(null) }

    override suspend fun current(): Session? = session.first()

    override suspend fun save(session: Session) {
        dataStore.edit { preferences ->
            preferences[PreferenceKeys.SessionAccessToken] = session.accessToken
            preferences[PreferenceKeys.SessionRefreshToken] = session.refreshToken
            preferences[PreferenceKeys.SessionExpiresAt] = session.expiresAtEpochSeconds
        }
    }

    override suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(PreferenceKeys.SessionAccessToken)
            preferences.remove(PreferenceKeys.SessionRefreshToken)
            preferences.remove(PreferenceKeys.SessionExpiresAt)
        }
    }
}
