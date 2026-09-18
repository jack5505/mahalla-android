package uz.mahalla.feature.role.data

import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.map
import uz.mahalla.data.network.ensureSuccess
import uz.mahalla.data.network.payload
import uz.mahalla.feature.role.domain.PlaceStaffMember
import uz.mahalla.feature.role.domain.PlaceStaffRole
import javax.inject.Inject
import javax.inject.Singleton

/** Сотрудники заведения (issue #189). Кэша нет: список короткий и правит его сам экран. */
interface PlaceStaffRepository {

    suspend fun list(placeId: String): ApiResult<List<PlaceStaffMember>>

    suspend fun add(placeId: String, userId: String, role: PlaceStaffRole): ApiResult<PlaceStaffMember>

    suspend fun changeRole(
        placeId: String,
        userId: String,
        role: PlaceStaffRole,
    ): ApiResult<PlaceStaffMember>

    suspend fun remove(placeId: String, userId: String): ApiResult<Unit>
}

@Singleton
class DefaultPlaceStaffRepository @Inject constructor(
    private val api: PlaceStaffApi,
) : PlaceStaffRepository {

    /**
     * Мягкий разбор, как у «моих заведений»: запись без `userId` отбрасывается
     * — действовать над ней (сменить роль, удалить) всё равно нечем, а в
     * `LazyColumn` она стала бы дубликатом ключа.
     */
    override suspend fun list(placeId: String): ApiResult<List<PlaceStaffMember>> =
        apiCall { api.list(placeId).payload() }
            .map { dtos -> dtos.mapNotNull(PlaceStaffDto::toDomainOrNull) }

    /**
     * Ответ на `POST` может промолчать о части полей (все они необязательны
     * по схеме) — подставляем то, что только что отправили сами, тем же
     * способом, что и регистрация заведения (`PlaceDetailDto.toDomain`).
     */
    override suspend fun add(
        placeId: String,
        userId: String,
        role: PlaceStaffRole,
    ): ApiResult<PlaceStaffMember> = apiCall {
        api.add(placeId, AddPlaceStaffRequest(userId = userId, role = role.apiValue)).payload()
    }.map { dto -> dto.toDomain(fallbackUserId = userId, fallbackRole = role) }

    override suspend fun changeRole(
        placeId: String,
        userId: String,
        role: PlaceStaffRole,
    ): ApiResult<PlaceStaffMember> = apiCall {
        api.changeRole(placeId, userId, ChangePlaceStaffRoleRequest(role = role.apiValue)).payload()
    }.map { dto -> dto.toDomain(fallbackUserId = userId, fallbackRole = role) }

    override suspend fun remove(placeId: String, userId: String): ApiResult<Unit> =
        apiCall { api.remove(placeId, userId).ensureSuccess() }
}

private fun PlaceStaffDto.toDomainOrNull(): PlaceStaffMember? {
    val resolvedUserId = userId?.takeIf { it.isNotBlank() } ?: return null
    return PlaceStaffMember(
        userId = resolvedUserId,
        role = PlaceStaffRole.fromApi(role),
        geoExempt = geoExempt ?: false,
    )
}

private fun PlaceStaffDto.toDomain(fallbackUserId: String, fallbackRole: PlaceStaffRole) = PlaceStaffMember(
    userId = userId?.takeIf { it.isNotBlank() } ?: fallbackUserId,
    role = role?.let(PlaceStaffRole::fromApi) ?: fallbackRole,
    geoExempt = geoExempt ?: false,
)
