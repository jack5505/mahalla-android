package uz.mahalla.feature.onboarding.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import uz.mahalla.core.crash.reportSwallowed
import uz.mahalla.core.result.runCatchingCancellable
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.data.network.BackendCertificatePin
import uz.mahalla.data.network.BackendCheck
import uz.mahalla.data.network.BackendReachability
import uz.mahalla.data.network.BackendUrl
import uz.mahalla.data.network.BackendUrlStore
import uz.mahalla.data.network.CleartextPolicy
import uz.mahalla.data.network.inspector.HttpInspector

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
    private val certificatePin: BackendCertificatePin,
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
                // непроверенный: проверять придётся уже новый адрес. Показанный
                // сертификат тоже относился к прежнему адресу.
                copy(url = event.raw, error = null, checked = false, certificate = null)
            }

            BackendUrlEvent.DefaultRequested -> updateState {
                copy(url = defaultUrl, error = null, checked = false, certificate = null)
            }

            BackendUrlEvent.Submit -> submit()

            BackendUrlEvent.TrustCertificateRequested -> trustCertificate()

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
        // Сервер уже проверяли и он промолчал — пользователь настаивает.
        if (currentState.checked) {
            persist(normalized)
            return
        }

        updateState { copy(checking = true, error = null, certificate = null) }
        viewModelScope.launch {
            val result = reachability.check(normalized)
            // `checked` — разрешение сохранить адрес следующим тапом, и оно
            // выдаётся ровно на один случай: сервер промолчал. Недоверенный
            // сертификат так обходить нельзя — handshake рвётся до запроса, и
            // «сохранить всё равно» означало бы приложение без сети.
            updateState {
                copy(checking = false, checked = result is BackendCheck.Unreachable)
            }
            when (result) {
                BackendCheck.Reachable -> persist(normalized)

                BackendCheck.Unreachable -> updateState {
                    copy(error = BackendUrlError.UNREACHABLE)
                }

                // Сервер на месте, но сертификату нет доверия (issue #32).
                // Сохранять адрес нельзя: по нему не уйдёт ни один запрос —
                // handshake рвётся раньше. Дальше решает пользователь.
                is BackendCheck.UntrustedCertificate -> updateState {
                    copy(
                        error = BackendUrlError.CERTIFICATE_UNTRUSTED,
                        certificate = result.certificate,
                    )
                }
            }
        }
    }

    /**
     * Доверие сертификату, отпечаток которого пользователь сверил (issue #32).
     *
     * После записи пина адрес проверяется заново, а не сохраняется на слово:
     * так видно, что handshake действительно проходит, и мимо не проедет
     * случай, когда сертификат сменился между проверкой и подтверждением.
     */
    private fun trustCertificate() {
        val certificate = currentState.certificate ?: return
        if (currentState.checking) return
        // Состояние правится до записи: показанный сертификат уже принят, а
        // адрес обязан проверяться заново, даже если запись не пройдёт.
        updateState { copy(checking = true, error = null, certificate = null, checked = false) }
        viewModelScope.launch {
            // Не записался пин — доверие всё равно действует на этот запуск
            // (кэш обновлён до записи), запирать пользователя незачем.
            val applied = runCatchingCancellable { certificatePin.save(certificate.sha256) }
                .reportSwallowed("backend.saveCertificatePin")
                .getOrDefault(true)
            updateState { copy(checking = false) }
            if (applied) {
                submit()
            } else {
                // Сборка доверять чужому сертификату не имеет права. Проверять
                // адрес заново незачем — результат будет тот же; экран
                // возвращается в прежнее состояние, а не мигает впустую.
                updateState {
                    copy(
                        error = BackendUrlError.CERTIFICATE_UNTRUSTED,
                        certificate = certificate,
                    )
                }
            }
        }
    }

    private fun persist(normalized: String) {
        viewModelScope.launch {
            // Запись могла не пройти (нет места, битый файл), но адрес уже
            // применён в кэше — на этот запуск приложение рабочее. Ронять экран
            // из-за настройки нельзя, иначе войти в приложение невозможно.
            runCatchingCancellable { backendUrlStore.save(normalized) }
                .reportSwallowed("backend.saveUrl")
            updateState { copy(url = normalized, error = null) }
            emitEffect(BackendUrlEffect.Saved)
        }
    }
}
