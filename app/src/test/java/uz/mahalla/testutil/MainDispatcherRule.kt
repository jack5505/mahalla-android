package uz.mahalla.testutil

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * `viewModelScope` живёт на `Dispatchers.Main`, которого в JVM-тестах нет.
 *
 * Диспетчер отдаётся наружу нарочно: тест должен запускаться как
 * `runTest(mainDispatcherRule.dispatcher)`, иначе у `runTest` и у ViewModel
 * будут разные планировщики, и `advanceTimeBy` не двинет таймер внутри
 * ViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
