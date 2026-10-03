package uz.mahalla.testutil

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
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

    /**
     * Снимок последнего успешного ответа — ровно как в настоящем репозитории:
     * значение из самого ответа, а не производная от [categories], чтобы фейк
     * не прятал гонку между отметкой и переэмиссией кэша.
     */
    private val confirmed = MutableStateFlow<List<PlaceCategory>?>(null)

    override fun categories(): Flow<List<PlaceCategory>> = categories

    override fun confirmedCategories(): Flow<List<PlaceCategory>> = confirmed.filterNotNull()

    override suspend fun refresh(): ApiResult<Unit> {
        refreshCount++
        refreshGate?.await()
        if (refreshResult is ApiResult.Success) confirmed.value = categories.value
        return refreshResult
    }
}
