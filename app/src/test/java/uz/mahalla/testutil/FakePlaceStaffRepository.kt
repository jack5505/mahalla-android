package uz.mahalla.testutil

import kotlinx.coroutines.CompletableDeferred
import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.role.data.PlaceStaffRepository
import uz.mahalla.feature.role.domain.PlaceStaffMember
import uz.mahalla.feature.role.domain.PlaceStaffRole

/** Сотрудники заведения в памяти (issue #189): экран проверяется без MockWebServer. */
class FakePlaceStaffRepository : PlaceStaffRepository {

    var listResult: ApiResult<List<PlaceStaffMember>> = ApiResult.Success(emptyList())
    var addResult: ApiResult<PlaceStaffMember>? = null
    var changeRoleResult: ApiResult<PlaceStaffMember>? = null
    var removeResult: ApiResult<Unit> = ApiResult.Success(Unit)

    /** Держит `add` подвешенным, пока тест не отпустит его — проверить, что видно, пока запрос летит. */
    var addGate: CompletableDeferred<Unit>? = null

    val listedPlaceIds = mutableListOf<String>()
    val added = mutableListOf<Triple<String, String, PlaceStaffRole>>()
    val changedRoles = mutableListOf<Triple<String, String, PlaceStaffRole>>()
    val removed = mutableListOf<Pair<String, String>>()

    override suspend fun list(placeId: String): ApiResult<List<PlaceStaffMember>> {
        listedPlaceIds += placeId
        return listResult
    }

    override suspend fun add(
        placeId: String,
        userId: String,
        role: PlaceStaffRole,
    ): ApiResult<PlaceStaffMember> {
        added += Triple(placeId, userId, role)
        addGate?.await()
        return addResult ?: ApiResult.Success(PlaceStaffMember(userId = userId, role = role))
    }

    override suspend fun changeRole(
        placeId: String,
        userId: String,
        role: PlaceStaffRole,
    ): ApiResult<PlaceStaffMember> {
        changedRoles += Triple(placeId, userId, role)
        return changeRoleResult ?: ApiResult.Success(PlaceStaffMember(userId = userId, role = role))
    }

    override suspend fun remove(placeId: String, userId: String): ApiResult<Unit> {
        removed += placeId to userId
        return removeResult
    }
}
