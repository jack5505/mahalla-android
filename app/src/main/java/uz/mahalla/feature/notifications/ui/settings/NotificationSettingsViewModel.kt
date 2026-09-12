package uz.mahalla.feature.notifications.ui.settings

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import uz.mahalla.BuildConfig
import uz.mahalla.core.crash.reportSwallowed
import uz.mahalla.core.result.runCatchingCancellable
import uz.mahalla.core.ui.MviViewModel
import uz.mahalla.feature.notifications.data.NotificationSettingsStore
import uz.mahalla.feature.notifications.domain.QuietHours
import uz.mahalla.feature.notifications.push.NotificationChannels
import javax.inject.Inject

/**
 * Настройки уведомлений (эпик 11): какие категории приходят и когда молчать.
 *
 * Состояние — зеркало DataStore, а не отдельная копия: переключатель
 * перерисовывается после того, как значение записалось, поэтому выключенное
 * при сбое записи не «залипает» включённым на экране.
 */
@HiltViewModel
class NotificationSettingsViewModel @Inject constructor(
    private val store: NotificationSettingsStore,
    private val channels: NotificationChannels,
) : MviViewModel<NotificationSettingsState, NotificationSettingsEvent,
    NotificationSettingsEffect>(
    NotificationSettingsState(pushConfigured = BuildConfig.PUSH_ENABLED),
) {

    init {
        // Каналы перезаводятся при открытии экрана: `createNotificationChannel`
        // обновляет название и описание, а язык приложения человек мог сменить
        // после того, как каналы завёл старт (`RootViewModel`). Важность и звук
        // при этом остаются его — их повторный вызов не трогает.
        viewModelScope.launch {
            runCatchingCancellable { channels.ensureAll() }
                .reportSwallowed("push.ensureChannels")
        }

        viewModelScope.launch {
            store.settings.collect { settings -> updateState { copy(settings = settings) } }
        }
    }

    override fun onEvent(event: NotificationSettingsEvent) {
        when (event) {
            is NotificationSettingsEvent.CategoryToggled ->
                edit { store.setCategoryEnabled(event.category, event.enabled) }

            is NotificationSettingsEvent.QuietHoursToggled ->
                edit { store.setQuietHoursEnabled(event.enabled) }

            is NotificationSettingsEvent.QuietHoursEditRequested ->
                updateState { copy(editing = event.bound) }

            NotificationSettingsEvent.QuietHoursEditDismissed ->
                updateState { copy(editing = null) }

            is NotificationSettingsEvent.QuietHoursPicked -> {
                updateState { copy(editing = null) }
                val minute = event.hour * QuietHours.MINUTES_IN_HOUR
                // Пишется ровно одна граница: вторую хранилище не трогает, и
                // выбор начала и конца подряд не затирает друг друга (см.
                // NotificationSettingsStore).
                edit {
                    when (event.bound) {
                        QuietHoursBound.From -> store.setQuietHoursFrom(minute)
                        QuietHoursBound.To -> store.setQuietHoursTo(minute)
                    }
                }
            }
        }
    }

    /**
     * Запись настройки. Отказ DataStore (нет места, права) не роняет экран:
     * значение просто не изменится, и переключатель вернётся на место сам —
     * состояние приходит из того же хранилища.
     */
    private fun edit(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatchingCancellable { block() }.reportSwallowed("push.settings")
        }
    }
}
