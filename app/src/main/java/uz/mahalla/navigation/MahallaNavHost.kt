package uz.mahalla.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import uz.mahalla.feature.discovery.ui.home.DiscoveryHomeScreen
import uz.mahalla.feature.discovery.ui.search.SearchScreen
import uz.mahalla.feature.food.ui.cart.CartScreen
import uz.mahalla.feature.food.ui.checkout.CheckoutScreen
import uz.mahalla.feature.food.ui.menu.MenuScreen
import uz.mahalla.feature.food.ui.order.OrderStatusScreen
import uz.mahalla.feature.map.ui.MapScreen
import uz.mahalla.feature.notifications.ui.NotificationsScreen
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
import uz.mahalla.feature.role.ui.CustomerFormScreen
import uz.mahalla.feature.role.ui.ProviderFormScreen
import uz.mahalla.feature.role.ui.RoleScreen
import uz.mahalla.feature.update.ui.AppUpdateScreen
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
 * @param afterUpdate куда уходить с экрана обновления (issue #80). Вызывается
 * только на мягком предложении: обязательное обновление отсюда не выпускает.
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
    afterUpdate: Any = OnboardingGraph,
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

        // Обновление приложения (issue #80) — тоже вне графов и всегда в
        // графе, а не под флагом: экран показывается только когда бэкенд
        // сказал обновиться, и стартовым его назначает `MainActivity`.
        composable<UpdateRoute> {
            AppUpdateScreen(
                onContinue = {
                    navController.navigate(afterUpdate) {
                        // Возвращаться к предложению обновиться некуда: оно
                        // уже отработано и на этот запуск отложено.
                        popUpTo(UpdateRoute) { inclusive = true }
                    }
                },
            )
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
                                channel = challenge.channel.name,
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
                // Последний шаг регистрации — «кто вы» (issue #84): анкета
                // покупателя или заявка продавца. Сам экран роли лежит вне
                // графа: он же открывается из профиля.
                GeoScreen(onFinished = { navController.navigate(RoleRoute(onboarding = true)) })
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
                    onNotificationsClick = { navController.navigate(NotificationsRoute) },
                )
            }
            composable<OrdersRoute> { OrdersScreen() }
            composable<WalletRoute> { WalletScreen() }
            composable<ProfileRoute> {
                ProfileScreen(
                    // Вышли (issue #61): сессии и PIN больше нет, поэтому весь
                    // основной граф уходит из стека — «назад» в приложение,
                    // где каждый запрос ответит 401, вести некуда. Идём на
                    // welcome, а не в `OnboardingGraph`: граф онбординга мог
                    // стартовать с PIN (прерванный вход), а после выхода
                    // проверять нечего.
                    onLoggedOut = {
                        navController.navigate(WelcomeRoute) {
                            popUpTo(MainGraph) { inclusive = true }
                        }
                    },
                    // «Кто вы» и анкеты (issue #84): в онбординге шаг можно
                    // было пропустить, а роль потом меняется.
                    onOpenRole = { navController.navigate(RoleRoute()) },
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

        // Анкеты покупателя и продавца (issue #84) — вне обоих графов: экраны
        // нужны и последним шагом регистрации, и потом из профиля (роль
        // меняется). Чем кончается заполнение, решает аргумент `onboarding`:
        // в регистрации — переходом в приложение, из профиля — возвратом.
        composable<RoleRoute> { entry ->
            val onboarding = entry.toRoute<RoleRoute>().onboarding
            RoleScreen(
                onCustomerForm = {
                    navController.navigate(CustomerFormRoute(onboarding = onboarding))
                },
                onProviderForm = {
                    navController.navigate(ProviderFormRoute(onboarding = onboarding))
                },
                // Анкету можно отложить: упереться на последнем шаге
                // регистрации в форму, которую человек пока не хочет
                // заполнять, — это потерять его у самого входа.
                onSkip = if (onboarding) {
                    { finishOnboarding(navController, onOnboardingFinished) }
                } else {
                    null
                },
                onBack = if (onboarding) null else ({ navController.navigateUp() }),
            )
        }

        composable<CustomerFormRoute> { entry ->
            val onboarding = entry.toRoute<CustomerFormRoute>().onboarding
            CustomerFormScreen(
                onSaved = {
                    if (onboarding) {
                        finishOnboarding(navController, onOnboardingFinished)
                    } else {
                        navController.navigateUp()
                    }
                },
                onBack = { navController.navigateUp() },
            )
        }

        composable<ProviderFormRoute> { entry ->
            val onboarding = entry.toRoute<ProviderFormRoute>().onboarding
            ProviderFormScreen(
                onFinished = {
                    if (onboarding) {
                        finishOnboarding(navController, onOnboardingFinished)
                    } else {
                        // Заявка отправлена — возвращаться в заполненную форму
                        // некуда, поэтому уходит и она, и экран выбора роли.
                        // Экрана роли в стеке может и не быть (пришли по
                        // другому пути) — тогда обычный возврат назад.
                        if (!navController.popBackStack<RoleRoute>(inclusive = true)) {
                            navController.navigateUp()
                        }
                    }
                },
                onBack = { navController.navigateUp() },
            )
        }

        // Поиск и карта — вне графа табов: нижняя навигация на них не нужна,
        // а возврат ведёт обратно на главную.
        composable<SearchRoute> {
            SearchScreen(
                onPlaceClick = { placeId -> navController.navigate(PlaceRoute(placeId)) },
                onBack = { navController.navigateUp() },
            )
        }

        // Центр уведомлений (issue #81) — тоже вне графа табов: открывается
        // иконкой из топбара главной, а возврат ведёт обратно туда же.
        composable<NotificationsRoute> {
            NotificationsScreen(
                // Уведомление о заказе ведёт на его статус. Экран уведомлений
                // при этом остаётся в стеке: «назад» возвращает к списку, а не
                // выбрасывает на главную посреди чтения.
                onOrderClick = { orderId -> navController.navigate(OrderStatusRoute(orderId)) },
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
            PlaceDetailsScreen(
                onOrderClick = { placeId -> navController.navigate(MenuRoute(placeId)) },
                onBack = { navController.navigateUp() },
            )
        }

        // Вертикаль «Еда» (эпик 5): меню → корзина → checkout → статус заказа.
        composable<MenuRoute> {
            MenuScreen(
                onCartClick = { placeId -> navController.navigate(CartRoute(placeId)) },
                onBack = { navController.navigateUp() },
            )
        }

        composable<CartRoute> {
            CartScreen(
                onCheckout = { placeId -> navController.navigate(CheckoutRoute(placeId)) },
                // «Добавить ещё» — это возврат в меню, а не второй его
                // экземпляр поверх первого.
                onAddMore = { placeId ->
                    navController.navigate(MenuRoute(placeId)) {
                        popUpTo(MenuRoute(placeId)) { inclusive = true }
                    }
                },
                onBack = { navController.navigateUp() },
            )
        }

        composable<CheckoutRoute> {
            CheckoutScreen(
                // Заказ создан — возвращаться в корзину, которой больше нет,
                // некуда: весь путь оформления уходит из стека, и «назад» с
                // экрана статуса ведёт на карточку заведения.
                onOrderCreated = { orderId ->
                    navController.navigate(OrderStatusRoute(orderId)) {
                        popUpTo<MenuRoute> { inclusive = true }
                    }
                },
                onOpenWallet = { navController.navigate(WalletRoute) },
                onBack = { navController.navigateUp() },
            )
        }

        composable<OrderStatusRoute> {
            OrderStatusScreen(
                onOpenCart = { placeId ->
                    navController.navigate(CartRoute(placeId)) {
                        popUpTo<OrderStatusRoute> { inclusive = true }
                    }
                },
                onBack = { navController.navigateUp() },
            )
        }
    }
}

/**
 * Онбординг пройден: флаг в настройки, дальше основной граф.
 *
 * Вынесено функцией, потому что точек выхода стало три (issue #84): «пропустить
 * анкету», анкета покупателя и заявка продавца. Стек чистится целиком —
 * `popUpTo(OnboardingGraph)` снимает и экраны анкет, которые лежат выше графа,
 * а возврата в регистрацию нет.
 */
private fun finishOnboarding(navController: NavHostController, onOnboardingFinished: () -> Unit) {
    onOnboardingFinished()
    navController.navigate(MainGraph) {
        popUpTo(OnboardingGraph) { inclusive = true }
    }
}
