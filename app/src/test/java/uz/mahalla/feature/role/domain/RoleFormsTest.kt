package uz.mahalla.feature.role.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.feature.discovery.domain.PlaceCategory
import uz.mahalla.feature.onboarding.domain.City

/**
 * Анкеты покупателя и продавца (issue #84): правила проверки — чистый домен,
 * поэтому проверяются без Android и без сети.
 */
class RoleFormsTest {

    // --- Роль ---

    @Test
    fun `role survives a round trip through storage`() {
        UserRole.entries.forEach { role ->
            assertEquals(role, UserRole.fromStoredValue(role.storedValue))
        }
    }

    @Test
    fun `unknown role reads as no choice at all`() {
        // Значение могло приехать из будущей версии приложения. Врать про
        // выбор человека хуже, чем спросить заново.
        assertNull(UserRole.fromStoredValue("seller"))
        assertNull(UserRole.fromStoredValue(""))
        assertNull(UserRole.fromStoredValue(null))
        assertEquals(UserRole.Customer, UserRole.fromStoredValue(" CUSTOMER "))
    }

    // --- Анкета покупателя ---

    @Test
    fun `customer form needs a name and a city`() {
        val errors = CustomerFormValidator.validate(CustomerForm())

        assertTrue(CustomerFormError.NameRequired in errors)
        assertTrue(CustomerFormError.CityRequired in errors)
    }

    @Test
    fun `whitespace is not a name`() {
        val errors = CustomerFormValidator.validate(
            CustomerForm(fullName = "   ", city = City.TASHKENT),
        )

        assertEquals(listOf(CustomerFormError.NameRequired), errors)
    }

    @Test
    fun `address is optional`() {
        val errors = CustomerFormValidator.validate(
            CustomerForm(fullName = "Jahongir", city = City.TASHKENT),
        )

        assertTrue(errors.isEmpty())
    }

    @Test
    fun `too long fields are reported with their limit`() {
        val errors = CustomerFormValidator.validate(
            CustomerForm(
                fullName = "a".repeat(CustomerForm.MAX_NAME_LENGTH + 1),
                city = City.TASHKENT,
                address = "b".repeat(CustomerForm.MAX_ADDRESS_LENGTH + 1),
            ),
        )

        assertTrue(CustomerFormError.NameTooLong(CustomerForm.MAX_NAME_LENGTH) in errors)
        assertTrue(CustomerFormError.AddressTooLong(CustomerForm.MAX_ADDRESS_LENGTH) in errors)
    }

    @Test
    fun `saved form keeps the text without edges`() {
        val trimmed = CustomerForm(fullName = "  Jahongir  ", address = " Chilonzor 12 ").trimmed()

        assertEquals("Jahongir", trimmed.fullName)
        assertEquals("Chilonzor 12", trimmed.address)
    }

    @Test
    fun `empty form is recognised as unfilled`() {
        assertTrue(CustomerForm().isEmpty)
        assertTrue(!CustomerForm(city = City.TASHKENT).isEmpty)
    }

    // --- Анкета продавца ---

    @Test
    fun `provider form lists every missing field at once`() {
        val errors = ProviderFormValidator.validate(ProviderForm(), isPhoneValid = { false })

        // Человек заполняет форму целиком: показывать замечания по одному —
        // это заставить его нажимать «отправить» пять раз.
        assertTrue(ProviderFormError.NameRequired in errors)
        assertTrue(ProviderFormError.CategoryRequired in errors)
        assertTrue(ProviderFormError.CityRequired in errors)
        assertTrue(ProviderFormError.AddressRequired in errors)
        assertTrue(ProviderFormError.PhoneInvalid in errors)
    }

    @Test
    fun `filled provider form passes`() {
        val errors = ProviderFormValidator.validate(validForm(), isPhoneValid = { true })

        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test
    fun `other is not a category the server can take`() {
        // У `Other` пустой apiValue — отправлять на сервер нечего.
        val errors = ProviderFormValidator.validate(
            validForm().copy(category = PlaceCategory.Other),
            isPhoneValid = { true },
        )

        assertEquals(listOf(ProviderFormError.CategoryRequired), errors)
    }

    @Test
    fun `single letter name is a typo, not a place`() {
        val errors = ProviderFormValidator.validate(
            validForm().copy(name = "O"),
            isPhoneValid = { true },
        )

        assertEquals(listOf(ProviderFormError.NameTooShort(ProviderForm.MIN_NAME_LENGTH)), errors)
    }

    @Test
    fun `empty website is fine, broken one is not`() {
        assertTrue(
            ProviderFormValidator.validate(validForm().copy(website = "  "), { true }).isEmpty(),
        )
        assertEquals(
            listOf(ProviderFormError.WebsiteInvalid),
            ProviderFormValidator.validate(validForm().copy(website = "mahalla"), { true }),
        )
    }

    // --- Ссылка на сайт ---

    @Test
    fun `website gets the scheme it is missing`() {
        assertEquals("https://mahalla.uz", WebsiteLink.sanitize("mahalla.uz"))
        assertEquals("https://mahalla.uz/menu", WebsiteLink.sanitize(" mahalla.uz/menu "))
        assertEquals("http://mahalla.uz", WebsiteLink.sanitize("http://mahalla.uz"))
        assertEquals("https://MAHALLA.uz", WebsiteLink.sanitize("https://MAHALLA.uz"))
    }

    @Test
    fun `foreign schemes are rejected, not repaired`() {
        // Ссылку из карточки открывает Intent: `market://` и `intent://` в
        // поле «сайт» — это не сайт (то же правило, что у ссылки на бота).
        assertNull(WebsiteLink.sanitize("market://details?id=uz.mahalla"))
        assertNull(WebsiteLink.sanitize("intent://evil"))
        assertNull(WebsiteLink.sanitize("mahalla://place/1"))
        assertNull(WebsiteLink.sanitize("javascript:alert(1)"))
    }

    @Test
    fun `host without a dot is not a website`() {
        assertNull(WebsiteLink.sanitize("localhost"))
        assertNull(WebsiteLink.sanitize("https://localhost"))
        assertNull(WebsiteLink.sanitize("mahalla uz"))
        assertNull(WebsiteLink.sanitize("mahalla.u"))
        assertNull(WebsiteLink.sanitize(""))
    }

    // --- Статус заведения ---

    @Test
    fun `moderation status is read leniently`() {
        assertEquals(PlaceModerationStatus.Pending, PlaceModerationStatus.fromApi("PENDING"))
        assertEquals(PlaceModerationStatus.Active, PlaceModerationStatus.fromApi(" active "))
        // Новый статус бэкенда не должен превращаться в экран ошибки.
        assertEquals(PlaceModerationStatus.Unknown, PlaceModerationStatus.fromApi("MODERATING"))
        assertEquals(PlaceModerationStatus.Unknown, PlaceModerationStatus.fromApi(null))
    }

    private fun validForm(): ProviderForm = ProviderForm(
        name = "Osh Markazi",
        category = PlaceCategory.Food,
        city = City.TASHKENT,
        address = "Chilonzor, 12-kvartal",
        phoneDigits = "901234567",
    )
}
