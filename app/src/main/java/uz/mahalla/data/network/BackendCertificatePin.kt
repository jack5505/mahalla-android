package uz.mahalla.data.network

import uz.mahalla.data.network.tls.CertificatePinSource
import uz.mahalla.data.prefs.SettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Сертификат сервера, которому доверился пользователь (issue #32).
 *
 * Устроен как [BackendUrlStore] и по той же причине: отпечаток читается на
 * потоке OkHttp во время handshake, где ждать DataStore нельзя. Значение
 * поднимается один раз на старте ([hydrate], под держащимся splash'ем — см.
 * `RootViewModel`) и дальше меняется только записью с экрана адреса.
 *
 * @param overrideEnabled то же право, что и на смену адреса
 * (`BuildConfig.BACKEND_URL_OVERRIDE`). Гейт обязателен: доверять чужому
 * сертификату имеет смысл только там, где можно и сервер указать свой, а пин
 * из debug-установки не должен переезжать в релиз через бэкап настроек.
 */
@Singleton
class BackendCertificatePin @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    @BackendUrlOverride private val overrideEnabled: Boolean = true,
) : CertificatePinSource {

    @Volatile
    private var cached: String? = null

    override fun pinnedFingerprint(): String? = cached?.takeIf { overrideEnabled }

    /** Читает подтверждённый отпечаток в кэш. Вызывать до первого запроса. */
    suspend fun hydrate() {
        if (!overrideEnabled) return
        cached = settingsDataStore.current().backendCertificatePin
    }

    /**
     * Применяет и сохраняет доверие.
     *
     * Кэш обновляется до записи: пин нужен уже следующему запросу, а не
     * следующему запуску. Исключение записи пробрасывается — сообщить о нём
     * дело вызывающего.
     *
     * Пин один: подтверждение нового сертификата заменяет прежний. Отдельный
     * список не нужен — приложение разговаривает с одним бэкендом.
     */
    suspend fun save(fingerprint: String) {
        if (!overrideEnabled) return
        cached = fingerprint
        settingsDataStore.setBackendCertificatePin(fingerprint)
    }
}
