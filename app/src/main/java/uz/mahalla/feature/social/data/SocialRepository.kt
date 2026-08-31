package uz.mahalla.feature.social.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import uz.mahalla.core.format.parseServerInstant
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.map
import uz.mahalla.data.network.ensureSuccess
import uz.mahalla.data.network.payload
import uz.mahalla.data.prefs.UserProfileStore
import uz.mahalla.feature.discovery.data.CatalogRepository
import uz.mahalla.feature.discovery.data.PageDto
import uz.mahalla.feature.discovery.domain.Place
import uz.mahalla.feature.social.domain.CommentRules
import uz.mahalla.feature.social.domain.PlaceComment
import uz.mahalla.feature.social.domain.PlaceCommentPage
import uz.mahalla.feature.social.domain.PlaceSocialStatus
import uz.mahalla.feature.social.domain.SavedPlaceIdsPage
import javax.inject.Inject
import javax.inject.Singleton

/** Страница «Избранного», уже собранная в карточки. */
data class SavedPlacesPage(
    val items: List<Place> = emptyList(),
    val hasMore: Boolean = false,
)

/**
 * Лайк, «Избранное» и комментарии (issue #75).
 *
 * Кэша у социальных ручек нет намеренно: «мой лайк», сохранённый вчера, — это
 * состояние аккаунта, а не витрина. Показать чужое или устаревшее значение
 * кнопки хуже, чем показать ошибку.
 *
 * Интерфейс — ради тестов ViewModel: экраны проверяются без MockWebServer.
 */
interface SocialRepository {

    suspend fun status(placeId: String): ApiResult<PlaceSocialStatus>

    /** Переключатель: сервер возвращает новое состояние и счётчик. */
    suspend fun toggleLike(placeId: String): ApiResult<LikeResult>

    /** Переключатель: в ответе — новое состояние «в избранном». */
    suspend fun toggleSave(placeId: String): ApiResult<Boolean>

    suspend fun comments(placeId: String, page: Int = 0): ApiResult<PlaceCommentPage>

    suspend fun addComment(placeId: String, text: String): ApiResult<PlaceComment>

    suspend fun deleteComment(commentId: String): ApiResult<Unit>

    suspend fun savedPlaces(page: Int = 0): ApiResult<SavedPlacesPage>
}

/**
 * Ответ ручки лайка. Счётчик может не приехать — тогда его считает экран
 * (`PlaceSocialStatus.withLike`), а не подставляет ноль.
 */
data class LikeResult(val liked: Boolean, val likes: Long?)

