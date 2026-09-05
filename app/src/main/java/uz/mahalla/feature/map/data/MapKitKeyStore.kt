package uz.mahalla.feature.map.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uz.mahalla.core.crash.reportSwallowed
import uz.mahalla.core.result.runCatchingCancellable
import uz.mahalla.data.prefs.SettingsDataStore

/**
 * Ключ Yandex MapKit, действующий прямо сейчас (issue #129).
 *
 * Обычно ключ приезжает из сборки (`BuildConfig.MAPKIT_API_KEY`, эпик 4.2), и
 * этого достаточно. Но сборка, собранная без секрета, оставляет карту мёртвой
 * до следующего релиза: на экране объяснение «приложение собрано без ключа», а
 * ключ у владельца лежит на руках и приложению его сообщить нечем. Здесь он
 * вводится прямо в приложении — тем же способом и по тем же правилам, что
 * адрес бэкенда (issue #26).
 *
 * @param buildKey ключ из сборки: он же подставляется в поле ввода и остаётся
 * рабочим, пока пользователь не задал свой.
 * @param canEdit разрешает ли сборка менять ключ (`BuildConfig
 * .BACKEND_URL_OVERRIDE`). Право одно на оба случая осознанно: и адрес
 * бэкенда, и ключ карты — конфигурация сборки, и в магазинной сборке её меняет
 * только релиз. Без права сохранённый ключ не читается вовсе — ключ из
 * debug-установки не должен переезжать в релиз через бэкап настроек.
 */
class MapKitKeyStore(
    private val settingsDataStore: SettingsDataStore,
    val buildKey: String,
    val canEdit: Boolean,
) {

    /**
     * Кэш нужен не ради скорости: [MapKitInitializer] спрашивает ключ на каждой
     * попытке поднять движок, а ответ обязан меняться сразу после сохранения —
     * ждать, пока DataStore допишет файл, карте нельзя.
     */
    @Volatile
    private var cached: String? = null

    private val mutex = Mutex()

    /** Ключ для SDK: введённый пользователем, иначе из сборки. Пустой — ключа нет. */
    suspend fun current(): String {
        cached?.let { return it }
        return mutex.withLock { cached ?: resolve().also { cached = it } }
    }

    /**
     * Ключ, введённый пользователем; `null` — не вводил. Нужен полю ввода:
     * показывать чужой (собранный) ключ как «свой» и предлагать его исправить —
     * значит путать источник.
     */
    suspend fun saved(): String? =
        if (canEdit) settingsDataStore.current().mapKitApiKey else null

    /**
     * Сохраняет ключ и применяет его немедленно. Пустая строка возвращает карту
     * к ключу сборки.
     *
     * Кэш обновляется **до** записи: если DataStore недоступен, карта всё равно
     * должна подняться с тем ключом, который только что ввели — иначе отказ
     * хранилища выглядит как отказ ключа. Возвращает `false`, когда сохранить
     * не удалось (ключ действует до перезапуска) либо сборка менять его не
     * разрешает.
     */
    suspend fun save(apiKey: String): Boolean {
        if (!canEdit) return false
        val cleaned = apiKey.trim()
        cached = cleaned.ifEmpty { buildKey }
        return runCatchingCancellable { settingsDataStore.setMapKitApiKey(cleaned) }
            .reportSwallowed("mapkit.saveKey")
            .isSuccess
    }

    private suspend fun resolve(): String {
        if (!canEdit) return buildKey
        val saved = settingsDataStore.current().mapKitApiKey?.trim().orEmpty()
        return saved.ifEmpty { buildKey }
    }
}
