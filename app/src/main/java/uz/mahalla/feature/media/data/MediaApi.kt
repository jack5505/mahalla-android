package uz.mahalla.feature.media.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.MultipartBody
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import uz.mahalla.data.network.ApiResponse
import uz.mahalla.feature.media.domain.MediaFile
import uz.mahalla.feature.media.domain.MediaType

/**
 * Загрузка файлов (issue #101) — **первый `multipart` в приложении**: весь
 * остальной API ходит JSON'ом, и Retrofit собран под него.
 *
 * Контракт снят со стенда (`/v3/api-docs` + curl 2026-09-04):
 *
 * | | |
 * |---|---|
 * | `POST media/upload` | `multipart/form-data`, часть `file`; query `entityType`, `entityId` — **оба необязательны** |
 * | без токена | `401 UNAUTHORIZED`, а без гео-заголовков — `403 GEO_PERMISSION_REQUIRED` |
 * | тело > 1 МиБ | `413` HTML'ом от nginx (см. [uz.mahalla.feature.media.domain.MediaUploadLimits]) |
 *
 * Ручка требует Bearer, поэтому API создаётся на **основном** Retrofit, а не
 * на «голом» `@RefreshClient`. Гео-заголовки ставит `GeoHeaderInterceptor` на
 * обоих клиентах (issue #53) — отдельно о них заботиться не нужно.
 *
 * `GET media/entity/{entityId}` и `DELETE media/{id}` (issue #185) отдают и
 * принимают тот же конверт и ту же схему `MediaFile`, что и `upload`
 * (`docs/API-CONTRACT.md`): отдельного curl'а на стенд под них не потребовалось.
 */
interface MediaApi {

    /**
     * @param entityType к чему относится файл (`REVIEW`, `PLACE`, …). Словаря
     * значений в схеме нет — поле объявлено просто строкой, поэтому вызывающий
     * либо знает значение точно, либо не шлёт его вовсе: `null` Retrofit из
     * query выбрасывает.
     */
    @Multipart
    @POST("media/upload")
    suspend fun upload(
        @Part file: MultipartBody.Part,
        @Query("entityType") entityType: String?,
        @Query("entityId") entityId: String?,
    ): ApiResponse<MediaFileDto>

    /** Галерея сущности (issue #185): всё, что к ней когда-либо загружено. */
    @GET("media/entity/{entityId}")
    suspend fun forEntity(@Path("entityId") entityId: String): ApiResponse<List<MediaFileDto>>

    /**
     * Удаление файла (issue #185). Необратимо, и прав на него из схемы не
     * следует — экран решает, кому предложить кнопку, по [MediaFileDto.ownerId],
     * а на отказ сервера (чужой файл, гонка с чужим удалением) отвечает его
     * текстом, а не молчанием.
     */
    @DELETE("media/{id}")
    suspend fun delete(@Path("id") id: String): ApiResponse<JsonElement>
}

/**
 * `MediaFile` из схемы. Все поля необязательные, кроме проверки на `url` в
 * [toDomain]: конверт один на весь API, и что именно приедет, зависит от
 * настроек хранилища на сервере.
 */
@Serializable
data class MediaFileDto(
    @SerialName("id") val id: String? = null,
    @SerialName("url") val url: String? = null,
    @SerialName("thumbnailUrl") val thumbnailUrl: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("fileSize") val fileSize: Long? = null,
    @SerialName("originalName") val originalName: String? = null,
    @SerialName("entityId") val entityId: String? = null,
    @SerialName("entityType") val entityType: String? = null,
    @SerialName("ownerId") val ownerId: String? = null,
    @SerialName("isPublic") val isPublic: Boolean? = null,
)

/**
 * Ответ без `url` — негодный: файл, адреса которого никто не знает, показать
 * и сохранить нечем, и «успешная» загрузка без него была бы обещанием, за
 * которым ничего нет. Остальные поля отсутствовать могут.
 */
internal fun MediaFileDto.toDomain(): MediaFile? {
    val link = url?.takeIf { it.isNotBlank() } ?: return null
    return MediaFile(
        id = id.orEmpty(),
        url = link,
        thumbnailUrl = thumbnailUrl?.takeIf { it.isNotBlank() },
        type = MediaType.fromApi(type),
        sizeBytes = fileSize?.coerceAtLeast(0) ?: 0,
        originalName = originalName?.takeIf { it.isNotBlank() },
        ownerId = ownerId?.takeIf { it.isNotBlank() },
    )
}
