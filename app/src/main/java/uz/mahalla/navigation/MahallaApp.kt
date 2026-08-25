package uz.mahalla.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

/**
 * Корневой каркас приложения: нижняя навигация показывается только внутри
 * основного графа — в онбординге и на экранах-деталях её нет.
 */
@Composable
fun MahallaApp(
    startDestination: Any,
    onOnboardingFinished: () -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentDestination = currentEntry?.destination
    val selectedItem = BottomNavItem.entries.firstOrNull { it.matches(currentDestination) }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (selectedItem != null) {
                NavigationBar {
                    BottomNavItem.entries.forEach { item ->
                        NavigationBarItem(
                            selected = item == selectedItem,
                            onClick = { navController.navigateToTab(item) },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(stringResource(item.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        MahallaNavHost(
            navController = navController,
            startDestination = startDestination,
            onOnboardingFinished = onOnboardingFinished,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

private fun BottomNavItem.matches(destination: NavDestination?): Boolean =
    destination?.hasRoute(route::class) == true

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