@Singleton
class DefaultSocialRepository @Inject constructor(
    private val api: SocialApi,
    private val catalogRepository: CatalogRepository,
    private val profileStore: UserProfileStore,
) : SocialRepository {

    override suspend fun status(placeId: String): ApiResult<PlaceSocialStatus> =
        apiCall { api.status(placeId).payload() }
            .map { PlaceSocialStatus.of(it.liked, it.saved, it.totalLikes) }

    override suspend fun toggleLike(placeId: String): ApiResult<LikeResult> =
        apiCall { api.like(placeId).payload() }
            .map { LikeResult(liked = it.liked, likes = it.totalLikes) }

    override suspend fun toggleSave(placeId: String): ApiResult<Boolean> =
        apiCall { api.save(placeId).payload() }

    override suspend fun comments(placeId: String, page: Int): ApiResult<PlaceCommentPage> {
        val userId = currentUserId()
        return apiCall { api.comments(placeId, page = page.coerceAtLeast(0)).payload() }
            .map { it.toDomain(userId) }
    }

    override suspend fun addComment(placeId: String, text: String): ApiResult<PlaceComment> {
        val userId = currentUserId()
        return apiCall {
            api.addComment(placeId, AddCommentRequest(CommentRules.normalize(text))).payload()
        }.map {
            // Свой комментарий помечаем своим даже если бэкенд не вернул
            // `userId`: только что отправленную запись человек обязан узнать.
            it.toDomain(userId)?.copy(isMine = true) ?: it.fallback(text, userId)
        }
    }

    override suspend fun deleteComment(commentId: String): ApiResult<Unit> =
        apiCall { api.deleteComment(commentId).ensureSuccess() }

    /**
     * «Избранное» собирается N+1 запросом: `GET saved-places` отдаёт только
     * идентификаторы (`PageResponseUUID`), карточек в контракте нет вовсе.
     * Это долг бэкенда (вопрос заведён в `jack5505/mahalla`), а не выбор
     * клиента — до его возврата список приходится дособирать здесь.
     *
     * Чтобы страница не превращалась в двадцать одновременных соединений,
     * запросы идут пачками по [MAX_PARALLEL_PLACES]; порядок сохраняется —
     * его задаёт сервер, и «недавно сохранённое» должно оставаться сверху.
     * Место, которое не доехало ни из сети, ни из кэша, из списка выпадает:
     * карточка без названия ничем не лучше её отсутствия. Но если не доехало
     * **ни одно**, это отказ, а не пустое «Избранное» — иначе экран соврал бы,
     * что список пуст.
     */
    override suspend fun savedPlaces(page: Int): ApiResult<SavedPlacesPage> {
        val ids = apiCall { api.savedPlaces(page = page.coerceAtLeast(0)).payload() }
            .map(PageDto<String>::toIdsPage)
        if (ids is ApiResult.Failure) return ids
        val idsPage = (ids as ApiResult.Success).data
        if (idsPage.ids.isEmpty()) {
            return ApiResult.Success(SavedPlacesPage(hasMore = idsPage.hasMore))
        }

        val cards = coroutineScope {
            val limit = Semaphore(MAX_PARALLEL_PLACES)
            idsPage.ids
                .map { id -> async { limit.withPermit { catalogRepository.placeCard(id) } } }
                .awaitAll()
        }

        val places = cards.filterIsInstance<ApiResult.Success<Place>>().map { it.data }
        if (places.isEmpty()) {
            return cards.filterIsInstance<ApiResult.Failure>().first()
        }
        return ApiResult.Success(SavedPlacesPage(items = places, hasMore = idsPage.hasMore))
    }

    /** Профиль хранится локально (issue #61): id вошедшего берётся из него. */
    private suspend fun currentUserId(): String? =
        profileStore.current().id?.takeIf { it.isNotBlank() }

    private companion object {
        /**
         * Больше четырёх одновременных запросов карточек смысла не имеют:
         * пул OkHttp по умолчанию держит пять соединений на хост, а очередь
         * из двадцати только отняла бы их у остальных экранов.
         */
        const val MAX_PARALLEL_PLACES = 4
    }
}

/**
 * Разбор мягкий, как в каталоге (issue #53): комментарий без `id`
 * отбрасывается — в `LazyColumn` он стал бы дубликатом ключа, а удалить его
 * всё равно нечем.
 */
internal fun PageDto<CommentDto>.toDomain(currentUserId: String?): PlaceCommentPage =
    PlaceCommentPage(
        items = content.mapNotNull { it.toDomain(currentUserId) },
        hasMore = !last || page + 1 < totalPages,
    )

internal fun CommentDto.toDomain(currentUserId: String?): PlaceComment? {
    val commentId = id?.takeIf { it.isNotBlank() } ?: return null
    val author = userId?.takeIf { it.isNotBlank() }
    return PlaceComment(
        id = commentId,
        authorId = author,
        text = text,
        createdAt = parseServerInstant(createdAt),
        // Имени автора контракт не отдаёт, поэтому «свой» определяется только
        // сравнением с id вошедшего: удалять бэкенд разрешает лишь свои.
        isMine = author != null && author == currentUserId,
    )
}

/**
 * Ответ на отправку без `id`: комментарий сервер принял, но сослаться на него
 * нечем. Показываем его как есть — потерять только что написанный текст хуже,
 * чем показать запись, которую нельзя удалить до перезагрузки списка.
 */
private fun CommentDto.fallback(text: String, currentUserId: String?): PlaceComment = PlaceComment(
    id = LOCAL_COMMENT_ID_PREFIX + (createdAt ?: text.hashCode().toString()),
    authorId = userId?.takeIf { it.isNotBlank() } ?: currentUserId,
    text = this.text.ifBlank { CommentRules.normalize(text) },
    createdAt = parseServerInstant(createdAt),
    isMine = true,
)

internal fun PageDto<String>.toIdsPage(): SavedPlaceIdsPage = SavedPlaceIdsPage(
    ids = content.filter { it.isNotBlank() }.distinct(),
    hasMore = !last || page + 1 < totalPages,
)

/** Локальный id виден только приложению — на сервер он никогда не уходит. */
private const val LOCAL_COMMENT_ID_PREFIX = "local:"
