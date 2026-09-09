package uz.mahalla.feature.notifications.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import uz.mahalla.core.result.runCatchingCancellable
import uz.mahalla.feature.notifications.push.needsPostNotificationsPermission

/**
 * Состояние разрешения на уведомления (эпик 11).
 *
 * @param enabled уведомления реально дойдут: разрешение выдано **и** сам
 * пользователь не выключил уведомления приложения в системных настройках.
 * Второе бывает чаще, чем кажется, и снаружи выглядит одинаково — «пуши не
 * приходят», — поэтому одно состояние на оба случая.
 * @param canRequest спросить можно системным диалогом. `false` — либо уже всё
 * хорошо, либо диалога больше не будет (отказали дважды, выключили руками), и
 * остаётся вести в системные настройки.
 */
@Immutable
data class NotificationPermissionState(
    val enabled: Boolean,
    val canRequest: Boolean,
    val request: () -> Unit,
    val openSystemSettings: () -> Unit,
)

/**
 * Разрешение `POST_NOTIFICATIONS` (эпик 11) — спрашивается **не на старте**.
 *
 * Диалог на первом экране люди закрывают не глядя, а второй раз система его не
 * покажет: право спросить тратится один раз. Поэтому запрос живёт там, где
 * человек сам пришёл за уведомлениями, — в центре уведомлений и в их
 * настройках, рядом с объяснением, что именно перестанет приходить.
 *
 * Состояние перечитывается на каждом возврате на экран: разрешение выдают и
 * отзывают в системных настройках, то есть за пределами приложения.
 */
// InlinedApi: `POST_NOTIFICATIONS` — строковая константа, компилятор
// подставляет её значение, класса из API 33 в рантайме не требуется. Запросить
// разрешение ниже API 33 всё равно невозможно: `canRequest` там всегда `false`
// (`needsPostNotificationsPermission`), а системе такое имя просто неизвестно.
@SuppressLint("InlinedApi")
@Composable
fun rememberNotificationPermissionState(): NotificationPermissionState {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(context.areNotificationsAllowed()) }
    // Право показать системный диалог тратится один раз за установку. Второй
    // отказ приложение не увидит вовсе — коллбэк придёт мгновенно с `false`.
    //
    // `rememberSaveable`, а не `remember`: поворот экрана и уход в фон не
    // должны возвращать кнопку, которая уже ничего не откроет. Пережить смерть
    // процесса это всё равно не может — спросить систему «спрашивал ли я
    // раньше» нельзя, — поэтому путь в системные настройки на карточке есть
    // всегда (см. `NotificationPermissionCard`).
    var requestSpent by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        requestSpent = true
        // Не `granted`, а полная проверка: разрешение могли выдать, а
        // уведомления приложения при этом остаться выключенными.
        enabled = granted && context.areNotificationsAllowed()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        enabled = context.areNotificationsAllowed()
    }

    return NotificationPermissionState(
        enabled = enabled,
        canRequest = !enabled && needsPostNotificationsPermission() && !requestSpent,
        request = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
        openSystemSettings = { context.openNotificationSettings() },
    )
}

/**
 * Дойдёт ли уведомление до человека.
 *
 * `areNotificationsEnabled` на API 33+ уже учитывает `POST_NOTIFICATIONS`, но
 * на 26–32 разрешения нет, а выключить уведомления можно, — поэтому проверяются
 * обе вещи, и на всех версиях.
 */
private fun Context.areNotificationsAllowed(): Boolean {
    val permissionGranted = !needsPostNotificationsPermission() ||
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    return permissionGranted && NotificationManagerCompat.from(this).areNotificationsEnabled()
}

/**
 * Системные настройки уведомлений приложения — единственный путь, когда право
 * на диалог уже потрачено.
 *
 * Падение здесь штатно: экрана настроек уведомлений нет на части прошивок, и
 * ронять приложение из-за кнопки «настроить» нельзя.
 */
private fun Context.openNotificationSettings() {
    runCatchingCancellable {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                // Контекст здесь — Activity, но у обёрток темы им может быть и
                // ContextWrapper без своей задачи; флаг делает вызов безопасным
                // в обоих случаях.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
