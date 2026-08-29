package uz.mahalla.feature.auth.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import uz.mahalla.core.result.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Установлен ли на устройстве Telegram (issue #46).
 *
 * От ответа зависит весь выбор канала: есть Telegram — вход бесплатный, нет —
 * платное SMS. Поэтому кнопку Telegram нельзя показывать «на всякий случай»:
 * человек, у которого приложения нет, упёрся бы в ссылку, открывшуюся в
 * браузере, где нажать Start невозможно.
 *
 * Интерфейс, а не класс: `PackageManager` в JVM-тестах заглушен, а ViewModel
 * ветвится именно по этому ответу.
 */
interface TelegramAvailability {

    /**
     * Пакет Telegram-клиента, которым надо открывать ссылку, или `null`, если
     * ни одного не нашлось.
     *
     * Возвращается именно пакет, а не `Boolean`: ссылку на бота нужно
     * адресовать конкретному приложению (см. `TelegramLoginScreen`).
     */
    fun installedPackage(): String?
}

@Singleton
class AndroidTelegramAvailability @Inject constructor(
    @ApplicationContext private val context: Context,
) : TelegramAvailability {

    /**
     * Ищем в два захода.
     *
     * Сначала — кто умеет открывать схему `tg:`. Это и есть определение
     * Telegram-клиента, оно не зависит от имён пакетов и переживает форки
     * (Telegram X, Plus, сборки из Google Play и с сайта — у всех разные
     * `applicationId`). Браузеры сюда не попадают: `tg:` они не объявляют.
     *
     * Потом — известные пакеты по именам, на случай прошивки, где резолвер
     * ничего не отдаёт из-за политики видимости.
     */
    override fun installedPackage(): String? = resolveTgScheme() ?: knownPackage()

    private fun resolveTgScheme(): String? = runCatchingCancellable {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(TG_PROBE_URI))
        context.packageManager
            .queryIntentActivities(intent, 0)
            .firstOrNull()
            ?.activityInfo
            ?.packageName
    }.getOrNull()

    private fun knownPackage(): String? = KNOWN_PACKAGES.firstOrNull { isInstalled(it) }

    private fun isInstalled(packageName: String): Boolean = runCatchingCancellable {
        // NameNotFoundException здесь — обычный ответ «не установлено», а не
        // исключительная ситуация. На API 30+ он же приходит, когда пакета нет
        // в <queries> манифеста, — поэтому все четыре там перечислены.
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    private companion object {
        /**
         * Домен подставлен намеренно несуществующий: intent никуда не
         * отправляется, он нужен только резолверу как образец.
         */
        const val TG_PROBE_URI = "tg://resolve?domain=mahalla"

        /** Порядок — от официального клиента к форкам. */
        val KNOWN_PACKAGES = listOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.thunderdog.challegram",
            "org.telegram.plus",
        )
    }
}
