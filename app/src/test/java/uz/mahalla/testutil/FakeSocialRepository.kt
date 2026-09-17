package uz.mahalla.testutil

import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.social.data.LikeResult
import uz.mahalla.feature.social.data.SavedPlacesPage
import uz.mahalla.feature.social.data.SocialRepository
import uz.mahalla.feature.social.domain.PlaceComment
import uz.mahalla.feature.social.domain.PlaceCommentPage
import uz.mahalla.feature.social.domain.PlaceSocialStatus

/**
 * Социальные действия без сети (issue #75): ответы задаются полями, вызовы
 * записываются — тесты ViewModel проверяют оптимистичное обновление и откат,
 * а не разбор JSON (для него есть `SocialRepositoryTest` на MockWebServer).
 */
class FakeSocialRepository : SocialRepository {

    var status: ApiResult<PlaceSocialStatus> = ApiResult.Success(PlaceSocialStatus())
    var likeResult: ApiResult<LikeResult> = ApiResult.Success(LikeResult(liked = true, likes = 1))
    var saveResult: ApiResult<Boolean> = ApiResult.Success(true)
    var addCommentResult: ApiResult<PlaceComment> = ApiResult.Failure(ApiError.NoConnection)
    var deleteResult: ApiResult<Unit> = ApiResult.Success(Unit)

    val commentPages: MutableMap<Int, ApiResult<PlaceCommentPage>> = mutableMapOf()
    val savedPages: MutableMap<Int, ApiResult<SavedPlacesPage>> = mutableMapOf()

    val likeCalls: MutableList<String> = mutableListOf()
    val saveCalls: MutableList<String> = mutableListOf()
    val statusCalls: MutableList<String> = mutableListOf()
    val sentComments: MutableList<String> = mutableListOf()
    val deletedComments: MutableList<String> = mutableListOf()
    val requestedSavedPages: MutableList<Int> = mutableListOf()

    override suspend fun status(placeId: String): ApiResult<PlaceSocialStatus> {
        statusCalls += placeId
        return status
    }

    override suspend fun toggleLike(placeId: String): ApiResult<LikeResult> {
        likeCalls += placeId
        return likeResult
    }

    override suspend fun toggleSave(placeId: String): ApiResult<Boolean> {
        saveCalls += placeId
        return saveResult
    }

    override suspend fun comments(placeId: String, page: Int): ApiResult<PlaceCommentPage> =
        commentPages[page] ?: ApiResult.Success(PlaceCommentPage())

    override suspend fun addComment(placeId: String, text: String): ApiResult<PlaceComment> {
        sentComments += text
        return addCommentResult
    }

    override suspend fun deleteComment(commentId: String): ApiResult<Unit> {
        deletedComments += commentId
        return deleteResult
    }

    override suspend fun savedPlaces(page: Int): ApiResult<SavedPlacesPage> {
        requestedSavedPages += page
        return savedPages[page] ?: ApiResult.Success(SavedPlacesPage())
    }
}
