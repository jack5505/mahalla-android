package uz.mahalla.feature.map.data

import android.content.Context
import com.yandex.mapkit.MapKitFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    /**
     * Движок поднялся: ключ непустой, `initialize` прошёл.
     *
     * Про **валидность** ключа это не говорит ничего: MapKit проверяет ключ на
     * своём сервере уже после инициализации, при первой загрузке тайлов, и
     * отозванный ключ виден только как пустая карта. Отдельное состояние для
     * этого случая появится вместе с подпиской на ошибки слоя — сейчас его нет.
     */
    Ready,

    /** Ключа нет (сборка без `MAPKIT_API_KEY`) — карту показывать нечем. */
    MissingApiKey,

    /**
     * `initialize` не прошёл: нет нативной библиотеки под ABI устройства, не
     * хватило места под кэш, нарушен контракт SDK. На экране — ошибка с retry.
     */
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
    /**
     * MapKit требует главного потока: `setApiKey`/`setLocale`/`initialize` из
     * фонового кидают `AssertionError` внутри SDK. Диспетчер параметром —
     * чтобы тест мог подставить свой, а не потому, что его кто-то меняет.
     */
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {

    /** Собрано ли приложение с ключом. Проверяется до всякой работы с SDK. */
    val hasApiKey: Boolean get() = apiKey.isNotBlank()

    /**
     * `Mutex`, а не `@Synchronized`: метод suspend'ится (уходит на главный
     * поток), а блокировать чужой поток на время загрузки нативной библиотеки
     * нельзя — вызывающий приходит из `produceState` композиции.
     */
    private val mutex = Mutex()

    @Volatile
    private var state: MapEngineState =
        if (hasApiKey) MapEngineState.Failed else MapEngineState.MissingApiKey

    @Volatile
    private var initialized = false

    /**
     * Поднимает SDK, если он ещё не поднят, и возвращает текущее состояние.
     * Провал не кэшируется: у пользователя мог просто не быть доступен диск или
     * сеть, и кнопка «Повторить» должна давать второй шанс.
     */
    suspend fun ensureInitialized(): MapEngineState {
        if (!hasApiKey) return MapEngineState.MissingApiKey
        if (initialized) return state

        return mutex.withLock {
            // Пока ждали замок, инициализировать мог соседний вызов.
            if (initialized) return@withLock state
            withContext(mainDispatcher) { initializeOnMainThread() }
        }
    }

    private fun initializeOnMainThread(): MapEngineState {
        val result = runCatchingMapKit {
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
