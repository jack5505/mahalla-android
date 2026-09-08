package uz.mahalla.testutil

import kotlinx.coroutines.delay
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.update.data.AppVersionRepository
import uz.mahalla.feature.update.domain.UpdateDecision

/**
 * Проверка версии (issue #80) без сети: гейт и ViewModel проверяются на JVM,
 * сам контракт закрывает `AppVersionRepositoryTest` на MockWebServer.
 *
 * @param answer чем отвечает `check`.
 * @param delayMillis сколько сервер «думает» — тем и проверяется бюджет
 * времени под splash'ем.
 */
class FakeAppVersionRepository(
    var answer: ApiResult<UpdateDecision> = ApiResult.Success(UpdateDecision.None),
    var delayMillis: Long = 0,
    var skipAnswer: ApiResult<Unit> = ApiResult.Success(Unit),
) : AppVersionRepository {

    var checkCount = 0
        private set

    /** Версии, для которых просили пропуск, по порядку. */
    val skipped = mutableListOf<String>()

    override suspend fun check(): ApiResult<UpdateDecision> {
        checkCount++
        if (delayMillis > 0) delay(delayMillis)
        return answer
    }

    override suspend fun skip(versionId: String): ApiResult<Unit> {
        skipped += versionId
        if (delayMillis > 0) delay(delayMillis)
        return skipAnswer
    }

    companion object {
        fun failing(error: ApiError = ApiError.NoConnection) =
            FakeAppVersionRepository(answer = ApiResult.Failure(error))
    }
}
