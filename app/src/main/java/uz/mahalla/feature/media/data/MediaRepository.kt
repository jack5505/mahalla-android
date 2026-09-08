package uz.mahalla.feature.media.data

import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.data.network.payload
import uz.mahalla.feature.media.domain.MediaFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Загрузка файла на бэкенд (issue #101).
 *
 * Кэша нет и быть не может: файл уходит один раз, а ответ — это адрес на
 * сервере, который хранит уже он.
 *
 * **Отмена** — обычная отмена корутины: Retrofit обрывает вызов, OkHttp
 * закрывает сокет, недописанное тело до сервера не доезжает. Поэтому
 * отдельного `cancel()` в интерфейсе нет — экран отменяет свой job.
 *
 * Интерфейс — ради тестов ViewModel: экран проверяется без MockWebServer.
 */
interface MediaRepository {

    /**
     * Сжать выбранный снимок и отправить его.
     *
     * @param source `content://`-адрес от photo picker.
     * @param entityType к чему относится файл (`REVIEW`, `PLACE`, …).
     * Словаря значений в схеме нет, поэтому по умолчанию не отправляется
     * вовсе: выдуманное значение бэкенд запомнит, и разбирать это придётся
     * потом руками.
     * @param onProgress доля отправленного, 0..100. **Зовётся с потока
     * OkHttp**, а не с того, на котором начали, — состояние из него можно
     * менять только потокобезопасно (`MutableStateFlow.update`, как в
     * `MviViewModel`).
     */
    suspend fun uploadImage(
        source: String,
        entityType: String? = null,
        entityId: String? = null,
        onProgress: (percent: Int) -> Unit = {},
    ): ApiResult<MediaFile>

    companion object {
        /** Имя части в `multipart/form-data` — из схемы `media/upload`. */
        const val PART_NAME = "file"

        const val PROGRESS_COMPLETE = 100
    }
}

@Singleton
class DefaultMediaRepository @Inject constructor(
    private val api: MediaApi,
    private val compressor: ImageCompressor,
) : MediaRepository {

    override suspend fun uploadImage(
        source: String,
        entityType: String?,
        entityId: String?,
        onProgress: (Int) -> Unit,
    ): ApiResult<MediaFile> {
        // Сжатие идёт до сети и **до** любого запроса: файл, который всё равно
        // упрётся в лимит nginx, не должен занимать связь, а причина отказа
        // человеку понятна сразу (см. MediaUploadLimits).
        val image = when (val compressed = compressor.compress(source)) {
            is CompressionResult.Rejected ->
                return ApiResult.Failure(ApiError.Business(compressed.reason.code))

            is CompressionResult.Success -> compressed.image
        }

        return apiCall {
            val body = ProgressRequestBody(
                bytes = image.bytes,
                contentType = image.mimeType.toMediaTypeOrNull(),
                onProgress = percentReporter(onProgress),
            )
            val part = MultipartBody.Part.createFormData(
                MediaRepository.PART_NAME,
                image.fileName,
                body,
            )
            api.upload(part, entityType, entityId)
                .payload()
                .toDomain()
            // Ответ без `url` — это «успех», которым нечего сделать. Наружу он
            // уходит как ошибка разбора: apiCall превращает её в
            // ApiError.Serialization, то есть в честное «не получилось».
                ?: throw SerializationException("media/upload responded without url")
        }
    }

    /**
     * Проценты, а не байты: экрану нужна полоска, а не арифметика. Одно и то
     * же значение подряд не повторяется — иначе на каждый килобайт шла бы
     * перерисовка состояния.
     */
    private fun percentReporter(onProgress: (Int) -> Unit): (Long, Long) -> Unit {
        var last = -1
        return { written, total ->
            val percent = if (total <= 0) {
                MediaRepository.PROGRESS_COMPLETE
            } else {
                (written * MediaRepository.PROGRESS_COMPLETE / total)
                    .toInt()
                    .coerceIn(0, MediaRepository.PROGRESS_COMPLETE)
            }
            if (percent != last) {
                last = percent
                onProgress(percent)
            }
        }
    }
}
