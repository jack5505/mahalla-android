package uz.mahalla.data.push

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import uz.mahalla.data.prefs.PreferenceKeys
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Токен FCM на устройстве (эпик 11).
 *
 * Хранилище нужно потому, что **отправить токен отдельным запросом нечем**:
 * ручки регистрации устройства у бэкенда нет (сверка `/v3/api-docs`
 * 2026-09-09), токен он принимает только полем `AuthDeviceInfo.fcmToken` в
 * запросах авторизации. Значит между приходом токена и следующим таким
 * запросом его надо где-то держать — здесь.
 *
 * Токен не секрет в смысле пароля, но и не публичен: по нему шлют пуш этому
 * устройству. Лежит там же, где остальные настройки, — отдельного
 * зашифрованного хранилища ради него не заводим (в отличие от PIN).
 */
@Singleton
class PushTokenStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    val token: Flow<String?> = dataStore.data
        .map { it[PreferenceKeys.FcmToken]?.takeIf(String::isNotBlank) }
        // Недоступный файл настроек — это «токена нет»: пуши не придут, но
        // приложение работает. Ронять на этом вход было бы хуже.
        .catch { emit(null) }

    suspend fun current(): String? = token.first()

    /**
     * Пустая строка — это «токена нет», а не токен из пробелов: ключ
     * удаляется, иначе запрос авторизации отправил бы бэкенду мусор, по
     * которому пуш никуда не уйдёт (то же правило, что у адреса доставки).
     */
    suspend fun save(token: String) {
        val cleaned = token.trim()
        dataStore.edit { preferences ->
            if (cleaned.isEmpty()) {
                preferences.remove(PreferenceKeys.FcmToken)
            } else {
                preferences[PreferenceKeys.FcmToken] = cleaned
            }
        }
    }
}
