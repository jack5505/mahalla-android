package uz.mahalla.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import uz.mahalla.feature.booking.domain.AppointmentVertical
import uz.mahalla.feature.booking.ui.BookingScreen
import uz.mahalla.feature.booking.ui.appointments.MyAppointmentsScreen
import uz.mahalla.feature.cinema.ui.movie.MovieScreen
import uz.mahalla.feature.cinema.ui.poster.CinemaScreen
import uz.mahalla.feature.cinema.ui.tickets.MyTicketsScreen
import uz.mahalla.feature.discovery.ui.home.DiscoveryHomeScreen
import uz.mahalla.feature.fashion.ui.cart.FashionCartScreen
import uz.mahalla.feature.fashion.ui.catalog.FashionCatalogScreen
import uz.mahalla.feature.fashion.ui.checkout.FashionCheckoutScreen
import uz.mahalla.feature.fashion.ui.orders.FashionOrdersScreen
import uz.mahalla.feature.fashion.ui.product.FashionProductScreen
import uz.mahalla.feature.discovery.ui.search.SearchScreen
import uz.mahalla.feature.food.ui.cart.CartScreen
import uz.mahalla.feature.food.ui.checkout.CheckoutScreen
import uz.mahalla.feature.food.ui.menu.MenuScreen
import uz.mahalla.feature.food.ui.order.OrderStatusScreen
import uz.mahalla.feature.freelancer.ui.catalog.FreelancersScreen
import uz.mahalla.feature.freelancer.ui.orders.MyFreelancerOrdersScreen
import uz.mahalla.feature.freelancer.ui.profile.FreelancerProfileScreen
import uz.mahalla.feature.hospital.ui.DoctorBookingScreen
import uz.mahalla.feature.map.domain.MapPoint
import uz.mahalla.feature.map.ui.MapScreen
import uz.mahalla.feature.map.ui.picker.MapPickerScreen
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
import uz.mahalla.feature.pharmacy.ui.PharmacyScreen
import uz.mahalla.feature.place.ui.PlaceDetailsScreen
import uz.mahalla.feature.profile.ui.ProfileScreen
import uz.mahalla.feature.queue.ui.QueueScreen
import uz.mahalla.feature.role.ui.CustomerFormScreen
import uz.mahalla.feature.role.ui.ProviderFormScreen
import uz.mahalla.feature.role.ui.RoleScreen
import uz.mahalla.feature.role.ui.places.MyPlacesScreen
import uz.mahalla.feature.subscription.ui.SubscriptionScreen
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
                    // Каталог мастеров (issue #107): отдельная ветка, мастер
                    // не заведение.
                    onFreelancersClick = { navController.navigate(FreelancersRoute) },
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
                    // «Мои заведения» (issue #94): судьба заявки продавца.
                    onOpenMyPlaces = { navController.navigate(MyPlacesRoute) },
                    // «Мои записи» (issue #97): своего таба у брони нет.
                    onOpenMyAppointments = { navController.navigate(MyAppointmentsRoute()) },
                    // «Мои билеты» (issue #106): своего таба у кино нет.
                    onOpenMyTickets = { navController.navigate(MyTicketsRoute) },
                    // «Мои записи к врачу» (issue #99): тот же экран, другой
                    // список — у больниц своя ручка `hospitals/appointments/my`.
                    // «Мои заказы одежды» (issue #108): своего таба у
                    // вертикали нет, а следить за заказом надо.
                    onOpenMyFashionOrders = { navController.navigate(FashionOrdersRoute) },
                    onOpenMyDoctorAppointments = {
                        navController.navigate(
                            MyAppointmentsRoute(AppointmentVertical.Doctor.name),
                        )
                    },
                    // «Мои заказы у мастеров» (issue #107): заказать услугу у
                    // фрилансера может любой, своего таба у этого нет.
                    onOpenMyFreelancerOrders = {
                        navController.navigate(MyFreelancerOrdersRoute)
                    },
                    // Подписка (issue #103): тарифы, пробный период и отмена.
                    onOpenSubscription = { navController.navigate(SubscriptionRoute) },
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
            // Точка с карты (issue #90) приезжает в `SavedStateHandle` этой же
            // записи стека: экран выбора не знает, кто его позвал, и класть
            // результат ему некуда, кроме предыдущей записи.
            val picked by entry.savedStateHandle
                .getStateFlow<String?>(MapPickerArgs.RESULT_POINT, null)
                .collectAsStateWithLifecycle()
            ProviderFormScreen(
                onPickLocation = { point ->
                    navController.navigate(MapPickerRoute(point = point?.encode()))
                },
                pickedLocation = MapPoint.decode(picked),
                // Ключ гасится после применения: иначе точка возвращалась бы в
                // форму каждый раз, когда экран снова оказывается наверху.
                onPickedLocationHandled = {
                    entry.savedStateHandle[MapPickerArgs.RESULT_POINT] = null
                },
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

        // «Мои заведения» (issue #94) — вне обоих графов, как центр
        // уведомлений: открывается строкой из профиля, возврат ведёт туда же.
        composable<MyPlacesRoute> {
            MyPlacesScreen(
                // Карточка в каталоге есть только у того, что модерация
                // пропустила: экран сам не даст нажать на заявку `PENDING`.
                onPlaceClick = { placeId -> navController.navigate(PlaceRoute(placeId)) },
                // Зарегистрировать ещё одно (или первое) — та же анкета
                // продавца, что и из «Моей анкеты». Возврат из неё приведёт
                // назад в список, где заявка уже будет видна.
                onRegisterPlace = { navController.navigate(ProviderFormRoute()) },
                onBack = { navController.navigateUp() },
            )
        }

        // Подписка (issue #103) — вне обоих графов, как «мои заведения»:
        // открывается строкой из профиля, возврат ведёт туда же.
        composable<SubscriptionRoute> {
            SubscriptionScreen(onBack = { navController.navigateUp() })
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

        // Выбор точки на карте (issue #90). Результат кладётся в предыдущую
        // запись стека, а не отдаётся коллбэком: экран, открывший карту, к
        // этому моменту может пережить смерть процесса, и живой ссылки на него
        // здесь нет.
        composable<MapPickerRoute> {
            MapPickerScreen(
                onPicked = { point ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(MapPickerArgs.RESULT_POINT, point.encode())
                    navController.navigateUp()
                },
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
                onOrderClick = { placeId, placeName ->
                    navController.navigate(MenuRoute(placeId, placeName))
                },
                // Очередь (issue #96): у мастеров это главное действие
                // карточки.
                onQueueClick = { placeId, placeName ->
                    navController.navigate(QueueRoute(placeId, placeName))
                },
                // Бронь (issue #97): запись на время — второе действие тех же
                // мастеров, для тех, кому очередь «прямо сейчас» не подходит.
                onBookingClick = { placeId, placeName ->
                    navController.navigate(BookingRoute(placeId, placeName))
                },
                // Больницы (issue #99): у них своя запись — к врачу, а не на
                // услугу заведения.
                onDoctorClick = { placeId, placeName ->
                    navController.navigate(DoctorBookingRoute(placeId, placeName))
                },
                // Кино (issue #106): с карточки кинотеатра — в его афишу,
                // оттуда в фильм, сеансы и покупку.
                onCinemaClick = { placeId, placeName ->
                    navController.navigate(CinemaRoute(placeId, placeName))
                },
                // Одежда (issue #108): витрина магазина с корзиной на сервере.
                onShopClick = { placeId, placeName ->
                    navController.navigate(FashionCatalogRoute(placeId, placeName))
                },
                // Витрина аптеки (issue #100): единственное действие, которое
                // ничего не начинает — заказать товар бэкенду нечем.
                onProductsClick = { placeId, placeName ->
                    navController.navigate(PharmacyRoute(placeId, placeName))
                },
                onBack = { navController.navigateUp() },
            )
        }

        // Вертикаль «Кино» (эпик #13, issue #106): афиша кинотеатра → фильм →
        // сеансы → покупка; за билетами следят в «моих билетах».
        composable<CinemaRoute> { entry ->
            val route = entry.toRoute<CinemaRoute>()
            CinemaScreen(
                onOpenMovie = { movieId ->
                    navController.navigate(
                        MovieRoute(
                            placeId = route.placeId,
                            movieId = movieId,
                            placeName = route.placeName,
                        ),
                    )
                },
                onBack = { navController.navigateUp() },
            )
        }

        composable<MovieRoute> {
            MovieScreen(
                // Экран покупки из стека уходит: возвращаться к сеансу, билет
                // на который уже куплен, некуда.
                onOpenMyTickets = {
                    navController.navigate(MyTicketsRoute) {
                        popUpTo<MovieRoute> { inclusive = true }
                    }
                },
                onBack = { navController.navigateUp() },
            )
        }

        composable<MyTicketsRoute> {
            MyTicketsScreen(onBack = { navController.navigateUp() })
        }

        // Вертикаль «Больницы» (эпик #11, issue #99): к врачу записываются с
        // карточки места, а следят за записью в «моих записях к врачу».
        composable<DoctorBookingRoute> {
            DoctorBookingScreen(
                // Экран записи из стека уходит: возвращаться в собранную форму
                // после того, как запись создана, некуда.
                onOpenMyAppointments = {
                    navController.navigate(
                        MyAppointmentsRoute(AppointmentVertical.Doctor.name),
                    ) {
                        popUpTo<DoctorBookingRoute> { inclusive = true }
                    }
                },
                onBack = { navController.navigateUp() },
            )
        }

        // Вертикаль «Аптека» (issue #100): витрина товаров с наличием. Своей
        // корзины у неё нет и не будет, пока `pharmacy-controller` не отдаст
        // ручку заказа, — поэтому маршрут здесь один.
        composable<PharmacyRoute> {
            PharmacyScreen(onBack = { navController.navigateUp() })
        }

        // Вертикаль «Бронь» (эпик #11, issue #97): записываются с карточки
        // места, а следят за записью в «моих записях» — туда же ведёт и
        // подтверждение.
        composable<BookingRoute> {
            BookingScreen(
                // Экран записи из стека уходит: возвращаться в собранную форму
                // после того, как запись создана, некуда.
                onOpenMyAppointments = {
                    navController.navigate(MyAppointmentsRoute()) {
                        popUpTo<BookingRoute> { inclusive = true }
                    }
                },
                onBack = { navController.navigateUp() },
            )
        }

        composable<MyAppointmentsRoute> {
            MyAppointmentsScreen(onBack = { navController.navigateUp() })
        }

        // Вертикаль «Мастера» (issue #107): каталог фрилансеров → профиль с
        // услугами → заказ. Мастер не заведение, поэтому это отдельная ветка,
        // а не карточка места.
        composable<FreelancersRoute> {
            FreelancersScreen(
                onFreelancerClick = { freelancerId, name ->
                    navController.navigate(FreelancerRoute(freelancerId, name))
                },
                onBack = { navController.navigateUp() },
            )
        }

        composable<FreelancerRoute> {
            FreelancerProfileScreen(
                // Профиль из стека уходит: возвращаться в собранную форму
                // после того, как заказ создан, некуда. Каталог при этом
                // остаётся — «назад» из заказов приведёт к списку мастеров.
                onOpenMyOrders = {
                    navController.navigate(MyFreelancerOrdersRoute) {
                        popUpTo<FreelancerRoute> { inclusive = true }
                    }
                },
                onBack = { navController.navigateUp() },
            )
        }

        composable<MyFreelancerOrdersRoute> {
            MyFreelancerOrdersScreen(onBack = { navController.navigateUp() })
        }

        // Вертикаль «Очередь» (эпик #10, issue #96): талон берут с карточки
        // места, а о решении мастера сообщают уведомления — поэтому с экрана
        // талона есть путь в их центр.
        composable<QueueRoute> {
            QueueScreen(
                onOpenNotifications = { navController.navigate(NotificationsRoute) },
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
                // popUpTo по типу, а не по значению маршрута: имя заведения в
                // аргументах может отличаться, а вернуться нужно в то меню,
                // которое уже лежит в стеке.
                onAddMore = { placeId, placeName ->
                    navController.navigate(MenuRoute(placeId, placeName)) {
                        popUpTo<MenuRoute> { inclusive = true }
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

        // Вертикаль «Одежда» (issue #108): витрина магазина → товар →
        // корзина (она на сервере и общая) → оформление по одному магазину →
        // «мои заказы».
        composable<FashionCatalogRoute> {
            FashionCatalogScreen(
                onProductClick = { productId ->
                    navController.navigate(FashionProductRoute(productId))
                },
                onCartClick = { navController.navigate(FashionCartRoute) },
                onBack = { navController.navigateUp() },
            )
        }

        composable<FashionProductRoute> {
            FashionProductScreen(
                onCartClick = { navController.navigate(FashionCartRoute) },
                onBack = { navController.navigateUp() },
            )
        }

        composable<FashionCartRoute> {
            FashionCartScreen(
                onCheckout = { storeId -> navController.navigate(FashionCheckoutRoute(storeId)) },
                onBack = { navController.navigateUp() },
            )
        }

        composable<FashionCheckoutRoute> {
            FashionCheckoutScreen(
                // Корзина и оформление из стека уходят: возвращаться к
                // заказу, который уже создан, некуда, а корзина по этому
                // магазину пуста.
                onOpenOrders = {
                    navController.navigate(FashionOrdersRoute) {
                        popUpTo<FashionCartRoute> { inclusive = true }
                    }
                },
                onOpenWallet = { navController.navigate(WalletRoute) },
                onBack = { navController.navigateUp() },
            )
        }

        composable<FashionOrdersRoute> {
            FashionOrdersScreen(onBack = { navController.navigateUp() })
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
