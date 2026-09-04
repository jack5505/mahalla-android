package uz.mahalla.feature.role.domain

import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.map.domain.MapPoint
import uz.mahalla.feature.onboarding.domain.City

/**
 * Анкета продавца (issue #84): заявка на регистрацию заведения, которое
 * оказывает услуги.
 *
 * Уходит в `POST /api/v1/places` и попадает на модерацию — бэкенд отвечает
 * карточкой со статусом `PENDING` (см. [PlaceModerationStatus]). Поэтому
 * форма — это именно заявка, а не «создание места»: сразу после отправки
 * заведение в каталоге не появится.
 *
 * Телефон хранится национальными цифрами (без `+998`), как на экране ввода
 * номера: форматированием занимается поле кита, а домен работает с цифрами.
 *
 * @param location точка, выбранная на карте (issue #90). Необязательна:
 * заведение регистрируют и не глядя на карту, и тогда координаты берутся от
 * устройства или по городу — но выбранная точка всегда важнее их обоих.
 */
data class ProviderForm(
    val name: String = "",
    val category: PlaceCategory? = null,
    val description: String = "",
    val address: String = "",
    val city: City? = null,
    val phoneDigits: String = "",
    val website: String = "",
    val location: MapPoint? = null,
) {

    fun trimmed(): ProviderForm = copy(
        name = name.trim(),
        description = description.trim(),
        address = address.trim(),
        website = website.trim(),
    )

    companion object {
        const val MAX_NAME_LENGTH = 200
        const val MAX_DESCRIPTION_LENGTH = 2000
        const val MAX_ADDRESS_LENGTH = 500

        /** Короче двух букв название не бывает, а «.» отправлять на модерацию незачем. */
        const val MIN_NAME_LENGTH = 2
    }
}

/** Что не так с анкетой продавца. Каждая ошибка привязана к своему полю. */
sealed interface ProviderFormError {
    data object NameRequired : ProviderFormError
    data class NameTooShort(val min: Int) : ProviderFormError
    data class NameTooLong(val max: Int) : ProviderFormError
    data object CategoryRequired : ProviderFormError
    data object CityRequired : ProviderFormError
    data object AddressRequired : ProviderFormError
    data class AddressTooLong(val max: Int) : ProviderFormError
    data object PhoneInvalid : ProviderFormError
    data class DescriptionTooLong(val max: Int) : ProviderFormError
    data object WebsiteInvalid : ProviderFormError
}

/**
 * Проверка анкеты продавца — до сети.
 *
 * Отправлять заведомо неполную заявку незачем: бэкенд ответит тем же, но
 * платой будут запрос, спиннер и сообщение на чужом языке. Ошибки
 * возвращаются все сразу — форма длинная, и показывать замечания по одному
 * значит гонять человека по экрану.
 *
 * Телефон обязателен: по нему в заведение звонят из карточки места, и без
 * него заявка бессмысленна. Сайт необязателен, но заполненный — проверяется:
 * ссылку показывают людям, и «www» без домена там ничего не откроет.
 */
object ProviderFormValidator {

