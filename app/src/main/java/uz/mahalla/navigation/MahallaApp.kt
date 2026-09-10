package uz.mahalla.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import uz.mahalla.R
import uz.mahalla.core.ui.components.MahallaBottomNav
import uz.mahalla.core.ui.components.MahallaSnackbarHost
import uz.mahalla.core.ui.components.MahallaTone
import uz.mahalla.core.ui.components.NavItemUi
import uz.mahalla.core.ui.components.rememberSnackbarController
import uz.mahalla.core.ui.snackbar.SnackbarLength
import uz.mahalla.core.ui.snackbar.SnackbarMessage

/**
 * Корневой каркас приложения: нижняя навигация показывается только внутри
 * основного графа — в онбординге и на экранах-деталях её нет.
 *
 * @param sessionExpired сессия умерла, пока приложение работало (issue #138):
 * повод увести человека на вход с любого экрана.
 */
@Composable
fun MahallaApp(
    startDestination: Any,
    onOnboardingFinished: () -> Unit,
    modifier: Modifier = Modifier,
    onboardingStartDestination: Any = WelcomeRoute,
    afterBackendUrl: Any = OnboardingGraph,
    afterUpdate: Any = OnboardingGraph,
    backendUrlOverrideEnabled: Boolean = false,
    sessionExpired: Flow<Unit> = emptyFlow(),
    navController: NavHostController = rememberNavController(),
) {
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentDestination = currentEntry?.destination
    val selectedItem = BottomNavItem.entries.firstOrNull { it.matches(currentDestination) }
    val snackbarController = rememberSnackbarController()
    val expiredMessage = stringResource(R.string.error_unauthorized)

    SessionExpiryEffect(navController = navController, sessionExpired = sessionExpired) {
        // Экран входа, возникший сам собой, читается как сброс приложения,
        // поэтому причина говорится словами. `Long`, а не `Short`: человек
        // мог отвернуться от телефона именно в этот момент.
        snackbarController.show(
            SnackbarMessage(
                text = expiredMessage,
                tone = MahallaTone.Error,
                length = SnackbarLength.Long,
            ),
        )
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { MahallaSnackbarHost(controller = snackbarController) },
        bottomBar = {
            if (selectedItem != null) {
                // Нижняя навигация — компонент UI-кита (эпик 2.2): цвета,
                // подписи и цель нажатия 48dp заданы там, а не на каждом экране.
                MahallaBottomNav(
                    items = BottomNavItem.entries.map { item ->
                        NavItemUi(
                            id = item.name,
                            label = stringResource(item.labelRes),
                            icon = item.icon,
                        )
                    },
                    selectedId = selectedItem.name,
                    onSelect = { selected ->
                        // Ищем по name, а не valueOf: неизвестный id — это баг
                        // сборки списка, а не повод уронить приложение.
                        BottomNavItem.entries
                            .firstOrNull { it.name == selected.id }
                            ?.let(navController::navigateToTab)
                    },
                )
            }
        },
    ) { innerPadding ->
        MahallaNavHost(
            navController = navController,
            startDestination = startDestination,
            onOnboardingFinished = onOnboardingFinished,
            modifier = Modifier.padding(innerPadding),
            onboardingStartDestination = onboardingStartDestination,
            afterBackendUrl = afterBackendUrl,
            afterUpdate = afterUpdate,
            backendUrlOverrideEnabled = backendUrlOverrideEnabled,
        )
    }
}

/**
 * Уход на экран входа, когда сессия умерла на ходу (issue #138): токенов
 * больше нет и вернуть их нечем, а значит каждый следующий экран отвечал бы
 * ошибкой сервера и кнопкой «повторить», которая не может помочь. Идём туда
 * же, куда ведёт явный выход из профиля.
 *
 * Отдельной функцией, а не блоком внутри [MahallaApp], чтобы это можно было
 * проверить тестом: композиция [MahallaNavHost] поднимает настоящие экраны с
 * `hiltViewModel()`, а здесь нужен только контроллер навигации.
 *
 * @param onExpired объяснение для человека; вызывается только когда уход
 * действительно случился.
 */
@Composable
internal fun SessionExpiryEffect(
    navController: NavHostController,
    sessionExpired: Flow<Unit>,
    onExpired: suspend () -> Unit,
) {
    LaunchedEffect(sessionExpired, navController) {
        sessionExpired.collect {
            if (!navController.needsLogin()) return@collect
            navController.navigate(WelcomeRoute) {
                // Стек чистится целиком, а не до `MainGraph`: без сессии в нём
                // не осталось ни одного работающего экрана, включая детали,
                // открытые из уведомления мимо основного графа.
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
            onExpired()
        }
    }
}

/**
 * Таб считается выбранным, если маршрут есть в иерархии текущего назначения:
 * на вложенном экране таба подсветка не должна пропадать.
 */
private fun BottomNavItem.matches(destination: NavDestination?): Boolean =
    destination?.hierarchy?.any { it.hasRoute(route::class) } == true

/**
 * Кого уводить на вход при смерти сессии.
 *
 * Не уводим только с экранов, стоящих **до** входа, — [preLoginRoutes]:
 * человек как раз входит, и welcome посреди ввода кода стёр бы шаг, а адрес
 * бэкенда (issue #26) и обновление (issue #80) ведут дальше сами. PIN здесь
 * же: и установка, и вход по нему анонимны и сами выдают новую сессию.
 *
 * Шаги онбординга после PIN (биометрия, гео) исключением не считаются: сессия
 * там уже есть, и с мёртвой человек доигрывал бы шаги, после которых каждый
 * запрос ответит 401. Анкеты последнего шага (`RoleRoute` и формы) — тоже:
 * незаполненная анкета потеряется, но отправить её всё равно нечем.
 *
 * Смотрим и на экран под текущим: адрес бэкенда открывается ещё и из профиля.
 * Там событие, отброшенное как «до входа», терялось бы насовсем — следующие
 * 401 приходят уже без сессии и событий не шлют, и человек, вернувшись
 * «назад», застревал бы в приложении без токена. Двух уровней достаточно: с
 * экранов до входа вглубь уходят только на такие же экраны.
 *
 * Граф ещё не построен — навигировать некуда, да и ходить в сеть было некому.
 */
private fun NavHostController.needsLogin(): Boolean {
    val current = currentDestination ?: return false
    val previous = previousBackStackEntry?.destination
    return !current.isPreLogin() || (previous != null && !previous.isPreLogin())
}

private fun NavDestination.isPreLogin(): Boolean = preLoginRoutes.any { hasRoute(it) }

private val preLoginRoutes = listOf(
    BackendUrlRoute::class,
    UpdateRoute::class,
    WelcomeRoute::class,
    PhoneRoute::class,
    TelegramRoute::class,
    OtpRoute::class,
    PinRoute::class,
)

/**
 * Переключение таба: стек не растёт (`launchSingleTop`), состояние таба
 * сохраняется, а «назад» с любого таба ведёт на стартовый раздел.
 *
 * Не `private`: тем же способом уходит на главную кнопка пустого состояния в
 * «моих активностях» (issue #73) — это переключение таба, а не переход вглубь,
 * и `navigate(DiscoveryRoute)` там растил бы стек.
 */
internal fun NavHostController.navigateToTab(item: BottomNavItem) {
    navigate(item.route) {
        popUpTo(MainGraph) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
