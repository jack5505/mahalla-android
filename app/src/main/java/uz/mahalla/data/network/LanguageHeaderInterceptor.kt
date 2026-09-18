package uz.mahalla.data.network

import okhttp3.Interceptor
import okhttp3.Response
import uz.mahalla.core.locale.AppLanguage
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `Accept-Language` в каждом запросе (issue #242).
 *
 * Пробой на стенде (`189-74-96-232.nip.io`) подтверждён: без заголовка
 * `users/me` отвечает `401` на uz («Kirish uchun autentifikatsiya talab
 * qilinadi»), с `Accept-Language: ru` — на ru («Для доступа требуется
 * аутентификация»). Значит `error.message` лечится заголовком, а не полем в
 * `UpdateMeRequest` — писать туда `language` не нужно (`docs/adr/0007`).
 *
 * Значение — не сырой выбор из `SettingsDataStore` (`AppLanguage.SYSTEM` ушёл
 * бы пустой строкой, а бэкенд знает только `uz`/`ru`), а язык, который система
 * уже применила к процессу: [AppLocaleManager][uz.mahalla.core.locale.AppLocaleManager]
 * (API 33+) и [LocaleContextWrapper][uz.mahalla.core.locale.LocaleContextWrapper]
 * (API 26–32) меняют [Locale.getDefault] раньше, чем уходит первый запрос.
 * Если системная локаль — не uz и не ru, `AppLanguage.fromTag` вернёт
 * `SYSTEM`, и заголовок падает на язык ресурсов по умолчанию (`uz`).
 */
@Singleton
class LanguageHeaderInterceptor @Inject constructor() : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(HEADER_ACCEPT_LANGUAGE) != null) return chain.proceed(request)
        return chain.proceed(
            request.newBuilder()
                .header(HEADER_ACCEPT_LANGUAGE, effectiveTag())
                .build(),
        )
    }

    private fun effectiveTag(): String =
        AppLanguage.fromTag(Locale.getDefault().toLanguageTag()).tag ?: DEFAULT_TAG

    companion object {
        const val HEADER_ACCEPT_LANGUAGE = "Accept-Language"
        private const val DEFAULT_TAG = "uz"
    }
}
