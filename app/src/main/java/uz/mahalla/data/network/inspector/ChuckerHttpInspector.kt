package uz.mahalla.data.network.inspector

import android.content.Context
import android.content.Intent
import com.chuckerteam.chucker.api.Chucker
import com.chuckerteam.chucker.api.ChuckerCollector
import com.chuckerteam.chucker.api.ChuckerInterceptor
import com.chuckerteam.chucker.api.RetentionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Interceptor
import uz.mahalla.data.network.AuthInterceptor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Инспектор трафика на Chucker (issue #30).
 *
 * `Chucker.isOp` — единственный честный признак, что в сборку приехала
 * настоящая библиотека, а не no-op: проверять `BuildConfig.DEBUG` значило бы
 * дублировать решение, уже принятое в `app/build.gradle.kts`, и разъезжаться
 * с ним при появлении новых buildType'ов.
 *
 * Интерцептор создаётся лениво: Chucker поднимает Room-базу транзакций, а
 * граф собирается на старте приложения под держащимся splash'ем.
 */
@Singleton
class ChuckerHttpInspector @Inject constructor(
    @ApplicationContext private val context: Context,
) : HttpInspector {

    override val isAvailable: Boolean = Chucker.isOp

    private val chuckerInterceptor: Interceptor? by lazy {
        if (!isAvailable) {
            null
        } else {
            ChuckerInterceptor.Builder(context)
                .collector(
                    ChuckerCollector(
                        context = context,
                        showNotification = true,
                        // Сутки: тестировщик открывает список не в ту же
                        // секунду, а хранить трафик неделями незачем.
                        retentionPeriod = RetentionManager.Period.ONE_DAY,
                    ),
                )
                .maxContentLength(MAX_CONTENT_LENGTH)
                // Токены не должны лежать в открытом виде в базе Chucker'а:
                // тот же список заголовков уже редактируется в logcat.
                .redactHeaders(
                    AuthInterceptor.HEADER_AUTHORIZATION,
                    HEADER_COOKIE,
                    HEADER_SET_COOKIE,
                )
                // Иначе тело ответа, которое приложение не дочитало (ошибка
                // парсинга, отмена), в инспекторе оказывается пустым — а это
                // ровно тот случай, ради которого в него и заглядывают.
                .alwaysReadResponseBody(true)
                .build()
        }
    }

    override val interceptor: Interceptor?
        get() = chuckerInterceptor

    override fun launchIntent(): Intent? =
        if (isAvailable) Chucker.getLaunchIntent(context) else null

    private companion object {
        /** 1 МБ: списки каталога с картинками в base64 иначе режутся. */
        const val MAX_CONTENT_LENGTH = 1_000_000L
        const val HEADER_COOKIE = "Cookie"
        const val HEADER_SET_COOKIE = "Set-Cookie"
    }
}
