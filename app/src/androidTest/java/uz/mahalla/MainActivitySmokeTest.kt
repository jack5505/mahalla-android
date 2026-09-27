package uz.mahalla

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke-тест релиза (issue #347): единственная проверка на настоящем
 * Android-рантайме, а не на Robolectric. Ловит то, чего Robolectric не видит
 * — разбитый граф Hilt, забытую запись в манифесте, крэш на настоящем
 * `WindowManager`/`Looper`.
 *
 * Экран, на котором окажется свежий запуск (адрес бэкенда, онбординг или уже
 * авторизованные табы — см. `RootViewModel.resolveStart`), от состояния
 * DataStore/Keystore на устройстве и здесь не фиксируется: без фейковых
 * Hilt-модулей авторизации и онбординга (issue #347, открытый пункт) тест
 * не может гарантировать заранее, какой из них это будет. Цель этого теста —
 * пережить сборку графа и первую композицию, а не долистать до конкретного
 * маршрута.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun theAppStartsAndTheHiltGraphResolvesWithoutCrashing() {
        compose.waitForIdle()

        assertFalse(compose.activity.isFinishing)
    }
}
