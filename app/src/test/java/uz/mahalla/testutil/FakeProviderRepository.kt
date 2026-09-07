package uz.mahalla.testutil

import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.role.data.ProviderRepository
import uz.mahalla.feature.role.domain.MyPlacePage
import uz.mahalla.feature.role.domain.PlaceModerationStatus
import uz.mahalla.feature.role.domain.ProviderForm
import uz.mahalla.feature.role.domain.RegisteredPlace

/**
 * Заявка продавца и его заведения в памяти (issue #84, #94): экраны
 * проверяются без MockWebServer.
 *
 * Ответ на каждую страницу задаётся отдельно — иначе догрузку не отличить от
 * повторной загрузки первой страницы.
 */
class FakeProviderRepository : ProviderRepository {

    var result: ApiResult<RegisteredPlace> = ApiResult.Success(
        RegisteredPlace(id = "p-1", name = "Osh Markazi", status = PlaceModerationStatus.Pending),
    )

    val submitted = mutableListOf<ProviderForm>()

    val pages: MutableMap<Int, ApiResult<MyPlacePage>> = mutableMapOf()

    var defaultPage: ApiResult<MyPlacePage> = ApiResult.Success(MyPlacePage())

    val requestedPages = mutableListOf<Int>()

    /** Исход переключения доступности; `null` — вернуть перевёрнутое `current`. */
    var toggleResult: ApiResult<Boolean>? = null

    /** Что именно ушло в `toggleAvailability`: id заведения и известное состояние. */
    val toggled = mutableListOf<Pair<String, Boolean>>()

    override suspend fun registerPlace(form: ProviderForm): ApiResult<RegisteredPlace> {
        submitted += form
        return result
    }

    override suspend fun myPlaces(page: Int, size: Int): ApiResult<MyPlacePage> {
        requestedPages += page
        return pages[page] ?: defaultPage
    }

    override suspend fun toggleAvailability(
        placeId: String,
        current: Boolean,
    ): ApiResult<Boolean> {
        toggled += placeId to current
        return toggleResult ?: ApiResult.Success(!current)
    }
}
