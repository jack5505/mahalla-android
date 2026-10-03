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

    /**
     * Ручки, у которых секрет лежит в теле — запроса **или ответа**.
     *
     * Критерий: тело несёт то, чем можно войти или заплатить. Сюда попадают
     * PIN (`pin/change`, `pin/biometric`, `pin-resume`, `setup-pin`,
     * `pin-login`), код из SMS и токены (`verify-otp`, `refresh`), а также
     * обе телеграм-ручки: ответ
     * `telegram/check` кладёт `accessToken` и `refreshToken` **в корень**, и
     * `Level.BODY` печатает ответы так же, как запросы (issue #46).
     * `send-otp` — не токен, но номер телефона с точными координатами и
     * `otpToken` в ответе; в logcat это те же персональные данные.
     * `wallet/business/payouts` (issue #290) несёт полный номер карты — и
     * запросом (`PayoutCreateRequest.cardNumber`), и ответом
     * (`PayoutResponse.cardNumber`) — тот же критерий «заплатить», что и у PIN.
     *
     * Сравнение по концу пути, а не целиком: базовый адрес несёт префикс
     * `/api/v1/`, и он же меняется на стенде (issue #26). Завершающий слэш
     * срезается — иначе `…/pin/change/` прошёл бы мимо списка.
     */
    private val SECRET_BODY_PATHS = listOf(
        "pin/change",
        "pin/biometric",
        "auth/pin-resume",
        "auth/setup-pin",
        "auth/pin-login",
        "auth/verify-otp",
        "auth/send-otp",
        "auth/refresh",
        "auth/telegram/init",
        "auth/telegram/check",
        "wallet/business/payouts",
    )

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
        logger: HttpLoggingInterceptor.Logger = HttpLoggingInterceptor.Logger.DEFAULT,
    ): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .allowPinnedCertificate(certificatePin)
        .apply {
            if (logBodies) addInterceptor(loggingInterceptor(logger))
        }

    /**
     * Инспектор трафика в logcat — с телом, но не у всех ручек (issue #102).
     *
     * `HttpLoggingInterceptor` умеет прятать заголовки (`redactHeader`), но не
     * тело, а PIN, код из SMS и refresh-токен приложение шлёт именно телом.
     * В debug-сборке `Level.BODY` печатал их в logcat открытым текстом —
     * читает его кто угодно, у кого есть adb, а PIN здесь тот же, что открывает
     * кошелёк. Поэтому ручки из [SECRET_BODY_PATHS] логируются на `BASIC`:
     * строка запроса, код и время ответа остаются (этого хватает, чтобы понять,
     * что пошло не так), тело не печатается вовсе.
     *
     * Уровень выбирается на запрос, а не переключается у общего экземпляра:
     * `level` — обычное изменяемое поле, и на параллельных запросах
     * переключение успевало бы не туда. Два экземпляра стоят дешевле гонки.
     */
    internal fun loggingInterceptor(
        logger: HttpLoggingInterceptor.Logger = HttpLoggingInterceptor.Logger.DEFAULT,
    ): Interceptor {
        val full = HttpLoggingInterceptor(logger).apply {
            setLevel(HttpLoggingInterceptor.Level.BODY)
            // Иначе access/refresh-токены уезжают в logcat целиком.
            redactHeader(AuthInterceptor.HEADER_AUTHORIZATION)
        }
        val withoutBody = HttpLoggingInterceptor(logger).apply {
            setLevel(HttpLoggingInterceptor.Level.BASIC)
            redactHeader(AuthInterceptor.HEADER_AUTHORIZATION)
        }
        return Interceptor { chain ->
            val secret = carriesSecretBody(chain.request().url.encodedPath)
            (if (secret) withoutBody else full).intercept(chain)
        }
    }

    /**
     * Прячется ли тело этой ручки от logcat. `internal`, потому что проверить
     * это иначе нечем: уровень живёт внутри `HttpLoggingInterceptor`, наружу
     * он его не отдаёт.
     */
    internal fun carriesSecretBody(path: String): Boolean {
        val normalized = path.trimEnd('/')
        return SECRET_BODY_PATHS.any { normalized.endsWith(it) }
    }

    /**
     * Основной клиент: адрес бэкенда → координаты → язык → Bearer → инспектор,
     * плюс refresh по 401.
     *
     * Порядок не косметика. Инспектор добавляется последним, поэтому получает
     * запрос ровно в том виде, в каком тот уйдёт в сеть: с фактическим хостом
     * (его подставил [BackendUrlInterceptor], issue #26), с координатами
     * ([GeoHeaderInterceptor], issue #53), с `Accept-Language`
     * ([LanguageHeaderInterceptor], issue #242) и с уже проставленным
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
        languageHeaderInterceptor: Interceptor? = null,
        inspector: Interceptor? = null,
        logBodies: Boolean = false,
        certificatePin: CertificatePinSource? = null,
    ): OkHttpClient = clientBuilder(logBodies, certificatePin)
        .addInterceptor(backendUrlInterceptor)
        .apply { geoHeaderInterceptor?.let(::addInterceptor) }
        .apply { languageHeaderInterceptor?.let(::addInterceptor) }
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
        languageHeaderInterceptor: Interceptor? = null,
        inspector: Interceptor? = null,
        logBodies: Boolean = false,
        certificatePin: CertificatePinSource? = null,
    ): OkHttpClient = clientBuilder(logBodies, certificatePin)
        .addInterceptor(backendUrlInterceptor)
        .apply { geoHeaderInterceptor?.let(::addInterceptor) }
        .apply { languageHeaderInterceptor?.let(::addInterceptor) }
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
