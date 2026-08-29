package uz.mahalla.data.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Converter
import retrofit2.Retrofit
import uz.mahalla.data.network.tls.CertificatePinSource
import uz.mahalla.data.network.tls.allowPinnedCertificate
import java.util.concurrent.TimeUnit

/**
 * Сборка сетевого стека без Hilt (эпик 1.3).
 *
 * Вынесено из DI-модуля намеренно: ровно этот же код собирает клиент в
 * тестах на MockWebServer, поэтому тесты проверяют production-конфигурацию,
 * а не свою копию.
 */
object NetworkFactory {

    const val CONTENT_TYPE = "application/json"

    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val WRITE_TIMEOUT_SECONDS = 30L

    fun json(): Json = Json {
        // Бэкенд развивается быстрее клиента: новые поля не должны валить парсинг.
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun converterFactory(json: Json): Converter.Factory =
        json.asConverterFactory(CONTENT_TYPE.toMediaType())

    /**
     * @param certificatePin доверие к сертификату, подтверждённому
     * пользователем (issue #32). `null` — обычная проверка по системным CA.
     */
    fun clientBuilder(
        logBodies: Boolean = false,
        certificatePin: CertificatePinSource? = null,
    ): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .allowPinnedCertificate(certificatePin)
        .apply {
            if (logBodies) {
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        setLevel(HttpLoggingInterceptor.Level.BODY)
                        // Иначе access/refresh-токены уезжают в logcat целиком.
                        redactHeader(AuthInterceptor.HEADER_AUTHORIZATION)
                    },
                )
            }
        }

    /**
     * Основной клиент: адрес бэкенда → координаты → Bearer → инспектор, плюс
     * refresh по 401.
     *
     * Порядок не косметика. Инспектор добавляется последним, поэтому получает
     * запрос ровно в том виде, в каком тот уйдёт в сеть: с фактическим хостом
     * (его подставил [BackendUrlInterceptor], issue #26), с координатами
     * ([GeoHeaderInterceptor], issue #53) и с уже проставленным
     * `Authorization`. Стоя первым, он показывал бы адрес сборки и запрос без
     * заголовков — то есть отвечал бы не на тот вопрос, ради которого нужен.
     *
     * Чего инспектор не увидит: повтор запроса после refresh'а. Его делает
     * `Authenticator` ниже уровня application-интерцепторов — в списке будет
     * одна транзакция с ответом 401 и одна с уже успешным повтором только при
     * следующем вызове API.
     */
    fun mainClient(
        backendUrlInterceptor: Interceptor,
        authInterceptor: Interceptor,
        authenticator: Authenticator,
        geoHeaderInterceptor: Interceptor? = null,
        inspector: Interceptor? = null,
        logBodies: Boolean = false,
        certificatePin: CertificatePinSource? = null,
    ): OkHttpClient = clientBuilder(logBodies, certificatePin)
        .addInterceptor(backendUrlInterceptor)
        .apply { geoHeaderInterceptor?.let(::addInterceptor) }
        .addInterceptor(authInterceptor)
        .apply { inspector?.let(::addInterceptor) }
        .authenticator(authenticator)
        .build()

    /**
     * «Голый» клиент для авторизации и refresh'а: без `AuthInterceptor` и без
     * `TokenAuthenticator` (иначе 401 на refresh звал бы refresh). Адрес
     * бэкенда, координаты и инспектор нужны и здесь — вход и обновление токена
     * уходят на тот же сервер, точно так же требуют просмотра и проходят через
     * тот же гео-фильтр бэкенда.
     */
    fun refreshClient(
        backendUrlInterceptor: Interceptor,
        geoHeaderInterceptor: Interceptor? = null,
        inspector: Interceptor? = null,
        logBodies: Boolean = false,
        certificatePin: CertificatePinSource? = null,
    ): OkHttpClient = clientBuilder(logBodies, certificatePin)
        .addInterceptor(backendUrlInterceptor)
        .apply { geoHeaderInterceptor?.let(::addInterceptor) }
        .apply { inspector?.let(::addInterceptor) }
        .build()

    fun retrofit(
        baseUrl: String,
        client: OkHttpClient,
        converterFactory: Converter.Factory,
    ): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(converterFactory)
        .build()
}
