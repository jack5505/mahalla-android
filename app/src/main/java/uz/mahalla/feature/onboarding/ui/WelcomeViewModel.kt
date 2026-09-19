package uz.mahalla.feature.onboarding.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import uz.mahalla.core.analytics.AnalyticsQueuedEvents
import uz.mahalla.core.analytics.AnalyticsScreens
import uz.mahalla.core.analytics.AnalyticsTracker
import uz.mahalla.core.crash.reportSwallowed
import uz.mahalla.core.locale.AppLocaleManager
import uz.mahalla.core.result.runCatchingCancellable
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.feature.onboarding.data.OnboardingRepository

/**
 * Welcome (3.1): язык выбирается до входа — иначе пользователь читает
 * незнакомый язык на всём флоу авторизации. Выбор сразу пишется в DataStore,
 * а не «применяется потом»: следующий запуск должен открыться на нём же.
 */
@HiltViewModel
class WelcomeViewModel @Inject constructor(
    private val onboardingRepository: OnboardingRepository,
    private val localeManager: AppLocaleManager,
    private val analytics: AnalyticsTracker,
) : MviViewModel<WelcomeState, WelcomeEvent, WelcomeEffect>(WelcomeState()) {

    init {
        // Первый экран приложения — до входа. Аналитика уходит по `deviceId`
        // (issue #226): раньше воронка «посмотрел → зарегистрировался →
        // заказал» не измерялась вообще с этого шага, а не только теряла вид
        // события.
        analytics.track(AnalyticsQueuedEvents.screenOpened(AnalyticsScreens.ONBOARDING))
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
                    .reportSwallowed("settings.setLanguage")
                if (localeManager.apply(event.language)) {
                    emitEffect(WelcomeEffect.RecreateActivity)
                }
            }
        }
    }
}
