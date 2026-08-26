package uz.mahalla.data.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Что устройство может по биометрии (эпик 3.5).
 *
 * Четыре состояния, а не `Boolean`: пользователю нужно разное объяснение —
 * «на устройстве нет сканера» и «сканер есть, но отпечаток не добавлен» ведут
 * к разным действиям.
 */
enum class BiometricStatus {
    /** Можно включать вход по биометрии. */
    Available,

    /** Датчик есть, но ни одного отпечатка/лица не зарегистрировано. */
    NotEnrolled,

    /** Датчика нет вообще. */
    NoHardware,

    /** Временно недоступна (занята обновлением, отключена политикой). */
    Unavailable,
    ;

    val canEnable: Boolean get() = this == Available
}

/**
 * Проверка доступности вынесена за интерфейс: `BiometricManager` — статика
 * Android, а `BiometricViewModel` должен тестироваться на чистом JVM.
 */
interface BiometricAvailability {
    fun status(): BiometricStatus
}

@Singleton
class AndroidBiometricAvailability @Inject constructor(
    @ApplicationContext private val context: Context,
) : BiometricAvailability {

    override fun status(): BiometricStatus =
        when (BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.Available
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricStatus.NotEnrolled
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricStatus.NoHardware
            else -> BiometricStatus.Unavailable
        }

    companion object {
        /**
         * `BIOMETRIC_WEAK`: разблокировка приложения — не платёжная операция,
         * а на части устройств Узбекистана распознавание лица проходит только
         * по weak-классу. Криптоключи этой биометрией не защищаются (PIN
         * лежит под ключом Keystore, см. [PinCipher]).
         */
        const val AUTHENTICATORS = Authenticators.BIOMETRIC_WEAK
    }
}
