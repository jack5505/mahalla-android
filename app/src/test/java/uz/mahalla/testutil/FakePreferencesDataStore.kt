package uz.mahalla.testutil

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * `DataStore<Preferences>` в памяти: запись применяется на месте, без
 * IO-диспетчера настоящего DataStore. Нужен там, где тест ждёт результат
 * записи, инициированной ViewModel, и у него нет эффекта, за которым можно
 * было бы подождать — настоящий файловый DataStore в такой схеме иногда не
 * успевал за минуту таймаута `runTest`.
 */
class FakePreferencesDataStore : DataStore<Preferences> {

    private val state = MutableStateFlow(emptyPreferences())

    override val data: Flow<Preferences> get() = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val next = transform(state.value)
        state.value = next
        return next
    }
}
