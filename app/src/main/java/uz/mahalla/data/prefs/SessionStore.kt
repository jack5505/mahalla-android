package uz.mahalla.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import uz.mahalla.data.security.SessionCipher
import java.util.Base64
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
    /**
     * Идентификатор сессии на бэкенде (issue #42). Уходит в `X-Session-Id`
     * при выходе, чтобы сервер погасил именно эту сессию, а не все.
     * `null` — сервер его не прислал.
     */
    val sessionId: String? = null,
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

/**
 * Токены сессии в DataStore шифруются ключом Keystore (issue #319): рут или
 * бэкап читают файл префов открытым текстом, а refresh-токен — это доступ ко
 * всему аккаунту, включая кошелёк.
 *
 * Миграция без отдельного флага: сохранённое значение сперва пробуют
 * раскодировать как Base64 и расшифровать, и только если это не удалось (нет
 * Keystore-ключа, битые данные или значение — токен, записанный версией
 * приложения до этой задачи) используют как есть. Подделать чужой шифртекст
 * так, чтобы GCM-тег совпал случайно, нельзя, поэтому одинаково опрометчивый
 * токен не спутать с настоящим шифротекстом. Следующий же `save` (обновление
 * токена по 401 или новый вход) перезаписывает значение уже зашифрованным —
 * молчаливой потери сессии на обновлении приложения при этом не происходит.
 */
@Singleton
class DataStoreSessionStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SessionCipher,
) : SessionStore {

    override val session: Flow<Session?> = dataStore.data
        .map { preferences ->
            val access = preferences[PreferenceKeys.SessionAccessToken]
            val refresh = preferences[PreferenceKeys.SessionRefreshToken]
            if (access.isNullOrEmpty() || refresh.isNullOrEmpty()) {
                null
            } else {
                Session(
                    accessToken = decode(access),
                    refreshToken = decode(refresh),
                    expiresAtEpochSeconds = preferences[PreferenceKeys.SessionExpiresAt]
                        ?: Session.UNKNOWN_EXPIRY,
                    sessionId = preferences[PreferenceKeys.SessionId],
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
            preferences[PreferenceKeys.SessionAccessToken] = encode(session.accessToken)
            preferences[PreferenceKeys.SessionRefreshToken] = encode(session.refreshToken)
            preferences[PreferenceKeys.SessionExpiresAt] = session.expiresAtEpochSeconds
            // Идентификатор сессии перезаписывается, только если он приехал:
            // обновление токенов может вернуться без него, а сессия при этом
            // та же самая — стирать её id значило бы разучиться выходить.
            session.sessionId?.let { preferences[PreferenceKeys.SessionId] = it }
        }
    }

    override suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(PreferenceKeys.SessionAccessToken)
            preferences.remove(PreferenceKeys.SessionRefreshToken)
            preferences.remove(PreferenceKeys.SessionExpiresAt)
            preferences.remove(PreferenceKeys.SessionId)
        }
    }

    private fun encode(token: String): String =
        Base64.getEncoder().encodeToString(cipher.encrypt(token.toByteArray(Charsets.UTF_8)))

    /**
     * Отказ Keystore (ключ потерян, устройство без него) не должен запирать
     * человека вне приложения (ADR 0013 — то же правило, что и у PIN): токен
     * тогда используется как есть, сервер честно ответит 401 и запустит
     * обычный refresh/выход, а не крэш здесь.
     */
    private fun decode(stored: String): String {
        val payload = runCatching { Base64.getDecoder().decode(stored) }.getOrNull()
            ?: return stored
        val plain = runCatching { cipher.decrypt(payload) }.getOrNull() ?: return stored
        return plain.toString(Charsets.UTF_8)
    }
}
