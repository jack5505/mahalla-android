package uz.mahalla.feature.onboarding.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.core.result.runCatchingCancellable
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.data.network.BackendReachability
import uz.mahalla.data.network.BackendUrl
import uz.mahalla.data.network.BackendUrlStore
import uz.mahalla.data.network.CleartextPolicy
import uz.mahalla.data.network.inspector.HttpInspector
import javax.inject.Inject

/**
 * Ввод адреса бэкенда (issue #26) — первый экран приложения, пока адрес не
 * задан.
 *
 * Поле заполняется текущим адресом: чаще всего пользователю нужно поправить
 * хост и порт, а не набрать URL с нуля.
 */
@HiltViewModel
class BackendUrlViewModel @Inject constructor(
    private val backendUrlStore: BackendUrlStore,
    private val reachability: BackendReachability,
    private val cleartextPolicy: CleartextPolicy,
    private val httpInspector: HttpInspector,
) : MviViewModel<BackendUrlState, BackendUrlEvent, BackendUrlEffect>(BackendUrlState()) {

    init {
        updateState {
            copy(
                url = backendUrlStore.current,
                defaultUrl = backendUrlStore.buildDefault,
                httpInspectorAvailable = httpInspector.isAvailable,
            )
        }
    }

    override fun onEvent(event: BackendUrlEvent) {
        when (event) {
            is BackendUrlEvent.UrlChanged -> updateState {
                // Правка адреса обнуляет и ошибку, и разрешение сохранить
                // непроверенный: проверять придётся уже новый адрес.
                copy(url = event.raw, error = null, checked = false)
            }

            BackendUrlEvent.DefaultRequested -> updateState {
                copy(url = defaultUrl, error = null, checked = false)
            }

            BackendUrlEvent.Submit -> submit()

            // Интента нет только в сборке без инспектора — там нет и кнопки.
            BackendUrlEvent.HttpInspectorRequested ->
                httpInspector.launchIntent()?.let { intent ->
                    emitEffect(BackendUrlEffect.OpenHttpInspector(intent))
                }
        }
    }

    private fun submit() {
        if (currentState.checking) return
        val normalized = BackendUrl.normalize(currentState.url)
        if (normalized == null) {
            updateState { copy(error = BackendUrlError.INVALID) }
            return
        }
        // Сборке запрещён http на этот хост — сохранять адрес нельзя даже по
        // настоянию пользователя: запросы всё равно не уйдут, а ошибка
        // выглядела бы как «сервер недоступен».
        if (!cleartextPolicy.isAllowed(normalized)) {
            updateState { copy(error = BackendUrlError.CLEARTEXT_BLOCKED, checked = false) }
            return
        }
        // Сервер уже проверяли и он не ответил — пользователь настаивает.
        if (currentState.checked) {
            persist(normalized)
            return
        }

        updateState { copy(checking = true, error = null) }
        viewModelScope.launch {
            val reachable = reachability.check(normalized)
            updateState { copy(checking = false, checked = true) }
            if (reachable) {
                persist(normalized)
            } else {
                updateState { copy(error = BackendUrlError.UNREACHABLE) }
            }
        }
    }

    private fun persist(normalized: String) {
        viewModelScope.launch {
            // Запись могла не пройти (нет места, битый файл), но адрес уже
            // применён в кэше — на этот запуск приложение рабочее. Ронять экран
            // из-за настройки нельзя, иначе войти в приложение невозможно.
            runCatchingCancellable { backendUrlStore.save(normalized) }
            updateState { copy(url = normalized, error = null) }
            emitEffect(BackendUrlEffect.Saved)
        }
    }
}
