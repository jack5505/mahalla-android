package uz.mahalla.core.image

import java.net.URI

/**
 * Приведение ссылки на картинку к виду, который умеет загрузить Coil
 * (issue #60).
 *
 * Зачем это вообще нужно. Бэкенд отдаёт `logoUrl`/`coverUrl`/`photoUrl` как
 * придётся: у одних записей это абсолютный `https://…`, у других — путь вроде
 * `/media/entity/42.jpg` или `media/42.jpg` относительно адреса API. Адрес
 * API при этом задаёт пользователь (issue #26), то есть «дописать хост»
 * сборкой нельзя — только тем адресом, на который приложение ходит сейчас.
 *
 * Схемы ограничены `http`/`https`/`data` намеренно. Строку присылает сервер,
 * а адрес сервера в debug вводит пользователь: без белого списка подменённый
 * бэкенд мог бы прислать `content://…` или `file:///…` и показать в списке
 * мест кусок чужого хранилища. Всё остальное — `null`, то есть фоллбэк-иконка.
 */
object ImageUrl {

    private val ALLOWED_SCHEMES = setOf("http", "https", "data")

    /**
     * @param baseUrl адрес бэкенда «как сейчас» ([uz.mahalla.data.network.BackendUrlStore.current]).
     * @param raw значение поля из ответа сервера.
     * @return абсолютная ссылка либо `null`, если грузить нечего.
     */
    fun resolve(baseUrl: String, raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null

        // Протокол-относительная ссылка: схему берём https, а не схему API —
        // http для картинок в release всё равно запрещён (network-security-config).
        if (value.startsWith("//")) return resolve(baseUrl, "https:$value")

        val uri = value.toUriOrNull() ?: return null
        if (uri.isAbsolute) return value.takeIf { uri.scheme.lowercase() in ALLOWED_SCHEMES }

        val base = baseUrl.trim().toUriOrNull()?.takeIf {
            it.isAbsolute && it.scheme.lowercase() in ALLOWED_SCHEMES && it.host != null
        } ?: return null

        return runCatching { base.resolve(uri).toString() }.getOrNull()
    }

    /**
     * Ссылка непригодна для загрузки — незакодированный пробел, битая схема.
     * Падать на этом нельзя: одна кривая запись в каталоге не повод остаться
     * без экрана.
     */
    private fun String.toUriOrNull(): URI? = runCatching { URI(this) }.getOrNull()
}
