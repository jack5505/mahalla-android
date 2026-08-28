package uz.mahalla.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.ui.graphics.vector.ImageVector
import uz.mahalla.R

/** Разделы нижней навигации (эпик 1.2). Порядок = порядок в макете. */
enum class BottomNavItem(
    val route: Any,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    Discovery(DiscoveryRoute, R.string.nav_discovery, Icons.Outlined.Search),
    Orders(OrdersRoute, R.string.nav_orders, Icons.AutoMirrored.Outlined.ReceiptLong),
    Wallet(WalletRoute, R.string.nav_wallet, Icons.Outlined.AccountBalanceWallet),
    Profile(ProfileRoute, R.string.nav_profile, Icons.Outlined.Person),
}
