package uz.mahalla.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Кто вошёл: имя, номер и аватар аккаунта.
 *
 * Данные приезжают **только** в ответе на вход (`verify-otp`, `setup-pin`,
 * `pin-login`, `telegram/check`): `GET /users/me` у бэкенда нет, спросить
 * профиль отдельно нечем. Поэтому его сохраняет репозиторий авторизации, а
 * профиль читает из хранилища.
 *
 * @param fullName пустое имя — нормальный случай: у нового пользователя
 * бэкенд его не знает (по этому же признаку считается `isNewUser`).
 */
data class UserProfile(
    val id: String? = null,
    val phone: String? = null,
    val fullName: String? = null,
    val avatarUrl: String? = null,
) {

    /** Пусто — входа ещё не было либо ответ пришёл без блока `user`. */
    val isEmpty: Boolean
        get() = id == null && phone == null && fullName == null && avatarUrl == null

    /**
     * Инициалы для аватара-заглушки: «Jahongir Sabirov» → «JS». Картинок в
     * приложении пока нет (загрузчик изображений — отдельная задача), а
     * безымянный серый круг не отвечает на вопрос «кто вошёл».
     *
     * Регистр приводится по [Locale.ROOT]: на турецкой локали устройства `i`
     * иначе превращается в `İ`.
     */
    fun initials(): String = fullName
        ?.trim()
        ?.split(WHITESPACE)
        ?.filter { it.isNotBlank() }
        ?.take(MAX_INITIALS)
        ?.joinToString(separator = "") { it.first().uppercase(Locale.ROOT) }
        .orEmpty()

    private companion object {
        val WHITESPACE = Regex("\\s+")
        const val MAX_INITIALS = 2
    }
}

/**
 * Хранилище профиля. За интерфейсом — ради тестов: от него зависит и
 * репозиторий авторизации (пишет), и ViewModel профиля (читает).
 */
interface UserProfileStore {
    val profile: Flow<UserProfile>
    suspend fun current(): UserProfile
    suspend fun save(profile: UserProfile)
    suspend fun clear()
}

@Singleton
class DataStoreUserProfileStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : UserProfileStore {

    override val profile: Flow<UserProfile> = dataStore.data
        .map { preferences ->
            UserProfile(
                id = preferences[PreferenceKeys.ProfileUserId],
                phone = preferences[PreferenceKeys.ProfilePhone],
                fullName = preferences[PreferenceKeys.ProfileFullName],
                avatarUrl = preferences[PreferenceKeys.ProfileAvatarUrl],
            )
        }
        .distinctUntilChanged()
        // Профиль — подпись на экране, а не условие работы приложения:
        // недоступный файл настроек показывает пустую шапку, но не роняет
        // профиль и не мешает выйти из аккаунта.
        .catch { emit(UserProfile()) }

    override suspend fun current(): UserProfile = profile.first()

    /**
     * Поле, которого нет в ответе, стирается, а не остаётся от прошлого входа:
     * иначе после входа под другим номером в шапке висело бы чужое имя.
     */
    override suspend fun save(profile: UserProfile) {
        dataStore.edit { preferences ->
            preferences.put(PreferenceKeys.ProfileUserId, profile.id)
            preferences.put(PreferenceKeys.ProfilePhone, profile.phone)
            preferences.put(PreferenceKeys.ProfileFullName, profile.fullName)
            preferences.put(PreferenceKeys.ProfileAvatarUrl, profile.avatarUrl)
        }
    }

    override suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(PreferenceKeys.ProfileUserId)
            preferences.remove(PreferenceKeys.ProfilePhone)
            preferences.remove(PreferenceKeys.ProfileFullName)
            preferences.remove(PreferenceKeys.ProfileAvatarUrl)
        }
    }

    private fun MutablePreferences.put(key: Preferences.Key<String>, value: String?) {
        val cleaned = value?.takeIf { it.isNotBlank() }
        if (cleaned == null) remove(key) else set(key, cleaned)
    }
}
