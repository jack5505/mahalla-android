package uz.mahalla.feature.onboarding.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.feature.onboarding.data.OnboardingRepository
import javax.inject.Inject

/**
 * Геолокация (3.6).
 *
 * Отказ — не тупик: без координат каталог всё равно нужно чем-то ограничить,
 * поэтому пользователь выбирает город вручную, и онбординг продолжается.
 * Выбор города сохраняется до завершения шага, чтобы каталог открылся уже с
 * ним.
 */
@HiltViewModel
class GeoViewModel @Inject constructor(
    private val onboardingRepository: OnboardingRepository,
) : MviViewModel<GeoState, GeoEvent, GeoEffect>(GeoState()) {

    override fun onEvent(event: GeoEvent) {
        when (event) {
            GeoEvent.AllowRequested -> emitEffect(GeoEffect.RequestLocationPermission)

            is GeoEvent.PermissionResult -> if (event.granted) {
                emitEffect(GeoEffect.Finished)
            } else {
                updateState { copy(stage = GeoStage.CityPicker, permissionDenied = true) }
            }

            GeoEvent.ChooseCityRequested -> updateState { copy(stage = GeoStage.CityPicker) }

            is GeoEvent.CitySelected -> {
                updateState { copy(selectedCity = event.city, busy = true) }
                viewModelScope.launch {
                    onboardingRepository.setCity(event.city.id)
                    updateState { copy(busy = false) }
                    emitEffect(GeoEffect.Finished)
                }
            }
        }
    }
}
