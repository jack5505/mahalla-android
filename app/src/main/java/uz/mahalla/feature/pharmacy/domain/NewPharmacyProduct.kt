package uz.mahalla.feature.pharmacy.domain

import androidx.compose.runtime.Immutable

/**
 * Черновик нового товара витрины (issue #252, `PharmacyCreateRequest`).
 *
 * Обязательны только [name] (≤ 300 символов в схеме) и цена — остальное схема
 * не ограничивает. `priceText`/`stockText` — сырой ввод, как у пополнения
 * кошелька ([uz.mahalla.feature.wallet.domain.TopUpDraft]): битая цифра
 * обязана стать ошибкой поля, а не молча обнулиться.
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
    /** `null`, если поле пустое или в нём не только цифры. */
    val priceSum: Long? get() = priceText.trim().takeIf(String::isNotEmpty)?.toLongOrNull()

    /**
     * Остаток необязателен: пустое поле — «не считали на складе», а не ноль,
     * который читался бы как «закончилось».
     */
    val stockQuantity: Int? get() =
        stockText.trim().takeIf(String::isNotEmpty)?.toIntOrNull()?.takeIf { it >= 0 }

    val isNameValid: Boolean
        get() = name.trim().let { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH }

    val isPriceValid: Boolean get() = priceSum?.let { it >= 0 } == true

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
