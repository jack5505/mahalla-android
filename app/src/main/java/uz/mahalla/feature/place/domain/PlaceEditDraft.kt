package uz.mahalla.feature.place.domain

import androidx.compose.runtime.Immutable
import uz.mahalla.feature.role.domain.WebsiteLink

/**
 * Черновик правки карточки места (issue #188): владелец меняет то, что уже
 * есть, — точку на карте эта форма не трогает, координаты уходят вместе с
 * остальным без изменений (см. `UpdatePlaceRequest`).
 *
 * Ограничения длины — из схемы бэкенда `UpdateRequest` (сверено по живому
 * `/v3/api-docs` 2026-09-10): `name` ≤ 300, `address` ≤ 500, `city` ≤ 100,
 * `phone` ≤ 20, `website` ≤ 300. У `description` ограничения в схеме нет —
 * взят тот же курьёзный предел, что у анкеты регистрации ([ProviderForm]),
 * чтобы форма правки не отправляла то, что заведомо не влезет в карточку.
 */
@Immutable
data class PlaceEditDraft(
    val name: String = "",
    val description: String = "",
    val address: String = "",
    val city: String = "",
    val phone: String = "",
    val website: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    fun trimmed(): PlaceEditDraft = copy(
        name = name.trim(),
        description = description.trim(),
        address = address.trim(),
        city = city.trim(),
        phone = phone.trim(),
        website = website.trim(),
    )

    val isNameValid: Boolean
        get() = trimmed().name.let { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH }

    val isDescriptionValid: Boolean
        get() = trimmed().description.length <= MAX_DESCRIPTION_LENGTH

    val isAddressValid: Boolean
        get() = trimmed().address.length <= MAX_ADDRESS_LENGTH

    val isCityValid: Boolean
        get() = trimmed().city.length <= MAX_CITY_LENGTH

    val isPhoneValid: Boolean
        get() = trimmed().phone.length <= MAX_PHONE_LENGTH

    val isWebsiteValid: Boolean
        get() {
            val raw = trimmed().website
            if (raw.isEmpty()) return true
            val sanitized = WebsiteLink.sanitize(raw) ?: return false
            return sanitized.length <= MAX_WEBSITE_LENGTH
        }

    val canSubmit: Boolean
        get() = isNameValid && isDescriptionValid && isAddressValid && isCityValid &&
            isPhoneValid && isWebsiteValid

    fun withName(value: String): PlaceEditDraft = copy(name = value)
    fun withDescription(value: String): PlaceEditDraft = copy(description = value)
    fun withAddress(value: String): PlaceEditDraft = copy(address = value)
    fun withCity(value: String): PlaceEditDraft = copy(city = value)
    fun withPhone(value: String): PlaceEditDraft = copy(phone = value)
    fun withWebsite(value: String): PlaceEditDraft = copy(website = value)

    companion object {
        const val MAX_NAME_LENGTH = 300
        const val MAX_DESCRIPTION_LENGTH = 2000
        const val MAX_ADDRESS_LENGTH = 500
        const val MAX_CITY_LENGTH = 100
        const val MAX_PHONE_LENGTH = 20
        const val MAX_WEBSITE_LENGTH = 300

        /** Тот же код, каким бэкенд отвечает на невалидное тело (issue #76). */
        const val INVALID_CODE = "VALIDATION_ERROR"
    }
}
