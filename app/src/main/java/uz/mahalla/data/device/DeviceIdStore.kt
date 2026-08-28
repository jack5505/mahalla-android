package uz.mahalla.data.device

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uz.mahalla.core.result.runCatchingCancellable
import uz.mahalla.data.prefs.PreferenceKeys
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Идентификатор установки, который бэкенд использует как ключ сессии
 * устройства.
 *
 * Свой UUID, а не `ANDROID_ID`: тот привязан к паре «устройство + аккаунт
 * Google», требует `Settings.Secure` и на части прошивок совпадает у разных
 * приложений. Здесь важно только одно — чтобы значение не менялось между
 * запусками, поэтому оно лежит в DataStore рядом с остальными настройками
 * (файл исключён из бэкапа, так что после переноса на новое устройство
 * идентификатор будет новым — это и правильно, устройство другое).
 *
 * Ошибку записи проглатываем осознанно: без идентификатора нельзя даже
 * запросить код из SMS, а недоступный DataStore — не повод запирать вход.
 * В таком запуске идентификатор живёт в памяти процесса.
 */
@Singleton
class DeviceIdStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    private val mutex = Mutex()

    @Volatile
    private var cached: String? = null

    suspend fun deviceId(): String {
        cached?.let { return it }
        // Под локом: два параллельных запроса иначе сгенерировали бы два
        // разных идентификатора и завели на бэкенде два устройства.
        return mutex.withLock {
            cached?.let { return@withLock it }
            val stored = runCatchingCancellable {
                dataStore.data.first()[PreferenceKeys.DeviceId]
            }.getOrNull()
            val id = stored?.takeIf { it.isNotBlank() } ?: generateAndStore()
            cached = id
            id
        }
    }

    private suspend fun generateAndStore(): String {
        val id = UUID.randomUUID().toString()
        runCatchingCancellable {
            dataStore.edit { it[PreferenceKeys.DeviceId] = id }
        }
        return id
    }
}
