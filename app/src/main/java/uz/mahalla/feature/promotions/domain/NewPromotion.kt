package uz.mahalla.feature.promotions.domain

import androidx.compose.runtime.Immutable

/**
 * Черновик новой акции заведения (issue #252, `POST promotions/places/{placeId}`).
 *
 * Обязателен только [title] — контракт создания не сверен живым запросом
 * (нет Bearer владельца в песочнице, см. `PromotionsApi`), поэтому клиент не
 * выдумывает других ограничений сверх того, что уже подтверждено ответом
 * `Promotion` того же контроллера.
 *
 * `priceSum`-разбор чисел — тот же приём, что у [uz.mahalla.feature.pharmacy.domain.NewPharmacyProductDraft]:
 * цифры выбираются из строки, а не парсятся как есть, иначе значение с
 * пробелом между разрядами (`MoneyFormatter`) превращалось бы в ошибку поля.
 */
@Immutable
data class NewPromotionDraft(
    val title: String = "",
    val description: String = "",
    val type: CreatablePromoType = CreatablePromoType.PercentOff,
    val discountPercentText: String = "",
    val discountAmountText: String = "",
    val minOrderAmountText: String = "",
    val promoCode: String = "",
) {
    val discountPercent: Int?
        get() = discountPercentText.filter(Char::isDigit).takeIf(String::isNotEmpty)?.toIntOrNull()

    val discountAmountSum: Long?
        get() = discountAmountText.filter(Char::isDigit).takeIf(String::isNotEmpty)?.toLongOrNull()

    val minOrderAmountSum: Long?
        get() = minOrderAmountText.filter(Char::isDigit).takeIf(String::isNotEmpty)?.toLongOrNull()

    val isTitleValid: Boolean get() = title.trim().isNotEmpty()

    /**
     * Вид акции требует своего числа: без него скидки попросту нет, а
     * `FreeDelivery` числа не требует вовсе.
     */
    val isDiscountValid: Boolean
        get() = when (type) {
            CreatablePromoType.PercentOff -> discountPercent?.let { it in PERCENT_RANGE } == true
            CreatablePromoType.FixedOff -> discountAmountSum?.let { it > 0 } == true
            CreatablePromoType.FreeDelivery -> true
        }

    val canSubmit: Boolean get() = isTitleValid && isDiscountValid

    fun withTitle(value: String): NewPromotionDraft = copy(title = value)
    fun withDescription(value: String): NewPromotionDraft = copy(description = value)
    fun withType(value: CreatablePromoType): NewPromotionDraft = copy(type = value)
    fun withDiscountPercent(value: String): NewPromotionDraft = copy(discountPercentText = value)
    fun withDiscountAmount(value: String): NewPromotionDraft = copy(discountAmountText = value)
    fun withMinOrderAmount(value: String): NewPromotionDraft = copy(minOrderAmountText = value)
    fun withPromoCode(value: String): NewPromotionDraft = copy(promoCode = value)

    companion object {
        private val PERCENT_RANGE = 1..100

        /** Тот же приём, что у [uz.mahalla.feature.pharmacy.domain.NewPharmacyProductDraft]. */
        const val INVALID_CODE = "PROMOTION_FORM_INVALID"
    }
}

/**
 * Виды акции, которые форма умеет создать — подмножество [PromoType]
 * бэкенда.
 *
 * `BUY_X_GET_Y`, `HAPPY_HOUR`, `FLASH_SALE` сюда не входят: они требуют
 * дополнительных условий (временные окна, «купи X — получи Y»), для которых
 * на клиенте нет ни поля, ни подтверждённой схемы — выдумывать их нельзя.
 */
enum class CreatablePromoType(val serverValue: String) {
    PercentOff("PERCENT_OFF"),
    FixedOff("FIXED_OFF"),
    FreeDelivery("FREE_DELIVERY"),
}
