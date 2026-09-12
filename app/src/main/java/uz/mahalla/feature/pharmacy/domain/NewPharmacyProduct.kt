package uz.mahalla.feature.pharmacy.domain

import androidx.compose.runtime.Immutable

/**
 * Черновик нового товара витрины (issue #252, `PharmacyCreateRequest`).
 *
 * Обязательны только [name] (≤ 300 символов в схеме) и цена — остальное схема
 * не ограничивает. `priceText`/`stockText` разбираются тем же приёмом, что и
 * сумма пополнения кошелька (`WalletTopUp.parseAmount`): цифры выбираются из
 * строки, а не парсятся как есть, — иначе `MoneyFormatter`-форматированное или
 * вставленное значение с пробелом между разрядами превращалось бы в ошибку
 * поля вместо того, чтобы разобраться. Минус вместе с прочим мусором
 * отфильтровывается, так что поле физически не может стать отрицательным.
 */
@Immutable
data class NewPharmacyProductDraft(
    val name: String = "",
    val manufacturer: String = "",
    val description: String = "",
    val dosageForm: String = "",
    val strength: String = "",
    val priceText: String = "",
    val stockText: String = "",
    val requiresPrescription: Boolean = false,
) {
    /** `null`, если в поле нет ни одной цифры. */
    val priceSum: Long? get() = priceText.filter(Char::isDigit).takeIf(String::isNotEmpty)
        ?.toLongOrNull()

    /**
     * Остаток необязателен: пустое поле — «не считали на складе», а не ноль,
     * который читался бы как «закончилось».
     */
    val stockQuantity: Int? get() = stockText.filter(Char::isDigit).takeIf(String::isNotEmpty)
        ?.toIntOrNull()

    val isNameValid: Boolean
        get() = name.trim().let { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH }

    val isPriceValid: Boolean get() = priceSum != null

    val isStockValid: Boolean get() = stockText.isBlank() || stockQuantity != null

    val canSubmit: Boolean get() = isNameValid && isPriceValid && isStockValid

    fun withName(value: String): NewPharmacyProductDraft = copy(name = value)
    fun withManufacturer(value: String): NewPharmacyProductDraft = copy(manufacturer = value)
    fun withDescription(value: String): NewPharmacyProductDraft = copy(description = value)
    fun withDosageForm(value: String): NewPharmacyProductDraft = copy(dosageForm = value)
    fun withStrength(value: String): NewPharmacyProductDraft = copy(strength = value)
    fun withPrice(value: String): NewPharmacyProductDraft = copy(priceText = value)
    fun withStock(value: String): NewPharmacyProductDraft = copy(stockText = value)
    fun withPrescription(value: Boolean): NewPharmacyProductDraft =
        copy(requiresPrescription = value)

    companion object {
        const val MAX_NAME_LENGTH = 300

        /** Тот же приём, что у [uz.mahalla.feature.role.domain.ProviderForm]. */
        const val INVALID_CODE = "PHARMACY_PRODUCT_FORM_INVALID"
    }
}
