package uz.mahalla.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import uz.mahalla.feature.discovery.ui.DiscoveryScreen
import uz.mahalla.feature.onboarding.ui.BiometricScreen
import uz.mahalla.feature.onboarding.ui.GeoScreen
import uz.mahalla.feature.onboarding.ui.OtpScreen
import uz.mahalla.feature.onboarding.ui.PhoneInputScreen
import uz.mahalla.feature.onboarding.ui.PinScreen
import uz.mahalla.feature.onboarding.ui.WelcomeScreen
import uz.mahalla.feature.orders.ui.OrdersScreen
import uz.mahalla.feature.place.ui.PlaceDetailsScreen
import uz.mahalla.feature.profile.ui.ProfileScreen
import uz.mahalla.feature.wallet.ui.WalletScreen

/**
 * Граф навигации (эпик 1.2): onboarding → main (bottom nav) → детали.
 *
 * Маршруты типизированные ([Routes.kt]); карточка заведения дополнительно
 * достижима по deep link'у `mahalla://place/{placeId}` и лежит вне обоих
 * графов — на неё можно прийти и из онбординга (по ссылке), и из main.
 */
@Composable
fun MahallaNavHost(
    navController: NavHostController,
    startDestination: Any,
    onOnboardingFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        navigation<OnboardingGraph>(startDestination = WelcomeRoute) {
            composable<WelcomeRoute> {
                WelcomeScreen(onContinue = { navController.navigate(PhoneRoute) })
            }
            composable<PhoneRoute> {
                PhoneInputScreen(
                    onCodeRequested = { phone -> navController.navigate(OtpRoute(phone)) },
                )
            }
            composable<OtpRoute> { entry ->
                OtpScreen(
                    phone = entry.toRoute<OtpRoute>().phone,
                    onVerified = { navController.navigate(PinRoute) },
                )
            }
            composable<PinRoute> {
                PinScreen(onPinSet = { navController.navigate(BiometricRoute) })
            }
            composable<BiometricRoute> {
                BiometricScreen(
                    onEnabled = { navController.navigate(GeoRoute) },
                    onSkipped = { navController.navigate(GeoRoute) },
                )
            }
            composable<GeoRoute> {
                GeoScreen(
                    onFinished = {
                        onOnboardingFinished()
                        navController.navigate(MainGraph) {
                            // Назад в онбординг возврата нет.
                            popUpTo(OnboardingGraph) { inclusive = true }
                        }
                    },
                )
            }
        }

        navigation<MainGraph>(startDestination = DiscoveryRoute) {
            composable<DiscoveryRoute> {
                DiscoveryScreen(
                    onPlaceClick = { placeId -> navController.navigate(PlaceRoute(placeId)) },
                )
            }
            composable<OrdersRoute> { OrdersScreen() }
            composable<WalletRoute> { WalletScreen() }
            composable<ProfileRoute> { ProfileScreen() }
        }

        composable<PlaceRoute>(
            deepLinks = listOf(navDeepLink { uriPattern = DeepLinks.PLACE_PATTERN }),
        ) { entry ->
            PlaceDetailsScreen(
                placeId = entry.toRoute<PlaceRoute>().placeId,
                onBack = { navController.navigateUp() },
            )
        }
    }
}
