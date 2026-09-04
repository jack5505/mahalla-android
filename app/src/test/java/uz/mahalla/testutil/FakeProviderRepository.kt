package uz.mahalla.testutil

import uz.mahalla.core.result.ApiResult
import uz.mahalla.feature.role.data.ProviderRepository
import uz.mahalla.feature.role.domain.PlaceModerationStatus
import uz.mahalla.feature.role.domain.ProviderForm
import uz.mahalla.feature.role.domain.RegisteredPlace

/** Заявка продавца в памяти (issue #84): экран проверяется без MockWebServer. */
class FakeProviderRepository : ProviderRepository {

    var result: ApiResult<RegisteredPlace> = ApiResult.Success(
        RegisteredPlace(id = "p-1", name = "Osh Markazi", status = PlaceModerationStatus.Pending),
    )

    val submitted = mutableListOf<ProviderForm>()

    override suspend fun registerPlace(form: ProviderForm): ApiResult<RegisteredPlace> {
        submitted += form
        return result
    }
}
