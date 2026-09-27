package uz.mahalla.feature.discovery.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.data.db.dao.PlaceCategoryDao
import uz.mahalla.data.db.entity.PlaceCategoryEntity
import uz.mahalla.data.network.payload
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.discovery.domain.PlaceCategoryCatalog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Включённые категории каталога (issue #378): плитки главной, чипы фильтра в
 * поиске и выбор категории в анкете продавца.
 *
 * Источник — `GET categories` (jack5505/mahalla#340), между запусками список
 * живёт в Room. Интерфейс нужен ради тестов ViewModel: фейк подставляется без
 * базы и без MockWebServer.
 */
interface CategoryRepository {

    /**
     * Категории в порядке `sortOrder` сервера. Пока кэш пуст (первый запуск
     * без сети) — прежний зашитый набор `PlaceCategory.selectable`, чтобы
     * главная не была пустой. Коды, которых в приложении нет, пропущены.
     */
    fun categories(): Flow<List<PlaceCategory>>

    /**
     * То же, но молчит, пока с сервера не пришёл хотя бы один успешный ответ
     * за время жизни процесса.
     *
     * Нужно там, где по списку не рисуют, а **снимают** уже сделанный выбор:
     * категорию, выключенную в дашборде, надо убрать из применённого фильтра
     * и из анкеты (issue #382), но делать это по кэшу, который мог пролежать
     * неделю, нельзя — сервер вполне может всё ещё отдавать эту категорию, и
     * человек молча потеряет свой выбор.
     *
     * Отметка об успешном обновлении общая на весь процесс: обновила главная —
     * поиску и анкете ждать своего ответа уже незачем.
     */
    fun confirmedCategories(): Flow<List<PlaceCategory>>

    /**
     * Перечитать список с сервера и переписать кэш. Отказ сети кэш не трогает:
     * подписчики [categories] продолжают видеть прошлый список, а
     * [confirmedCategories] так и молчит: неудачное обновление ничего не
     * подтверждает.
     */
    suspend fun refresh(): ApiResult<Unit>
}

/**
 * Кэш перезаписывается целиком на каждом успешном ответе: ручка отдаёт весь
 * список, и выключенная в дашборде категория должна исчезнуть, а не остаться
 * от прошлого ответа. В кэш попадают и неизвестные коды — когда приложение
 * научится новой категории, она появится без повторного запроса.
 *
 * Пустой ответ сервера (в дашборде выключено всё) неотличим от пустого кэша и
 * даёт тот же фолбэк на зашитый набор — это осознанно: пустая главная хуже.
 */
@Singleton
class DefaultCategoryRepository @Inject constructor(
    private val api: CategoriesApi,
    private val dao: PlaceCategoryDao,
) : CategoryRepository {

    /**
     * Был ли за время жизни процесса хотя бы один успешный ответ сервера.
     * Репозиторий — `@Singleton`, поэтому отметка общая на все экраны.
     */
    private val confirmed = MutableStateFlow(false)

    override fun categories(): Flow<List<PlaceCategory>> = dao.observeAll()
        .map { rows -> PlaceCategoryCatalog.resolve(rows.map(PlaceCategoryEntity::code)) }
        .distinctUntilChanged()

    override fun confirmedCategories(): Flow<List<PlaceCategory>> =
        combine(confirmed, categories()) { ok, list -> list.takeIf { ok } }
            .filterNotNull()

    override suspend fun refresh(): ApiResult<Unit> {
        val result = apiCall { api.categories().payload() }
        return when (result) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> store(result.data.mapNotNull(CategoryDto::toEntity))
                .also { stored -> if (stored is ApiResult.Success) confirmed.value = true }
        }
    }

    /**
     * Запись в кэш — фоновая работа главной, и отказ базы (`SQLiteFullException`
     * и подобное) обязан остаться отказом обновления, а не исключением, которое
     * отменит загрузку каталога. Отмена корутины пробрасывается как есть.
     */
    private suspend fun store(items: List<PlaceCategoryEntity>): ApiResult<Unit> =
        try {
            dao.replaceAll(items)
            ApiResult.Success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            ApiResult.Failure(ApiError.Unexpected(failure))
        }
}
