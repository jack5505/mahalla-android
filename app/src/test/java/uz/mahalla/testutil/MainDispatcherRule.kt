package uz.mahalla.testutil

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Подменяет Main-диспетчер тестовым: `viewModelScope` завязан на Main, и без
 * этого любая ViewModel с корутиной падает в JVM-тесте.
 *
 * По умолчанию [UnconfinedTestDispatcher] — корутина выполняется сразу на
 * месте запуска, поэтому проверка состояния не требует `advanceUntilIdle()`
 * после каждого события. Там, где важна именно задержка (debounce поиска),
 * тест передаёт свой `StandardTestDispatcher` и управляет временем сам.
 */
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
