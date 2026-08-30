package uz.mahalla.testutil

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import uz.mahalla.data.prefs.UserProfile
import uz.mahalla.data.prefs.UserProfileStore

/** Профиль в памяти: тестам входа и профиля DataStore не нужен (issue #61). */
class FakeUserProfileStore(initial: UserProfile = UserProfile()) : UserProfileStore {

    private val state = MutableStateFlow(initial)

    /** Сколько раз профиль стирали — так видно, что выход его почистил. */
    var clearCount: Int = 0
        private set

    override val profile: Flow<UserProfile> = state

    override suspend fun current(): UserProfile = state.value

    override suspend fun save(profile: UserProfile) {
        state.value = profile
    }

    override suspend fun clear() {
        clearCount++
        state.value = UserProfile()
    }
}
