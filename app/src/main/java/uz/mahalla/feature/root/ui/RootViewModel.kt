package uz.mahalla.feature.root.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uz.mahalla.core.crash.reportSwallowed
import uz.mahalla.core.result.runCatchingCancellable
import uz.mahalla.data.network.BackendCertificatePin
import uz.mahalla.data.network.BackendUrlStore
import uz.mahalla.data.network.SessionExpiry
import uz.mahalla.data.prefs.AppSettings
import uz.mahalla.data.prefs.SettingsDataStore
import uz.mahalla.data.push.PushTokenRegistrar
import uz.mahalla.feature.auth.data.AuthRepository
import uz.mahalla.feature.notifications.push.NotificationChannels
import uz.mahalla.feature.onboarding.data.OnboardingRepository
import uz.mahalla.feature.update.data.AppUpdateGate
import uz.mahalla.feature.update.domain.UpdateDecision

/**
 * Состояние корня: пока настройки не прочитаны из DataStore, показывать UI
 * нельзя — иначе мигнёт неправильная тема и неправильный стартовый экран.
 * На это время держится системный splash (эпик 1.6).
 */
sealed interface RootUiState {
    data object Loading : RootUiState

    /**
     * @param startWithOnboarding стартовый пункт графа навигации. Зафиксирован
     * на первой эмиссии настроек и дальше не меняется — см. [RootViewModel].
     * Онбординг — это и «первый запуск», и «сессии больше нет» (issue #138).
     * @param resumeOnboardingAtPin онбординг продолжается с PIN: сессия уже
     * получена, повторный SMS-код не нужен.
     * @param needsBackendUrl адрес бэкенда ещё не задан (issue #26) — начинаем
     * с его ввода, иначе первый же запрос уйдёт в никуда.
     * @param backendUrlOverrideEnabled сборке разрешено менять адрес бэкенда:
     * от этого зависит и стартовый экран, и кнопки «сменить сервер».
     * @param showUpdate бэкенд просит обновиться (issue #80). Отказ проверки
     * сюда не доезжает: `false` — это и «обновляться не надо», и «спросить не
     * удалось».
     */
    data class Ready(
        val settings: AppSettings,
        val startWithOnboarding: Boolean,
        val resumeOnboardingAtPin: Boolean = false,
        val needsBackendUrl: Boolean = false,
        val backendUrlOverrideEnabled: Boolean = false,
        val showUpdate: Boolean = false,
    ) : RootUiState
}

