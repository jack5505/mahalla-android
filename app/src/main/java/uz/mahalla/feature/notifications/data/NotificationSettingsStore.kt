package uz.mahalla.feature.notifications.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import uz.mahalla.data.prefs.PreferenceKeys
import uz.mahalla.feature.notifications.domain.NotificationCategory
import uz.mahalla.feature.notifications.domain.NotificationSettings
import uz.mahalla.feature.notifications.domain.QuietHours
import uz.mahalla.feature.notifications.domain.normalizedMinuteOfDay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Настройки уведомлений в DataStore (эпик 11).
 *
 * Отдельно от `SettingsDataStore` не из вкуса: читает их **фоновый сервис** на
 * каждый пуш, и тащить туда язык, тему и адрес бэкенда незачем. Ключи при этом
 * общие ([PreferenceKeys]) — файл настроек один.
 *
 * Хранятся выключенные категории, а не включённые: категория, которой в наборе
 * нет, считается включённой, и обновление приложения с новой категорией не
 * оставляет её молчащей (см. [NotificationSettings]).
 */
@Singleton
class NotificationSettingsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    val settings: Flow<NotificationSettings> = dataStore.data
        .map { preferences ->
            NotificationSettings(
                mutedCategories = preferences[PreferenceKeys.MutedNotificationCategories]
                    .orEmpty()
                    // Незнакомый id — это категория, которую убрали из кода:
                    // молча отбрасываем, иначе она вечно жила бы в наборе.
                    .mapNotNullTo(mutableSetOf()) { id ->
                        NotificationCategory.entries.firstOrNull { it.id == id }
                    },
                quietHours = QuietHours(
                    enabled = preferences[PreferenceKeys.QuietHoursEnabled] ?: false,
                    fromMinuteOfDay = preferences[PreferenceKeys.QuietHoursFrom]
                        ?: QuietHours.DEFAULT_FROM,
                    toMinuteOfDay = preferences[PreferenceKeys.QuietHoursTo]
                        ?: QuietHours.DEFAULT_TO,
                ),
            )
        }
        // Файл настроек может быть недоступен (нет места, права, IO-ошибка).
        // Тогда значения по умолчанию: пуши приходят все и со звуком. Обратное
        // решение (молчать) означало бы, что сбойный файл тихо отключает
        // уведомления, и понять это по приложению невозможно.
        .catch { emit(NotificationSettings()) }

    suspend fun current(): NotificationSettings = settings.first()

    suspend fun setCategoryEnabled(category: NotificationCategory, enabled: Boolean) {
        dataStore.edit { preferences ->
            val muted = preferences[PreferenceKeys.MutedNotificationCategories].orEmpty()
            preferences[PreferenceKeys.MutedNotificationCategories] = if (enabled) {
                muted - category.id
            } else {
                muted + category.id
            }
        }
    }

    suspend fun setQuietHoursEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferenceKeys.QuietHoursEnabled] = enabled }
    }

    /**
     * Границы пишутся **по отдельности**, а не парой.
     *
     * Пара означала бы «прочитать вторую границу и записать обе», а вторую
     * читать неоткуда, кроме как из состояния экрана — асинхронного зеркала
     * этого же хранилища. Выбор начала и конца подряд тогда записывал бы поверх
     * ещё не доехавшего первого значения, и оно молча возвращалось бы к
     * прежнему.
     *
     * Значение нормализуется на записи: в хранилище лежит минута суток и ничего
     * иного.
     */
    suspend fun setQuietHoursFrom(minuteOfDay: Int) {
        dataStore.edit { it[PreferenceKeys.QuietHoursFrom] = minuteOfDay.normalizedMinuteOfDay() }
    }

    suspend fun setQuietHoursTo(minuteOfDay: Int) {
        dataStore.edit { it[PreferenceKeys.QuietHoursTo] = minuteOfDay.normalizedMinuteOfDay() }
    }
}
