package uz.mahalla.core.ui.biometric

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import uz.mahalla.data.security.AndroidBiometricAvailability

/**
 * Системный `BiometricPrompt` живёт в Activity, а не в ViewModel, поэтому его
 * показывает экран. Общий код вынесен сюда: промпт нужен и шагу онбординга
 * (3.5), и экрану блокировки приложения (issue #102), и переключателю
 * биометрии в настройках безопасности — три копии разъехались бы при первой
 * же правке текста кнопки.
 *
 * `BiometricPrompt` умеет работать только с [FragmentActivity] — ради этого
 * `MainActivity` от неё и наследуется. Контекст в Compose бывает обёрнут
 * (тема, локаль), поэтому обёртки разворачиваются, а не приводятся кастом.
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
 * @param onCancelled пользователь закрыл диалог. Это не ошибка, и объяснять
 * тут нечего — в отличие от [onFailed], где датчик отказал сам.
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