@HiltViewModel
class RootViewModel @Inject constructor(
    settingsDataStore: SettingsDataStore,
    private val onboardingRepository: OnboardingRepository,
    private val authRepository: AuthRepository,
    private val backendUrlStore: BackendUrlStore,
    private val backendCertificatePin: BackendCertificatePin,
    private val appUpdateGate: AppUpdateGate,
    private val pushTokenRegistrar: PushTokenRegistrar,
    private val notificationChannels: NotificationChannels,
    sessionExpiry: SessionExpiry,
) : ViewModel() {

    init {
        // Пуши (эпик 11). Здесь, а не в `Application`: старт приложения не
        // место для работы, которая ждёт ответа Firebase. Отдельной корутиной и
        // не в `resolveStart` — splash не должен висеть, пока Firebase думает.
        viewModelScope.launch {
            // Токен спрашиваем сами: `onNewToken` срабатывает только при
            // **смене** токена, а первый после установки приложение обязано
            // забрать само — иначе на бэкенд не уедет ничего до переустановки.
            runCatchingCancellable { pushTokenRegistrar.sync() }
                .reportSwallowed("push.syncToken")
            // Каналы обязаны существовать **до** первого пуша: сообщение с
            // блоком `notification` в фоне показывает сама библиотека Firebase,
            // и незаведённый канал `other` из манифеста она молча заменяет на
            // своё «Разное» (см. NotificationChannels).
            runCatchingCancellable { notificationChannels.ensureAll() }
                .reportSwallowed("push.ensureChannels")
        }
    }

    /**
     * Сессия умерла, пока приложение работало (issue #138). Обрабатывает
     * корень: он единственный видит навигацию целиком и может увести на вход
     * с любого экрана.
     */
    val sessionExpired: Flow<Unit> = sessionExpiry.expired

    /**
     * Стартовый пункт графа решается один раз за жизнь процесса.
     *
     * `NavHost` пересобирает граф при смене `startDestination` и сбрасывает
     * back stack, а настройки — живой flow: смена темы/языка в профиле или
     * запись флага онбординга обнуляли бы навигацию. Уход из онбординга — это
     * `navigate(MainGraph) { popUpTo(...) }` в графе, флаг в DataStore нужен
     * только следующему запуску.
     *
     * Поле безопасно: `stateIn` держит одну подписку на upstream, то есть
     * `map` ниже исполняется в одной корутине.
     */
    private var start: Start? = null

    val state: StateFlow<RootUiState> = settingsDataStore.settings
        .map<AppSettings, RootUiState> { settings ->
            val start = start ?: resolveStart(settings).also { start = it }
            RootUiState.Ready(
                settings = settings,
                startWithOnboarding = start.withOnboarding,
                resumeOnboardingAtPin = start.atPin,
                needsBackendUrl = start.needsBackendUrl,
                backendUrlOverrideEnabled = backendUrlStore.overrideEnabled,
                showUpdate = start.showUpdate,
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = RootUiState.Loading,
        )

    fun onOnboardingFinished() {
        viewModelScope.launch {
            // Не записался флаг — онбординг всё равно закончен для этого
            // запуска, ронять приложение из-за настройки нельзя.
            runCatchingCancellable { onboardingRepository.markCompleted() }
                .reportSwallowed("settings.markOnboardingCompleted")
        }
    }

    /**
     * Прерванный онбординг не должен стоить второго платного SMS: если сессия
     * уже лежит в хранилище, вход пройден, и продолжать надо с PIN, а не с
     * welcome → телефон → новый код.
     *
     * Обратное тоже верно: пройденный онбординг сам по себе в приложение не
     * пускает. Сессия могла умереть между запусками (refresh не прошёл,
     * устройство отозвали в «моих устройствах»), и тогда основной граф — это
     * экраны, на каждом из которых 401 (issue #138).
     */
    private suspend fun resolveStart(settings: AppSettings): Start {
        // Адрес бэкенда должен лежать в кэше до первого запроса: интерцептор
        // читает его синхронно, на потоке OkHttp. Здесь это безопасно —
        // splash висит, пока корень не готов.
        backendUrlStore.hydrate()
        // Тем же порядком и по той же причине: отпечаток доверенного
        // сертификата читается на потоке OkHttp во время handshake (issue #32).
        backendCertificatePin.hydrate()
        val authorized = authRepository.isAuthorized.first()
        val withOnboarding = !settings.onboardingCompleted || !authorized
        // Сборка без права менять адрес спрашивать его не должна: она ходит на
        // адрес из BuildConfig.
        val needsBackendUrl =
            backendUrlStore.overrideEnabled && settings.backendBaseUrl == null
        return Start(
            withOnboarding = withOnboarding,
            atPin = withOnboarding && authorized,
            needsBackendUrl = needsBackendUrl,
            // Пока адрес сервера не введён, спрашивать его о версии
            // бессмысленно: запрос ушёл бы на адрес из сборки, то есть не туда,
            // куда пользователь как раз собирается направить приложение
            // (issue #26). За версией сходим при следующем запуске.
            showUpdate = !needsBackendUrl && appUpdateGate.check() != UpdateDecision.None,
        )
    }

    /** Решение о старте графа, принимаемое один раз за жизнь процесса. */
    private data class Start(
        val withOnboarding: Boolean,
        val atPin: Boolean,
        val needsBackendUrl: Boolean,
        val showUpdate: Boolean,
    )
}
