package uz.mahalla.feature.update.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.POST
import uz.mahalla.data.network.ApiResponse

/**
 * Запрос проверки версии (`CheckRequest` в схеме стенда).
 *
 * `platform` и `currentVersionCode` обязательны, имя версии — нет.
 */
@Serializable
data class VersionCheckRequest(
    @SerialName("platform") val platform: String,
    @SerialName("currentVersionCode") val currentVersionCode: Int,
    @SerialName("currentVersionName") val currentVersionName: String? = null,
)

/**
 * Ответ проверки (`VersionCheckResponse`).
 *
 * Все поля nullable, и это не перестраховка: на стенде с незаполненным
 * реестром версий приходит `{"updateAvailable":false,"updateRequired":false,
 * "policy":null, …}` — то есть даже флаги могут отсутствовать. `null` у флага
 * читается как «нет», а не как ошибка разбора: молчание сервера про
 * обновление означает, что обновляться не надо.
 */
@Serializable
data class VersionCheckDto(
    @SerialName("updateAvailable") val updateAvailable: Boolean? = null,
    @SerialName("updateRequired") val updateRequired: Boolean? = null,
    @SerialName("policy") val policy: String? = null,
    @SerialName("latestVersionName") val latestVersionName: String? = null,
    @SerialName("latestVersionCode") val latestVersionCode: Int? = null,
    @SerialName("releaseNotes") val releaseNotes: String? = null,
    @SerialName("storeUrl") val storeUrl: String? = null,
    @SerialName("remainingSkips") val remainingSkips: Int? = null,
    @SerialName("versionId") val versionId: String? = null,
)

@Serializable
data class SkipVersionRequest(
    @SerialName("versionId") val versionId: String,
)

/**
 * Версия приложения (контроллер `app-version-controller`, issue #80).
 *
 * Проверено на стенде:
 * - `check` **анонимный** — отвечает `200` и без Bearer, но, как весь
 *   `/api/v1`, требует гео-заголовков (issue #53; их ставит
 *   `GeoHeaderInterceptor` на обоих клиентах). Это и нужно: проверка идёт под
 *   держащимся splash'ем, то есть до входа.
 * - `skip` требует Bearer (`401 UNAUTHORIZED` без него): пропуски бэкенд
 *   считает пользователю.
 *
 * Поэтому API создаётся на **основном** Retrofit — «голый» `@RefreshClient`
 * токен не ставит, и `skip` на нём не работал бы никогда.
 */
interface AppVersionApi {

    @POST("app/version/check")
    suspend fun check(@Body body: VersionCheckRequest): ApiResponse<VersionCheckDto>

    /** Ответ — конверт без полезной нагрузки: `data` пуст и при успехе. */
    @POST("app/version/skip")
    suspend fun skip(@Body body: SkipVersionRequest): ApiResponse<JsonElement>
}
