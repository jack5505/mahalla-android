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
         * `BIOMETRIC_STRONG` (issue #318): промпт всегда показывается с
         * `CryptoObject` поверх ключа Keystore ([BiometricCipher]), а такой
         * ключ **физически недоступен** без датчика, признанного Android
         * class 3 — weak-класс (2D-лицо, которое на части устройств
         * открывается фотографией) для него не годится в принципе, слабее
         * ставить нечего.
         *
         * Фолбэк не отдельная ветка кода: `canAuthenticate(STRONG)` для
         * устройства с одним лишь weak-датчиком возвращает `NotEnrolled`/
         * `Unavailable`, [BiometricStatus.canEnable] — `false`, и все экраны
         * (онбординг 3.5, настройки, профиль, app-lock) уже умеют это как
         * «биометрия недоступна» — держат тумблер выключенным и остаются на
         * PIN, ничего сверх этого писать не пришлось.
         */
        const val AUTHENTICATORS = Authenticators.BIOMETRIC_STRONG
    }
}
