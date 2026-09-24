package uz.mahalla.navigation

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navDeepLink
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Отложенный deep link из пуша, пока стартовый пункт — гейт (issue #160).
 *
 * `NavHost.setGraph` сам разбирает `activity.intent` сразу после построения
 * графа и уходит `popUpTo(graph){inclusive=true}` — без отсрочки ссылка из
 * пуша вытеснила бы `BackendUrlRoute`/`UpdateRoute` раньше, чем человек до них
 * дошёл. Тест на композицию с настоящим `NavHost` и настоящими маршрутами, как
 * `SessionExpiryEffectTest`: `pendingDeepLink` здесь играет роль интента,
 * который `MainActivity` забрала бы у `NavHost`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DeferredDeepLinkEffectTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController

    @Test
    fun `a deep link is held back while the backend url gate is active`() {
        setContent(startDestination = BackendUrlRoute, pendingDeepLink = placeDeepLink("42"))

        assertRoute<BackendUrlRoute>()

        leaveGate(gate = BackendUrlRoute, appStart = MainGraph)

        assertRoute<PlaceRoute>()
    }

    @Test
    fun `a deep link is held back while the update gate is active`() {
        setContent(startDestination = UpdateRoute, pendingDeepLink = placeDeepLink("42"))

        assertRoute<UpdateRoute>()

        leaveGate(gate = UpdateRoute, appStart = MainGraph)

        assertRoute<PlaceRoute>()
    }

    @Test
    fun `without a pending deep link the gate leads to the normal start`() {
        setContent(startDestination = BackendUrlRoute, pendingDeepLink = null)

        leaveGate(gate = BackendUrlRoute, appStart = MainGraph)

        assertRoute<DiscoveryRoute>()
    }

    @Test
    fun `the deep link is consumed only once`() {
        setContent(startDestination = BackendUrlRoute, pendingDeepLink = placeDeepLink("42"))

        leaveGate(gate = BackendUrlRoute, appStart = MainGraph)
        assertRoute<PlaceRoute>()

        // Дальнейшая навигация — обычные переходы человека внутри приложения,
        // а не повторный разбор той же ссылки: второй раз на неё уводить
        // нельзя, иначе таб переключался бы обратно на карточку заведения.
        navigate(WalletRoute)

        assertRoute<WalletRoute>()
    }

    private fun placeDeepLink(placeId: String): Intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse(DeepLinks.place(placeId)),
    )

    /** Симулирует уход с гейта — то, что делают `onSaved`/`onContinue`. */
    private fun leaveGate(gate: Any, appStart: Any) {
        compose.runOnIdle {
            navController.navigate(appStart) {
                popUpTo(gate) { inclusive = true }
            }
        }
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
     * Граф-заглушка формой как настоящий: гейты и карточка заведения — вне
     * графов, основной раздел — вложенный граф, как в `MahallaNavHost`.
     */
    private fun setContent(startDestination: Any, pendingDeepLink: Intent?) {
        compose.setContent {
            navController = androidx.navigation.compose.rememberNavController()
            DeferredDeepLinkEffect(navController = navController, pendingDeepLink = pendingDeepLink)
            NavHost(navController = navController, startDestination = startDestination) {
                composable<BackendUrlRoute> { Stub() }
                composable<UpdateRoute> { Stub() }
                composable<PlaceRoute>(
                    deepLinks = listOf(navDeepLink { uriPattern = DeepLinks.PLACE_PATTERN }),
                ) { Stub() }
                navigation<OnboardingGraph>(startDestination = WelcomeRoute) {
                    composable<WelcomeRoute> { Stub() }
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
}
