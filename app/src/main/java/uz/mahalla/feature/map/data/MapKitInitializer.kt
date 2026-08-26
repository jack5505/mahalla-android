package uz.mahalla.feature.map.data

import android.content.Context
import com.yandex.mapkit.MapKitFactory
import uz.mahalla.core.result.runCatchingCancellable

/**
 * Тонкая обёртка над статикой `MapKitFactory` (эпик 4.2).
 *
 * Нужна ровно затем, чтобы порядок вызовов SDK («ключ и локаль — до
 * `initialize`, `initialize` — один раз») проверялся обычным JVM-тестом:
 * статические методы MapKit на JVM не вызвать, а порядок здесь — не деталь,
 * SDK падает на нарушении.
 */
interface MapKitSdk {
    fun setApiKey(apiKey: String)
    fun setLocale(locale: String)
    fun initialize()
}

/** Боевая реализация: те же вызовы, что в документации MapKit. */
class YandexMapKitSdk(private val context: Context) : MapKitSdk {
    override fun setApiKey(apiKey: String) = MapKitFactory.setApiKey(apiKey)
    override fun setLocale(locale: String) = MapKitFactory.setLocale(locale)
    override fun initialize() = MapKitFactory.initialize(context)
}

/** Почему карта не показывается — состояние движка для UI. */
enum class MapEngineState {
    /** Готов: ключ есть, `initialize` прошёл. */
    Ready,

    /** Ключа нет (сборка без `MAPKIT_API_KEY`) — карту показывать нечем. */
    MissingApiKey,

    /** SDK не поднялся (ключ отозван, нет ресурсов) — на экране ошибка с retry. */
    Failed,
}

/**
 * Ворота инициализации MapKit.
 *
 * Инициализация ленивая, а не в `Application.onCreate`: MapKit поднимает свои
 * потоки и кэши, а карта — один экран из тридцати пяти, и большинство запусков
 * до неё не доходит. Первый показ карты платит эту цену сам.
 *
 * [ensureInitialized] идемпотентен и потокобезопасен: `MapKitFactory.initialize`
 * повторного вызова не прощает.
 */
class MapKitInitializer(
    private val apiKey: String,
    private val locale: String,
    private val sdk: MapKitSdk,
) {

    /** Собрано ли приложение с ключом. Проверяется до всякой работы с SDK. */
    val hasApiKey: Boolean get() = apiKey.isNotBlank()

    private var state: MapEngineState =
        if (hasApiKey) MapEngineState.Failed else MapEngineState.MissingApiKey
    private var initialized = false

    /**
     * Поднимает SDK, если он ещё не поднят, и возвращает текущее состояние.
     * Провал не кэшируется: у пользователя мог просто не быть доступен диск или
     * сеть, и кнопка «Повторить» должна давать второй шанс.
     */
    @Synchronized
    fun ensureInitialized(): MapEngineState {
        if (!hasApiKey) return MapEngineState.MissingApiKey
        if (initialized) return state

        val result = runCatchingCancellable {
            sdk.setApiKey(apiKey)
            sdk.setLocale(locale)
            sdk.initialize()
        }
        // initialized выставляется только при успехе: MapKit не считает
        // неудачную попытку состоявшейся, повтор он разрешает.
        initialized = result.isSuccess
        state = if (result.isSuccess) MapEngineState.Ready else MapEngineState.Failed
        return state
    }
}

/**
 * Локаль в формате MapKit (`ru_RU`). Язык приложения — uz по умолчанию, но
 * MapKit знает не все языки: неизвестный уходит в английский, а не в пустую
 * строку (на пустой SDK ругается).
 */
fun mapKitLocale(languageTag: String): String =
    when (languageTag.lowercase().substringBefore('-').substringBefore('_')) {
        "ru" -> "ru_RU"
        "uz" -> "uz_UZ"
        else -> "en_US"
    }
