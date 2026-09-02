package uz.mahalla.feature.role.ui

import androidx.annotation.StringRes
import uz.mahalla.R
import uz.mahalla.feature.role.domain.UserRole

/**
 * Подпись роли (issue #84). Домен `UserRole` про Android не знает, поэтому
 * названия живут в ресурсах, а сопоставление — здесь.
 *
 * `null` — роль не выбрана: так и пишем. «Покупатель» по умолчанию был бы
 * враньём про выбор, которого человек не делал.
 */
@StringRes
fun UserRole?.labelRes(): Int = when (this) {
    UserRole.Customer -> R.string.role_customer_title
    UserRole.Provider -> R.string.role_provider_title
    null -> R.string.role_not_selected
}
