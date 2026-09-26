package uz.mahalla.testutil

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.discovery.data.CategoryRepository
import uz.mahalla.feature.discovery.domain.PlaceCategory

/**
 * Категории каталога в памяти (issue #378): главная, поиск и анкета продавца
 * проверяются без Room и MockWebServer. [categories] меняется прямо в тесте —
 * так проверяется, что экран следит за кэшем, а не читает его один раз.
 */
class FakeCategoryRepository(
    initial: List<PlaceCategory> = PlaceCategory.selectable,
) : CategoryRepository {

    val categories = MutableStateFlow(initial)

    var refreshResult: ApiResult<Unit> = ApiResult.Success(Unit)

    /**
     * Гейт для проверки гонки: обновление висит, пока его не открыли. Нужен
     * там, где важно поведение экрана **до** первого ответа сервера — выбор
     * категории по устаревшему кэшу снимать нельзя (issue #382).
     */
    var refreshGate: CompletableDeferred<Unit>? = null

    /** Сколько раз просили обновить кэш с сервера. */
    var refreshCount = 0
        private set

    override fun categories(): Flow<List<PlaceCategory>> = categories

    override suspend fun refresh(): ApiResult<Unit> {
        refreshCount++
        refreshGate?.await()
        return refreshResult
    }
}
