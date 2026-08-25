package uz.mahalla.data.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Converter
import retrofit2.Retrofit
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

    fun clientBuilder(logBodies: Boolean = false): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
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
