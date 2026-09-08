package uz.mahalla.testutil

import kotlinx.coroutines.CompletableDeferred
import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.media.data.MediaRepository
import uz.mahalla.feature.media.domain.MediaFile

/**
 * Загрузка файлов в тестах экранов (issue #101): MockWebServer здесь лишний —
 * сеть проверяет `MediaRepositoryTest`.
 *
 * @param gate когда задан, ответ не приезжает, пока его не завершат: так
 * проверяется состояние «идёт загрузка» и её отмена.
 */
class FakeMediaRepository(
    var result: ApiResult<MediaFile> = ApiResult.Success(
        MediaFile(id = "m-1", url = "https://cdn.mahalla.uz/m-1.jpg"),
    ),
    var progress: List<Int> = listOf(0, 50, 100),
    var gate: CompletableDeferred<Unit>? = null,
) : MediaRepository {

    data class Upload(val source: String, val entityType: String?, val entityId: String?)

    val uploads = mutableListOf<Upload>()

    override suspend fun uploadImage(
        source: String,
        entityType: String?,
        entityId: String?,
        onProgress: (Int) -> Unit,
    ): ApiResult<MediaFile> {
        uploads += Upload(source, entityType, entityId)
        progress.forEach(onProgress)
        gate?.await()
        return result
    }
}
