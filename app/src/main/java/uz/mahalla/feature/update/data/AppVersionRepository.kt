package uz.mahalla.feature.update.data

import uz.mahalla.BuildConfig
import uz.mahalla.core.result.ApiResult
import uz.mahalla.core.result.apiCall
import uz.mahalla.core.result.map
import uz.mahalla.data.device.DeviceDescriptor
import uz.mahalla.data.network.ensureSuccess
import uz.mahalla.data.network.payload
import uz.mahalla.feature.update.domain.AppUpdate
import uz.mahalla.feature.update.domain.StoreLink
import uz.mahalla.feature.update.domain.UpdateDecision
import uz.mahalla.feature.update.domain.UpdatePolicy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Проверка версии приложения (issue #80).
 *
 * Интерфейс — ради тестов гейта и ViewModel: они проверяются без MockWebServer.
 */
interface AppVersionRepository {

    suspend fun check(): ApiResult<UpdateDecision>

    /**
     * Отложить версию. Бэкенд считает пропуски пользователю, поэтому до входа
     * запрос отвечает `401` — результат в этом случае просто игнорируется, см.
     * [uz.mahalla.feature.update.data.AppUpdateGate.skip].
     */
    suspend fun skip(versionId: String): ApiResult<Unit>
}

@Singleton
class DefaultAppVersionRepository @Inject constructor(
    private val api: AppVersionApi,
) : AppVersionRepository {

    override suspend fun check(): ApiResult<UpdateDecision> = apiCall {
        api.check(
            VersionCheckRequest(
                platform = DeviceDescriptor.PLATFORM_ANDROID,
                currentVersionCode = BuildConfig.VERSION_CODE,
                currentVersionName = BuildConfig.VERSION_NAME,
            ),
        ).payload()
    }.map { dto -> dto.toDecision(BuildConfig.APPLICATION_ID) }

    override suspend fun skip(versionId: String): ApiResult<Unit> =
        apiCall { api.skip(SkipVersionRequest(versionId = versionId)).ensureSuccess() }
}

/**
 * Разбор мягкий, как в каталоге: пустые строки не доезжают до экрана (иначе
 * блок «что нового» рисовался бы пустой рамкой), а негодная ссылка на магазин
 * заменяется карточкой собственного пакета — блокирующий экран без единой
 * кнопки был бы тупиком.
 *
 * @param packageName имя пакета сборки, не из ответа сервера.
 */
internal fun VersionCheckDto.toDecision(packageName: String): UpdateDecision {
    val update = AppUpdate(
        versionId = versionId?.takeIf { it.isNotBlank() },
        versionName = latestVersionName?.takeIf { it.isNotBlank() },
        versionCode = latestVersionCode,
        releaseNotes = releaseNotes?.takeIf { it.isNotBlank() },
        storeUrl = StoreLink.sanitize(storeUrl) ?: StoreLink.playStore(packageName),
        remainingSkips = remainingSkips,
        policy = UpdatePolicy.fromServer(policy),
    )
    return UpdateDecision.of(
        updateRequired = updateRequired == true,
        updateAvailable = updateAvailable == true,
        update = update,
    )
}
