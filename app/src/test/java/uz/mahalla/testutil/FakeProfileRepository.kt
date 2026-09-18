package uz.mahalla.testutil

import kotlinx.coroutines.CompletableDeferred
import uz.mahalla.core.result.ApiResult
import uz.mahalla.data.prefs.UserProfileStore
import uz.mahalla.feature.profile.data.ProfileRepository

/**
 * `GET`/`PUT users/me` в памяти (issue #170): ViewModel профиля проверяется
 * без MockWebServer.
 *
 * На успехе пишет в переданный [store] — так же, как настоящий
 * `DefaultProfileRepository`: имя и аватар должны попасть в одно и то же
 * хранилище, откуда их читает шапка.
 *
 * @param gate когда задан, `updateProfile` не возвращается, пока его не
 * завершат: так проверяется, что второе сохранение не стартует, пока первое
 * ещё в полёте.
 */
class FakeProfileRepository(
    private val store: UserProfileStore,
    var gate: CompletableDeferred<Unit>? = null,
) : ProfileRepository {

    var refreshResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var updateResult: ApiResult<Unit> = ApiResult.Success(Unit)

    var refreshCount: Int = 0
        private set
    val updates = mutableListOf<Pair<String?, String?>>()

    override suspend fun refresh(): ApiResult<Unit> {
        refreshCount++
        return refreshResult
    }

    override suspend fun updateProfile(fullName: String?, avatarUrl: String?): ApiResult<Unit> {
        updates += fullName to avatarUrl
        gate?.await()
        if (updateResult is ApiResult.Success) {
            val current = store.current()
            store.save(
                current.copy(
                    fullName = fullName ?: current.fullName,
                    avatarUrl = avatarUrl ?: current.avatarUrl,
                    fullNamePendingSync = false,
                ),
            )
        }
        return updateResult
    }
}
