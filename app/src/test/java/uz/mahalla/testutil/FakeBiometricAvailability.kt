package uz.mahalla.testutil

import uz.mahalla.data.security.BiometricAvailability
import uz.mahalla.data.security.BiometricStatus

/** Датчик биометрии в памяти: статус меняется по ходу теста, как в настройках устройства. */
class FakeBiometricAvailability(
    var status: BiometricStatus = BiometricStatus.Available,
) : BiometricAvailability {
    override fun status(): BiometricStatus = status
}
