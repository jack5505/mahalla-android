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
     *
     * Имя из анкеты покупателя (`RoleRepository.saveCustomer`, issue #234)
     * пишется в [UserProfileStore] раньше, чем доезжает до сервера — анкета не
     * должна запирать человека до ответа сети. Пока оно не подтверждено
     * (`fullNamePendingSync`), [refresh] шлёт его через `PUT`, а не `GET`:
     * обычный `GET` вернул бы `fullName = null` и стёр бы то, что сервер ещё
     * не видел.
     */
    suspend fun refresh(): ApiResult<Unit>

    /**
     * @param fullName новое имя, `null` — не менять.
     * @param avatarUrl новый адрес фото, `null` — не менять, пустая строка —
     * снять аватар.
     *
     * Сервер отвечает всем профилем разом — им и заменяется локальная копия,
     * а не только изменённые поля: после `PUT` источник истины уже он.
     *
     * Исключение — отложенное имя из анкеты покупателя
     * (`UserProfile.fullNamePendingSync`, issue #234): если [fullName] в этом
     * вызове `null` (имя в запросе не несём), а флаг в сторе ещё стоит,
     * локальное имя и флаг сохраняются поверх серверного ответа, чтобы
     * следующий `refresh()` смог повторить `PUT`. Иначе `PUT {avatarUrl}` без
     * имени получил бы ответ с `fullName = null` — имя из анкеты пропало бы
     * навсегда, не добравшись до сервера.
     */
    suspend fun updateProfile(fullName: String? = null, avatarUrl: String? = null): ApiResult<Unit>
}

@Singleton
class DefaultProfileRepository @Inject constructor(
    private val profileApi: ProfileApi,
    private val userProfileStore: UserProfileStore,
) : ProfileRepository {

    override suspend fun refresh(): ApiResult<Unit> {
        val pendingFullName = userProfileStore.current()
            .takeIf { it.fullNamePendingSync }
            ?.fullName
            ?.takeIf { it.isNotBlank() }
        return if (pendingFullName != null) {
            updateProfile(fullName = pendingFullName)
        } else {
            apiCall { userProfileStore.save(profileApi.getMe().payload().toUserProfile()) }
        }
    }

    override suspend fun updateProfile(fullName: String?, avatarUrl: String?): ApiResult<Unit> =
        apiCall {
            val pending = userProfileStore.current().takeIf { it.fullNamePendingSync }
            val body = UpdateMeRequest(fullName = fullName, avatarUrl = avatarUrl)
            val updated = profileApi.updateMe(body).payload().toUserProfile()
            // Если этот PUT не нёс имя, а в сторе ещё висит неподтверждённое
            // имя из анкеты, — сохраняем его поверх серверного ответа, иначе
            // имя из анкеты потеряется навсегда (сервер вернёт null, флаг
            // сбросится, следующий refresh пойдёт GET, а не PUT).
            val toSave = if (fullName == null && pending != null) {
                updated.copy(fullName = pending.fullName, fullNamePendingSync = true)
            } else {
                updated
            }
            userProfileStore.save(toSave)
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
