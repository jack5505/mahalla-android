package uz.mahalla.core.ui.biometric

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import uz.mahalla.data.security.AndroidBiometricAvailability

/**
 * Системный промпт биометрии — общий для онбординга (3.5) и подтверждения
 * оплаты (8.3).
 *
 * Вынесено из `BiometricScreen`, когда промпт понадобился второму месту:
 * копия этого кода в шторке оплаты означала бы две разные трактовки отмены и
 * два набора допустимых аутентификаторов.
 *
 * `BiometricPrompt` умеет работать только с `FragmentActivity` — ради этого
 * `MainActivity` от неё и наследуется. Контекст в Compose может быть обёрнут
 * (тема, локаль), поэтому обёртки разворачиваются.
 */
fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return null
}

/**
 * Показать промпт.
 *
 * @param onCancelled человек закрыл диалог сам — это не ошибка, и пугать его
 * сообщением о сбое не за что.
 * @param onFailed промпт не сработал: датчик занят, политика запретила,
 * биометрия сброшена. Вызывающий решает, чем подтверждать вместо неё.
 */
fun showBiometricPrompt(
    activity: FragmentActivity,
    title: String,
    subtitle: String,
    negativeLabel: String,
    onSuccess: () -> Unit,
    onCancelled: () -> Unit,
    onFailed: () -> Unit,
) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Отмена — не ошибка: пользователь просто закрыл диалог.
                val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                if (cancelled) onCancelled() else onFailed()
            }
            // onAuthenticationFailed — один неудачный отпечаток; диалог
            // остаётся открытым, и вмешиваться в него не нужно.
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeLabel)
            .setAllowedAuthenticators(AndroidBiometricAvailability.AUTHENTICATORS)
            .build(),
    )
}
