package uz.mahalla.data.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import uz.mahalla.data.prefs.SettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Адрес бэкенда, действующий прямо сейчас (issue #26).
 *
 * Кэш обязателен: [BackendUrlInterceptor] читает адрес на потоке OkHttp, где
 * ждать DataStore нельзя. Поэтому значение поднимается один раз на старте
 * ([hydrate], под держащимся splash'ем — см. `RootViewModel`) и дальше
 * обновляется только записью из экрана ввода.
 *
 * @param overrideEnabled разрешает ли сборка менять адрес (`BuildConfig
 * .BACKEND_URL_OVERRIDE`). В обычной release-сборке — нет: экран спрятан, а
 * сохранённый адрес игнорируется, иначе кто угодно уводил бы приложение на
 * чужой сервер.
 */
@Singleton
class BackendUrlStore @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    @BaseUrl private val buildUrl: String,
    @BackendUrlOverride val overrideEnabled: Boolean = true,
) {

    @Volatile
    private var cached: String? = null

    /** Адрес, на который уходят запросы. Читается с любого потока. */
    val current: String get() = cached ?: buildUrl

    /** Адрес из сборки: подставляется в поле ввода, пока пользователь ничего не задал. */
    val buildDefault: String get() = buildUrl

    /** Сохранённый адрес; `null` — пользователь ещё не вводил его. */
    val saved: Flow<String?> = settingsDataStore.settings.map {
        it.backendBaseUrl.takeIf { _ -> overrideEnabled }
    }

    /**
     * Читает адрес из DataStore в кэш. Вызывать до первого сетевого запроса:
     * иначе первый запрос уйдёт на адрес сборки.
     *
     * Сборка без права менять адрес не читает сохранённое вовсе: адрес из
     * debug-установки не должен переезжать в релиз через бэкап настроек.
     */
    suspend fun hydrate() {
        if (!overrideEnabled) return
        cached = settingsDataStore.current().backendBaseUrl
    }

    /**
     * Применяет и сохраняет адрес.
     *
     * Кэш обновляется до записи: если DataStore недоступен (нет места, битый
     * файл), приложение всё равно должно ходить туда, куда попросили сейчас —
     * иначе пользователь останется без бэкенда вообще. Исключение записи
     * пробрасывается: сообщить о нём — дело вызывающего.
     */
    suspend fun save(normalizedUrl: String) {
        if (!overrideEnabled) return
        cached = normalizedUrl
        settingsDataStore.setBackendBaseUrl(normalizedUrl)
    }
}
