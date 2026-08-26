package uz.mahalla.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import uz.mahalla.core.ui.components.MahallaBottomNav
import uz.mahalla.core.ui.components.NavItemUi

/**
 * Корневой каркас приложения: нижняя навигация показывается только внутри
 * основного графа — в онбординге и на экранах-деталях её нет.
 */
@Composable
fun MahallaApp(
    startDestination: Any,
    onOnboardingFinished: () -> Unit,
    modifier: Modifier = Modifier,
    onboardingStartDestination: Any = WelcomeRoute,
    afterBackendUrl: Any = OnboardingGraph,
    backendUrlOverrideEnabled: Boolean = false,
    navController: NavHostController = rememberNavController(),
) {
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentDestination = currentEntry?.destination
    val selectedItem = BottomNavItem.entries.firstOrNull { it.matches(currentDestination) }

    Scaffold(
        modifier = modifier,
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
            backendUrlOverrideEnabled = backendUrlOverrideEnabled,
        )
    }
}

/**
 * Таб считается выбранным, если маршрут есть в иерархии текущего назначения:
 * на вложенном экране таба подсветка не должна пропадать.
 */
private fun BottomNavItem.matches(destination: NavDestination?): Boolean =
    destination?.hierarchy?.any { it.hasRoute(route::class) } == true

/**
 * Переключение таба: стек не растёт (`launchSingleTop`), состояние таба
 * сохраняется, а «назад» с любого таба ведёт на стартовый раздел.
 */
private fun NavHostController.navigateToTab(item: BottomNavItem) {
    navigate(item.route) {
        popUpTo(MainGraph) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
