package uz.mahalla.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navDeepLink
import uz.mahalla.feature.discovery.ui.home.DiscoveryHomeScreen
import uz.mahalla.feature.discovery.ui.search.SearchScreen
import uz.mahalla.feature.map.ui.MapScreen
import uz.mahalla.feature.onboarding.ui.BackendUrlScreen
import uz.mahalla.feature.onboarding.ui.BiometricScreen
import uz.mahalla.feature.onboarding.ui.GeoScreen
import uz.mahalla.feature.onboarding.ui.OtpScreen
import uz.mahalla.feature.onboarding.ui.PhoneInputScreen
import uz.mahalla.feature.onboarding.ui.PinScreen
import uz.mahalla.feature.onboarding.ui.TelegramLoginScreen
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
 *
 * @param onboardingStartDestination где продолжается прерванный онбординг.
 * Сессия уже получена — начинать снова с welcome значит запросить второй
 * платный SMS-код; решение принимает `RootViewModel`.
 * @param afterBackendUrl куда уходить с экрана адреса бэкенда, когда он был
 * стартовым (issue #26): дальше начинается обычный старт приложения.
 * @param backendUrlOverrideEnabled разрешено ли этой сборке менять адрес
 * бэкенда. Выключено — маршрута и всех входов на него в графе нет: в релизе
 * увести приложение на чужой сервер не должен никто.
 */
@Composable
fun MahallaNavHost(
    navController: NavHostController,
    startDestination: Any,
    onOnboardingFinished: () -> Unit,
    modifier: Modifier = Modifier,
    onboardingStartDestination: Any = WelcomeRoute,
    afterBackendUrl: Any = OnboardingGraph,
    backendUrlOverrideEnabled: Boolean = false,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        // Адрес бэкенда (issue #26) — вне графов: без него ни один запрос не
        // уйдёт, поэтому экран стоит перед онбордингом, а вернуться на него
        // можно и позже — с welcome или из профиля.
        if (backendUrlOverrideEnabled) {
            composable<BackendUrlRoute> {
                // Стек пуст — экран стартовый, значит дальше начинается
                // приложение. Пришли с другого экрана — возвращаемся туда же.
                val openedAtStart = navController.previousBackStackEntry == null
                BackendUrlScreen(
                    onSaved = {
                        if (openedAtStart) {
                            navController.navigate(afterBackendUrl) {
                                popUpTo(BackendUrlRoute) { inclusive = true }
                            }
                        } else {
                            navController.navigateUp()
                        }
                    },
                    onBack = if (openedAtStart) null else ({ navController.navigateUp() }),
                )
            }
        }

        navigation<OnboardingGraph>(startDestination = onboardingStartDestination) {
            composable<WelcomeRoute> {
                WelcomeScreen(
                    onContinue = { navController.navigate(PhoneRoute) },
                    // Опечатку в адресе видно только здесь: исправить её
                    // иначе было бы негде.
                    onChangeServer = if (backendUrlOverrideEnabled) {
                        { navController.navigate(BackendUrlRoute) }
                    } else {
                        null
                    },
                )
            }
            composable<PhoneRoute> {
                PhoneInputScreen(
                    onCodeRequested = { phone, challenge ->
                        navController.navigate(
                            OtpRoute(
                                phone = phone,
                                otpToken = challenge.otpToken,
                                resendAfterSeconds = challenge.resendAfterSeconds,
                                codeLength = challenge.codeLength,
                            ),
                        )
                    },
                    onTelegramRequested = { navController.navigate(TelegramRoute) },
                    onBack = { navController.navigateUp() },
                )
            }
            composable<TelegramRoute> {
                TelegramLoginScreen(
                    // Вход состоялся без единого SMS — дальше обычный путь.
                    // Экран уходит из стека вместе с вводом номера: токен уже
                    // отработан, возвращаться к ожиданию Start некуда.
                    onConfirmed = {
                        navController.navigate(PinRoute) {
                            popUpTo(PhoneRoute) { inclusive = true }
                        }
                    },
                    // Telegram не подошёл (или бэкенд просит подтвердить номер)
                    // — остаётся SMS. Обычно экран телефона в стеке цел, и
                    // достаточно вернуться назад: повторная навигация на него
                    // завела бы второй экземпляр. Но если его там нет,
                    // `popBackStack` молча вернёт `false` — и человек останется
                    // на экране Telegram без единого пути дальше (issue #49).
                    onSmsRequested = {
                        if (!navController.popBackStack(PhoneRoute, inclusive = false)) {
                            navController.navigate(PhoneRoute) {
                                popUpTo(TelegramRoute) { inclusive = true }
                            }
                        }
                    },
                    onBack = { navController.navigateUp() },
                )
            }
            composable<OtpRoute> {
                OtpScreen(
                    // Код принят — возвращаться к его вводу больше некуда,
                    // поэтому экран OTP уходит из стека.
                    onVerified = {
                        navController.navigate(PinRoute) {
                            popUpTo(PhoneRoute) { inclusive = true }
                        }
                    },
                    onBack = { navController.navigateUp() },
                )
            }
            composable<PinRoute> {
                PinScreen(
                    onPinReady = { navController.navigate(BiometricRoute) },
                    // Сессия сброшена (лимит попыток или «забыли PIN») — вход
                    // начинается с номера телефона. Стек чистится целиком:
                    // возвращаться «назад» на экран PIN, которого больше нет,
                    // некуда — тем более когда граф стартовал с него.
                    onAuthRestartRequired = {
                        navController.navigate(WelcomeRoute) {
                            popUpTo(OnboardingGraph) { inclusive = true }
                        }
                    },
                )
            }
            composable<BiometricRoute> {
                BiometricScreen(onFinished = { navController.navigate(GeoRoute) })
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
                DiscoveryHomeScreen(
                    onPlaceClick = { placeId -> navController.navigate(PlaceRoute(placeId)) },
                    onSearchClick = { category ->
                        navController.navigate(SearchRoute(categoryId = category?.apiValue))
                    },
                    onMapClick = { navController.navigate(MapRoute) },
                )
            }
            composable<OrdersRoute> { OrdersScreen() }
            composable<WalletRoute> { WalletScreen() }
            composable<ProfileRoute> {
                ProfileScreen(
                    // Сменить сервер после входа (issue #26): онбординг уже
                    // пройден, и welcome, где стояла та же кнопка, недостижим.
                    onChangeServer = if (backendUrlOverrideEnabled) {
                        { navController.navigate(BackendUrlRoute) }
                    } else {
                        null
                    },
                )
            }
        }

        // Поиск и карта — вне графа табов: нижняя навигация на них не нужна,
        // а возврат ведёт обратно на главную.
        composable<SearchRoute> {
            SearchScreen(
                onPlaceClick = { placeId -> navController.navigate(PlaceRoute(placeId)) },
                onBack = { navController.navigateUp() },
            )
        }

        composable<MapRoute> {
            MapScreen(
                onPlaceClick = { placeId -> navController.navigate(PlaceRoute(placeId)) },
                onBack = { navController.navigateUp() },
            )
        }

        composable<PlaceRoute>(
            deepLinks = listOf(navDeepLink { uriPattern = DeepLinks.PLACE_PATTERN }),
        ) {
            // placeId читает сама ViewModel из SavedStateHandle — экран о
            // маршруте ничего не знает и открывается одинаково из списка и из
            // deep link'а.
            PlaceDetailsScreen(onBack = { navController.navigateUp() })
        }
    }
}
