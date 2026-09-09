package uz.mahalla.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Уход на экран входа при смерти сессии (issue #138).
 *
 * Тест на композицию, а не на ViewModel: проверять надо именно навигацию —
 * куда уходим, что остаётся в стеке и откуда уходить нельзя. Раньше этого
 * поведения не было вовсе, и человек оставался внутри приложения, где каждый
 * экран отвечал 401.
 *
 * Граф здесь свой, из заглушек, но на **настоящих** маршрутах проекта
 * (`Routes.kt`) и настоящем `NavHost`: композиция `MahallaNavHost` поднимала
 * бы экраны с `hiltViewModel()`, то есть весь граф DI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SessionExpiryEffectTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Как в проде: `SessionExpiry` без replay, отправка из не-suspend кода. */
    private val expired = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var explained = 0
    private lateinit var navController: NavHostController

    @Test
    fun `a dead session sends a main graph screen to login`() {
        setContent(startDestination = MainGraph)
        navigate(WalletRoute)

        expire()

        assertRoute<WelcomeRoute>()
        assertEquals("причину надо объяснить: экран входа сам собой пугает", 1, explained)
        // Стек чистится целиком: «назад» в приложение без сессии вести некуда.
        assertNull(
            "основной граф остался в стеке",
            compose.runOnIdle { navController.previousBackStackEntry },
        )
    }

    @Test
    fun `a detail screen outside the main graph goes to login too`() {
        // Экран из уведомления (`DeepLinks`) лежит вне `MainGraph`, и
        // `popUpTo(MainGraph)` его бы не тронул.
        setContent(startDestination = MapRoute)

        expire()

        assertRoute<WelcomeRoute>()
        assertNull(compose.runOnIdle { navController.previousBackStackEntry })
    }

    @Test
    fun `onboarding is not interrupted`() {
        // Сессии там и не было: человек как раз входит, и welcome посреди
        // ввода номера стёр бы шаг.
        setContent(startDestination = OnboardingGraph)
        navigate(PhoneRoute)

        expire()

        assertRoute<PhoneRoute>()
        assertEquals("объяснять нечего — никуда не уходили", 0, explained)
    }

    @Test
    fun `the backend address screen is not interrupted`() {
        // Он стоит до входа (issue #26) и ведёт дальше сам.
        setContent(startDestination = BackendUrlRoute)

        expire()

        assertRoute<BackendUrlRoute>()
        assertEquals(0, explained)
    }

    @Test
    fun `the update screen is not interrupted`() {
        setContent(startDestination = UpdateRoute)

        expire()

        assertRoute<UpdateRoute>()
        assertEquals(0, explained)
    }

    @Test
    fun `a second event does not stack a second login screen`() {
        // Параллельные запросы упираются в 401 пачкой, и событий приезжает
        // столько же: два экрана входа в стеке дали бы «назад» с входа на вход.
        setContent(startDestination = MainGraph)

        expire()
        expire()

        assertRoute<WelcomeRoute>()
        assertNull(compose.runOnIdle { navController.previousBackStackEntry })
    }

    /** Событие + ожидание: обработчик живёт в корутине `LaunchedEffect`. */
    private fun expire() {
        compose.runOnIdle { expired.tryEmit(Unit) }
        // Настоящей навигации не было бы видно сразу; отрицательным проверкам
        // это же время даёт шанс сломаться.
        compose.mainClock.advanceTimeBy(TICK_MILLIS)
        compose.waitForIdle()
    }

    private fun navigate(route: Any) {
        compose.runOnIdle { navController.navigate(route) }
        compose.waitForIdle()
    }

    private inline fun <reified T : Any> assertRoute() {
        val destination = compose.runOnIdle { navController.currentDestination }
        assertTrue(
            "оказались не там: ${destination?.route}",
            destination?.hasRoute(T::class) == true,
        )
    }

    /**
     * Граф-заглушка формой как настоящий: онбординг и основной раздел —
     * вложенные графы, детали и предвходные экраны — маршруты верхнего уровня.
     */
    private fun setContent(startDestination: Any) {
        compose.setContent {
            navController = rememberNavController()
            SessionExpiryEffect(
                navController = navController,
                sessionExpired = expired,
                onExpired = { explained++ },
            )
            NavHost(navController = navController, startDestination = startDestination) {
                composable<BackendUrlRoute> { Stub() }
                composable<UpdateRoute> { Stub() }
                composable<MapRoute> { Stub() }
                navigation<OnboardingGraph>(startDestination = WelcomeRoute) {
                    composable<WelcomeRoute> { Stub() }
                    composable<PhoneRoute> { Stub() }
                }
                navigation<MainGraph>(startDestination = DiscoveryRoute) {
                    composable<DiscoveryRoute> { Stub() }
                    composable<WalletRoute> { Stub() }
                }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun Stub() {
        Text(text = "stub")
    }

    private companion object {
        const val TICK_MILLIS = 1_000L
    }
}
