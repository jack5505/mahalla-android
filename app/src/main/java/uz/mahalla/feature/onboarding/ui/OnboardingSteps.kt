package uz.mahalla.feature.onboarding.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import uz.mahalla.R

/**
 * Шаги регистрации и их номера в счётчике «Шаг N из M» (макет 0b–0e).
 *
 * Порядок повторяет `OnboardingGraph` в `MahallaNavHost`: телефон → код → PIN
 * → отпечаток → геопозиция. Вход через Telegram отдельным шагом не считается —
 * он заменяет первый, а не добавляется к нему, поэтому у него тот же номер.
 *
 * Отпечаток входит в счёт, хотя на устройстве без датчика делать там нечего:
 * экран всё равно показывается (с объяснением и кнопкой «пропустить»), а
 * значит человек его видит и считает шагом. Пропадёт экран — поправится и
 * перечисление.
 */
enum class OnboardingStepNumber { Phone, Otp, Pin, Biometric, Geo }

@Composable
fun OnboardingStepNumber.label(): String = stringResource(
    R.string.onboarding_step_counter,
    ordinal + 1,
    OnboardingStepNumber.entries.size,
)
