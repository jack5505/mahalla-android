package uz.mahalla.feature.update.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.feature.update.data.AppUpdateGate
import uz.mahalla.feature.update.domain.UpdateDecision
import javax.inject.Inject

/**
 * Экран обновления (issue #80).
 *
 * Данные берутся из [AppUpdateGate], а не из аргументов маршрута: проверка
 * произошла один раз за запуск, под splash'ем, и её результат живёт в памяти
 * процесса.
 */
@HiltViewModel
class AppUpdateViewModel @Inject constructor(
    private val gate: AppUpdateGate,
) : MviViewModel<AppUpdateState, AppUpdateEvent, AppUpdateEffect>(AppUpdateState()) {

    init {
        when (val decision = gate.current()) {
            is UpdateDecision.Required ->
                updateState { copy(update = decision.update, blocking = true) }

            is UpdateDecision.Suggested ->
                updateState { copy(update = decision.update, blocking = false) }

            // Решения нет: процесс пережил экран (смерть процесса с
            // восстановлением стека), либо «Позже» уже нажали. Экран без данных
            // — тупик, тем более блокирующий, поэтому уходим сразу.
            UpdateDecision.None -> emitEffect(AppUpdateEffect.Continue)
        }
    }

    override fun onEvent(event: AppUpdateEvent) {
        when (event) {
            AppUpdateEvent.UpdateRequested -> openStore()
            AppUpdateEvent.LaterRequested -> skip()
            AppUpdateEvent.StoreOpenFailed -> updateState { copy(storeFailed = true) }
        }
    }

    private fun openStore() {
        val url = currentState.update?.storeUrl ?: return
        // Прошлая неудача снимается на новой попытке: человек мог как раз
        // поставить недостающий магазин или браузер.
        updateState { copy(storeFailed = false) }
        emitEffect(AppUpdateEffect.OpenStore(url))
    }

    /**
     * «Позже» доступно только на мягком предложении — на блокирующем экране
     * кнопки нет вовсе. Проверка здесь дублирующая: событие из UI приходит
     * снаружи, и полагаться на то, что кнопка не нарисована, для выхода из
     * блокирующего экрана нельзя.
     */
    private fun skip() {
        if (currentState.blocking || currentState.skipping) return
        updateState { copy(skipping = true) }
        viewModelScope.launch {
            // У пропуска свой короткий бюджет внутри гейта: служебный запрос не
            // должен держать человека на экране, который он уже закрыл.
            gate.skip()
            emitEffect(AppUpdateEffect.Continue)
        }
    }
}
