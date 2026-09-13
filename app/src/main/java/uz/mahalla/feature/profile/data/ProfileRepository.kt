package uz.mahalla.feature.profile.data

import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.data.network.payload
import uz.mahalla.data.prefs.UserProfile
import uz.mahalla.data.prefs.UserProfileStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Профиль на сервере, а не только то, что приехало с входом (issue #170).
 *
 * Интерфейс — ради теста ViewModel: экран проверяется без MockWebServer.
 */
interface ProfileRepository {

    /**
     * Перечитать профиль с сервера и сохранить его в [UserProfileStore] —
     * источник истины один. Отказ (сети, сервера) не трогает уже сохранённый
     * профиль: то, что приехало со входом, остаётся на экране, а не
     * очищается несостоявшимся ответом.
     */
    suspend fun refresh(): ApiResult<Unit>

    /**
     * @param fullName новое имя, `null` — не менять.
     * @param avatarUrl новый адрес фото, `null` — не менять, пустая строка —
     * снять аватар.
     *
     * Сервер отвечает всем профилем разом — им и заменяется локальная копия,
     * а не только изменённые поля: после `PUT` источник истины уже он.
     */
    suspend fun updateProfile(fullName: String? = null, avatarUrl: String? = null): ApiResult<Unit>
}

@Singleton
class DefaultProfileRepository @Inject constructor(
    private val profileApi: ProfileApi,
    private val userProfileStore: UserProfileStore,
) : ProfileRepository {

    override suspend fun refresh(): ApiResult<Unit> = apiCall {
        userProfileStore.save(profileApi.getMe().payload().toUserProfile())
    }

    override suspend fun updateProfile(fullName: String?, avatarUrl: String?): ApiResult<Unit> =
        apiCall {
            val body = UpdateMeRequest(fullName = fullName, avatarUrl = avatarUrl)
            userProfileStore.save(profileApi.updateMe(body).payload().toUserProfile())
        }
}

private fun MeResponseDto.toUserProfile(): UserProfile = UserProfile(
    id = id,
    phone = phone,
    fullName = fullName,
    avatarUrl = avatarUrl,
    serverRole = role,
    verificationStatus = verificationStatus,
    accountStatus = accountStatus,
)
