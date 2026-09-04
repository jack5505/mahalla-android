package uz.mahalla.feature.role.data

import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.map
import uz.mahalla.data.location.LocationSource
import uz.mahalla.data.network.payload
import uz.mahalla.feature.onboarding.domain.City
import uz.mahalla.feature.onboarding.domain.PhoneNumberValidator
import uz.mahalla.feature.role.domain.PlaceModerationStatus
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

    companion object {
        /** Код отказа, когда заявка не прошла проверку ещё на клиенте. */
        const val INVALID_FORM_CODE = "PROVIDER_FORM_INVALID"
    }
}

@Singleton
class DefaultProviderRepository @Inject constructor(
    private val api: ProviderApi,
    private val locationSource: LocationSource,
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
