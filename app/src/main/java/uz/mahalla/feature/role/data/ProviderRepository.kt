package uz.mahalla.feature.role.data

import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.map
import uz.mahalla.data.location.LocationSource
import uz.mahalla.data.location.RequestLocationProvider
import uz.mahalla.data.network.ensureSuccess
import uz.mahalla.data.network.payload
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.onboarding.domain.City
import uz.mahalla.feature.onboarding.domain.PhoneNumberValidator
import uz.mahalla.feature.role.domain.MyPlace
import uz.mahalla.feature.role.domain.MyPlacePage
import uz.mahalla.feature.role.domain.PlaceModerationStatus
import uz.mahalla.feature.role.domain.PlaceStaffRole
import uz.mahalla.feature.role.domain.ProviderForm
import uz.mahalla.feature.role.domain.ProviderFormValidator
import uz.mahalla.feature.role.domain.RegisteredPlace
import uz.mahalla.feature.role.domain.WebsiteLink
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Заявка продавца на регистрацию заведения (issue #84).
 *
 * Кэша нет: заявка отправляется один раз, а её судьбу решает модерация —
 * хранить локально «отправлено» значило бы показывать это и после отказа
 * бэкенда.
 *
 * Интерфейс — ради тестов ViewModel: экран проверяется без MockWebServer.
 */
interface ProviderRepository {

    suspend fun registerPlace(form: ProviderForm): ApiResult<RegisteredPlace>

    /** «Мои заведения» со статусом модерации (issue #94). */
    suspend fun myPlaces(page: Int = 0, size: Int = PAGE_SIZE): ApiResult<MyPlacePage>

    /**
     * Переключить «открыто сейчас».
     *
     * @param current известное приложению состояние. Нужно потому, что ручка —
     * именно переключатель: желаемого значения бэкенд не принимает, а своё
     * новое сообщает в `data`. Молчание сервера о нём читается как «флаг
     * перевернулся» — иначе экран показал бы прежнее состояние после
     * успешного запроса.
     * @return состояние флага после запроса.
     */
    suspend fun toggleAvailability(placeId: String, current: Boolean): ApiResult<Boolean>

    companion object {
        /** Код отказа, когда заявка не прошла проверку ещё на клиенте. */
        const val INVALID_FORM_CODE = "PROVIDER_FORM_INVALID"

        /** Столько же по умолчанию берёт и сам бэкенд. */
        const val PAGE_SIZE = 20
    }
}

@Singleton
class DefaultProviderRepository @Inject constructor(
    private val api: ProviderApi,
    private val locationSource: LocationSource,
    private val requestLocation: RequestLocationProvider,
    private val phoneValidator: PhoneNumberValidator,
) : ProviderRepository {

    /**
     * Координаты заведения: точка, выбранная на карте (issue #90), иначе
     * измеренная позиция устройства, иначе центр города из анкеты, иначе центр
     * Ташкента.
     *
     * Выбранная точка старше позиции устройства, и это главное правило здесь:
     * заявку часто заполняют дома, а заведение стоит в другом месте — и
     * запомнить надо то, что человек показал сам, а не то, где он в этот
     * момент сидит. Остальная лестница — та же, что у запросов авторизации
     * (`RequestLocationProvider`), только город берётся из формы, а не из
     * настроек: человек может регистрировать заведение в другом городе, чем
     * выбрал для каталога.
     *
     * Незаполненная форма в сеть не уходит: 400 от сервера сказал бы то же
     * самое, но платой были бы запрос и молчание экрана на время его
     * выполнения.
     */
    override suspend fun registerPlace(form: ProviderForm): ApiResult<RegisteredPlace> {
        val trimmed = form.trimmed()
        val errors = ProviderFormValidator.validate(trimmed, phoneValidator::isValid)
        if (errors.isNotEmpty()) {
            return ApiResult.Failure(ApiError.Business(ProviderRepository.INVALID_FORM_CODE))
        }

        val city = trimmed.city ?: City.Default
        val picked = trimmed.location
        // Позицию устройства не спрашиваем вовсе, когда точка выбрана: это
        // лишнее обращение к `LocationManager` за ответом, который всё равно
        // проиграет.
        val location = if (picked == null) locationSource.lastKnown() else null
        return apiCall {
            api.createPlace(
                CreatePlaceRequest(
                    name = trimmed.name,
                    category = trimmed.category?.apiValue.orEmpty(),
                    address = trimmed.address,
                    lat = picked?.latitude ?: location?.latitude ?: city.latitude,
                    lng = picked?.longitude ?: location?.longitude ?: city.longitude,
                    phone = phoneValidator.toE164(trimmed.phoneDigits),
                    city = city.id,
                    description = trimmed.description.takeIf(String::isNotEmpty),
                    website = WebsiteLink.sanitize(trimmed.website),
                ),
            ).payload()
        }.map { dto -> dto.toDomain(fallbackName = trimmed.name) }
    }

    /**
     * Кэша нет намеренно: статус модерации меняют на сервере, и «PENDING» из
     * Room после одобрения заявки был бы прямой ложью — ровно тем, ради чего
     * экран и делался.
     */
    override suspend fun myPlaces(page: Int, size: Int): ApiResult<MyPlacePage> =
        apiCall { api.myPlaces(page = page.coerceAtLeast(0), size = size).payload() }
            .map(MyPlacePageDto::toDomain)

    override suspend fun toggleAvailability(
        placeId: String,
        current: Boolean,
    ): ApiResult<Boolean> {
        val location = requestLocation.current()
        return apiCall {
            val response = api.toggleAvailability(
                placeId = placeId,
                body = ToggleAvailabilityRequest(
                    lat = location.latitude,
                    lng = location.longitude,
                ),
            )
            // `ensureSuccess`, а не `payload`: `data` тут `Boolean`, и `false`
            // — законный ответ («заведение закрыли»), который `payload` от
            // отсутствия значения не отличает.
            response.ensureSuccess()
            response.data ?: !current
        }
    }
}

/** См. [MyPlacePage.hasMore] — правило подсчёта живёт там. */
internal fun MyPlacePageDto.toDomain(): MyPlacePage {
    val pageIndex = page ?: 0
    val pages = totalPages
    return MyPlacePage(
        items = content.mapNotNull(MyPlaceDto::toDomain),
        hasMore = when {
            last != null -> !last
            pages != null -> pageIndex + 1 < pages
            else -> false
        },
    )
}

/**
 * Разбор мягкий, как в каталоге (issue #53): запись без `id` отбрасывается —
 * открыть её карточку и переключить ей доступность всё равно нечем, а в
 * `LazyColumn` она стала бы дубликатом ключа.
 *
 * Всё остальное заведение не прячет. Незнакомый статус показывается как есть
 * ([PlaceModerationStatus.Unknown]), незнакомая категория становится
 * `PlaceCategory.Other`, а заведение без имени получает подпись от экрана:
 * пропасть из списка своих заведений оно не должно ни в одном из этих
 * случаев.
 */
internal fun MyPlaceDto.toDomain(): MyPlace? {
    val placeId = id?.takeIf { it.isNotBlank() } ?: return null
    return MyPlace(
        id = placeId,
        name = name?.takeIf { it.isNotBlank() }.orEmpty(),
        category = PlaceCategory.fromApi(category),
        status = PlaceModerationStatus.fromApi(status),
        address = address?.takeIf { it.isNotBlank() },
        // Молчание сервера — «закрыто»: обещать открытое заведение, про
        // которое ничего не известно, хуже.
        isAvailable = isAvailable ?: available ?: false,
        rating = ratingAvg?.coerceAtLeast(0.0) ?: 0.0,
        ratingCount = ratingCount?.coerceAtLeast(0) ?: 0,
        staffRole = PlaceStaffRole.fromApi(role),
    )
}

/**
 * Ответ без `id` — не отказ: заявка принята, и показать подтверждение важнее,
 * чем идентификатор, которым приложению пока нечего делать (бизнес-панель —
 * эпик #16). Имя подставляется из формы: его человек только что ввёл сам.
 */
private fun PlaceDetailDto.toDomain(fallbackName: String): RegisteredPlace = RegisteredPlace(
    id = id.orEmpty(),
    name = name?.takeIf(String::isNotBlank) ?: fallbackName,
    status = PlaceModerationStatus.fromApi(status),
)
