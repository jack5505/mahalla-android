package uz.mahalla.feature.onboarding.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.locale.AppLocaleManager
import uz.mahalla.core.result.runCatchingCancellable
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.feature.onboarding.data.OnboardingRepository
import javax.inject.Inject

/**
 * Welcome (3.1): язык выбирается до входа — иначе пользователь читает
 * незнакомый язык на всём флоу авторизации. Выбор сразу пишется в DataStore,
 * а не «применяется потом»: следующий запуск должен открыться на нём же.
 */
@HiltViewModel
class WelcomeViewModel @Inject constructor(
    private val onboardingRepository: OnboardingRepository,
    private val localeManager: AppLocaleManager,
) : MviViewModel<WelcomeState, WelcomeEvent, WelcomeEffect>(WelcomeState()) {

    init {
        viewModelScope.launch {
            onboardingRepository.settings.collect { settings ->
                updateState { copy(language = settings.language) }
            }
        }
    }

    override fun onEvent(event: WelcomeEvent) {
        when (event) {
            is WelcomeEvent.LanguageSelected -> viewModelScope.launch {
                // Запись могла не пройти (нет места, битый файл) — язык всё
                // равно применяем: пользователь просил его сейчас, а не на
                // следующий запуск. Крэш вместо смены языка — худший исход.
                runCatchingCancellable { onboardingRepository.setLanguage(event.language) }
                if (localeManager.apply(event.language)) {
                    emitEffect(WelcomeEffect.RecreateActivity)
                }
            }
        }
    }
}
