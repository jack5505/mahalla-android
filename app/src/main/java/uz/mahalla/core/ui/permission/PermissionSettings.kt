package uz.mahalla.core.ui.permission

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import uz.mahalla.core.result.runCatchingCancellable

/**
 * Путь в системные настройки после «Больше не спрашивать» (issue #348).
 *
 * Общее для геоэкранов онбординга (3.6) и карты (issue #65): оба запрашивают
 * `ACCESS_COARSE_LOCATION`/`ACCESS_FINE_LOCATION` и должны одинаково понимать,
 * доступен ли ещё системный диалог.
 */

/**
 * Системный диалог разрешения показывает `Activity`, а Compose-контекст может
 * быть обёрнут (тема, локаль) — до неё нужно развернуться, чтобы спросить
 * `shouldShowRequestPermissionRationale`.
 */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * `false` — диалог больше не покажется: человек выбрал «Больше не спрашивать»
 * либо разрешение запрещено политикой устройства. Единственный путь тогда —
 * системные настройки приложения ([openAppSettings]).
 *
 * Имеет смысл звать только в колбэке результата запроса, после того как диалог
 * уже был показан хотя бы раз: до первого отказа система тоже возвращает
 * `false` (rationale ещё нечего показывать), и это неотличимо от «Больше не
 * спрашивать».
 */
fun Activity.canRequestPermissionAgain(permissions: Array<String>): Boolean =
    permissions.any { ActivityCompat.shouldShowRequestPermissionRationale(this, it) }

/** Экран настроек приложения — путь, когда системный диалог больше не доступен. */
fun Context.openAppSettings() {
    runCatchingCancellable {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