    /**
     * @param isPhoneValid проверку номера делает `PhoneNumberValidator` —
     * он живёт в онбординге и знает коды операторов Узбекистана. Передаётся
     * параметром, чтобы домен продавца не тянул за собой инъекцию.
     */
    fun validate(form: ProviderForm, isPhoneValid: (String) -> Boolean): List<ProviderFormError> {
        val trimmed = form.trimmed()
        return buildList {
            when {
                trimmed.name.isEmpty() -> add(ProviderFormError.NameRequired)
                trimmed.name.length < ProviderForm.MIN_NAME_LENGTH ->
                    add(ProviderFormError.NameTooShort(ProviderForm.MIN_NAME_LENGTH))

                trimmed.name.length > ProviderForm.MAX_NAME_LENGTH ->
                    add(ProviderFormError.NameTooLong(ProviderForm.MAX_NAME_LENGTH))
            }

            // `Other` — не категория, а место для значений, которых ещё нет в
            // приложении: отправить её на сервер нечем (пустой `apiValue`).
            if (trimmed.category == null || trimmed.category == PlaceCategory.Other) {
                add(ProviderFormError.CategoryRequired)
            }
            if (trimmed.city == null) add(ProviderFormError.CityRequired)

            when {
                trimmed.address.isEmpty() -> add(ProviderFormError.AddressRequired)
                trimmed.address.length > ProviderForm.MAX_ADDRESS_LENGTH ->
                    add(ProviderFormError.AddressTooLong(ProviderForm.MAX_ADDRESS_LENGTH))
            }

            if (!isPhoneValid(trimmed.phoneDigits)) add(ProviderFormError.PhoneInvalid)

            if (trimmed.description.length > ProviderForm.MAX_DESCRIPTION_LENGTH) {
                add(ProviderFormError.DescriptionTooLong(ProviderForm.MAX_DESCRIPTION_LENGTH))
            }

            if (trimmed.website.isNotEmpty() && WebsiteLink.sanitize(trimmed.website) == null) {
                add(ProviderFormError.WebsiteInvalid)
            }
        }
    }
}

/**
 * Сайт заведения: приводится к абсолютной ссылке или отвергается.
 *
 * Схему дописываем сами (`mahalla.uz` → `https://mahalla.uz`): требовать её от
 * владельца кафе — верный способ получить пустое поле. А вот чужие схемы не
 * принимаем вовсе: ссылку из карточки открывает `Intent`, и `intent://` или
 * `market://` в поле «сайт» — это не сайт (то же правило, что у ссылки на
 * бота в issue #46 и на магазин в issue #80).
 */
object WebsiteLink {

    fun sanitize(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val withScheme = when {
            value.startsWith(HTTPS, ignoreCase = true) -> value
            value.startsWith(HTTP, ignoreCase = true) -> value
            // Что-то со схемой, но не http(s) — отклоняем, а не «чиним».
            SCHEME.containsMatchIn(value) -> return null
            else -> HTTPS + value
        }
        val host = withScheme
            .substringAfter("://", missingDelimiterValue = "")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('@')
        // Хост без точки (`https://localhost`, `https://шоп`) сайтом заведения
        // быть не может, а пробел внутри — верный признак опечатки.
        if (!host.contains('.') || host.any(Char::isWhitespace)) return null
        if (host.substringAfterLast('.').length < MIN_TLD_LENGTH) return null
        return withScheme
    }

    private const val HTTPS = "https://"
    private const val HTTP = "http://"
    private const val MIN_TLD_LENGTH = 2
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
}

/**
 * Статус заведения на бэкенде (`Detail.status`). Новая заявка приезжает
 * `PENDING` — модерация ещё не смотрела.
 *
 * [Unknown] — не ошибка: сервер может завести новый статус раньше релиза
 * приложения, и показать «заявка отправлена» правильнее, чем экран отказа.
 */
enum class PlaceModerationStatus(val apiValue: String) {
    Pending("PENDING"),
    Active("ACTIVE"),
    Suspended("SUSPENDED"),
    Closed("CLOSED"),
    Unknown(""),
    ;

    companion object {
        fun fromApi(value: String?): PlaceModerationStatus {
            val raw = value?.trim().orEmpty()
            if (raw.isEmpty()) return Unknown
            return entries.firstOrNull { it.apiValue.equals(raw, ignoreCase = true) } ?: Unknown
        }
    }
}

/**
 * Что вернул бэкенд на заявку. Имя нужно экрану («Osh Markazi отправлено на
 * модерацию»), идентификатор — на будущее: бизнес-панель (эпик #16) начнётся
 * с него.
 */
data class RegisteredPlace(
    val id: String,
    val name: String,
    val status: PlaceModerationStatus,
)
