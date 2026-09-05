package uz.mahalla.testutil

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import uz.mahalla.data.prefs.SettingsDataStore
import uz.mahalla.feature.map.canvas.MapCoordinates
import uz.mahalla.feature.map.data.MapKitInitializer
import uz.mahalla.feature.map.data.MapKitKeyStore
import uz.mahalla.feature.map.data.MapKitSdk
import uz.mahalla.feature.map.data.UserLocationProvider

/**
 * Координаты без MapKit: на JVM SDK не поднимается, а карта обязана быть
 * проверяемой без устройства.
 *
 * [gate] позволяет задержать ответ — этим проверяется, что второй тап по «моему
 * местоположению» не запускает второй запрос.
 */
class FakeUserLocationProvider(
    var location: MapCoordinates? = null,
) : UserLocationProvider {

    var callCount: Int = 0
        private set

    var gate: CompletableDeferred<Unit>? = null

    override suspend fun currentLocation(): MapCoordinates? {
        callCount++
        gate?.await()
        return location
    }
}

/**
 * Ворота инициализации MapKit с SDK-заглушкой: ViewModel их только передаёт
 * экрану, но конструктор без них не собрать.
 */
/**
 * Ключ карты для ViewModel'ей: они его только передают экрану (issue #129).
 * `canEdit = false` — тесту нужен работающий объект, а не хранилище: с этим
 * флагом [MapKitKeyStore] до DataStore не доходит вовсе.
 */
fun fakeMapKitKeyStore(buildKey: String = "test-key"): MapKitKeyStore = MapKitKeyStore(
    settingsDataStore = SettingsDataStore(EmptyPreferencesDataStore),
    buildKey = buildKey,
    canEdit = false,
)

/** Пустое хранилище настроек: нужно только чтобы собрать [SettingsDataStore]. */
private object EmptyPreferencesDataStore : DataStore<Preferences> {
    override val data: Flow<Preferences> = flowOf(emptyPreferences())

    override suspend fun updateData(
        transform: suspend (Preferences) -> Preferences,
    ): Preferences = transform(emptyPreferences())
}

fun fakeMapKitInitializer(): MapKitInitializer = MapKitInitializer(
    apiKey = { "test-key" },
    locale = "uz_UZ",
    sdk = object : MapKitSdk {
        override fun setApiKey(apiKey: String) = Unit
        override fun setLocale(locale: String) = Unit
        override fun initialize() = Unit
    },
)
