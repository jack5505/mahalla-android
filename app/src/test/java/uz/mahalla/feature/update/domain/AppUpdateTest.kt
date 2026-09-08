package uz.mahalla.feature.update.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Правила решения об обновлении (issue #80).
 *
 * Цена ошибки здесь несимметрична: лишний блокирующий экран превращает
 * приложение в кирпич сразу на всех устройствах, поэтому каждое условие
 * закреплено отдельно.
 */
class AppUpdateTest {

    @Test
    fun `required flag blocks the app`() {
        val decision = UpdateDecision.of(
            updateRequired = true,
            updateAvailable = true,
            update = AppUpdate(versionName = "1.4.0"),
        )

        assertEquals(UpdateDecision.Required(AppUpdate(versionName = "1.4.0")), decision)
    }

    @Test
    fun `the immediate policy blocks the app even without the flag`() {
        // Флаг и политика приезжают независимо: на стенде политика бывает
        // null при выставленном флаге, значит и наоборот полагаться нельзя.
        val decision = UpdateDecision.of(
            updateRequired = false,
            updateAvailable = true,
            update = AppUpdate(policy = UpdatePolicy.Immediate),
        )

        assertEquals(
            UpdateDecision.Required(AppUpdate(policy = UpdatePolicy.Immediate)),
            decision,
        )
    }

    @Test
    fun `an available update with skips left is only suggested`() {
        val update = AppUpdate(remainingSkips = 2, policy = UpdatePolicy.Flexible)

        val decision = UpdateDecision.of(
            updateRequired = false,
            updateAvailable = true,
            update = update,
        )

        assertEquals(UpdateDecision.Suggested(update), decision)
    }

    @Test
    fun `unknown remaining skips still allow postponing`() {
        // Стенд отвечает `remainingSkips: null` — молчание сервера не должно
        // превращаться в невозможность отложить необязательное обновление.
        val update = AppUpdate(remainingSkips = null)

        val decision = UpdateDecision.of(
            updateRequired = false,
            updateAvailable = true,
            update = update,
        )

        assertEquals(UpdateDecision.Suggested(update), decision)
    }

    @Test
    fun `an available update without skips left shows nothing`() {
        // Экран без «Позже» при неблокирующем ответе сервера был бы той самой
        // блокировкой, которой бэкенд не просил: настоять он может флагом.
        val decision = UpdateDecision.of(
            updateRequired = false,
            updateAvailable = true,
            update = AppUpdate(remainingSkips = 0),
        )

        assertEquals(UpdateDecision.None, decision)
    }

    @Test
    fun `nothing to show when the version is current`() {
        val decision = UpdateDecision.of(
            updateRequired = false,
            updateAvailable = false,
            update = AppUpdate(),
        )

        assertEquals(UpdateDecision.None, decision)
    }

    @Test
    fun `an unknown policy alone never blocks the app`() {
        // Список политик у бэкенда открыт и уже менялся: новая политика не
        // должна запирать приложение сама по себе.
        val decision = UpdateDecision.of(
            updateRequired = false,
            updateAvailable = false,
            update = AppUpdate(policy = UpdatePolicy.Unknown),
        )

        assertEquals(UpdateDecision.None, decision)
    }

    @Test
    fun `policies are parsed case and space tolerantly`() {
        assertEquals(UpdatePolicy.Optional, UpdatePolicy.fromServer("OPTIONAL"))
        assertEquals(UpdatePolicy.Flexible, UpdatePolicy.fromServer(" flexible "))
        assertEquals(UpdatePolicy.Immediate, UpdatePolicy.fromServer("Immediate"))
        assertEquals(UpdatePolicy.Unknown, UpdatePolicy.fromServer("FORCED"))
        assertEquals(UpdatePolicy.Unknown, UpdatePolicy.fromServer(null))
    }
}

/**
 * Проверка ссылки на магазин. Ссылку присылает сервер, а адрес сервера в debug
 * вводит пользователь (issue #26) — без проверки подменённый бэкенд запускал бы
 * на устройстве произвольный intent.
 */
class StoreLinkTest {

    @Test
    fun `store schemes pass`() {
        assertEquals(
            "https://play.google.com/store/apps/details?id=uz.mahalla",
            StoreLink.sanitize("https://play.google.com/store/apps/details?id=uz.mahalla"),
        )
        assertEquals(
            "market://details?id=uz.mahalla",
            StoreLink.sanitize("market://details?id=uz.mahalla"),
        )
    }

    @Test
    fun `https on any host passes`() {
        // Список магазинов не ограничиваем намеренно: APK проекта вполне может
        // лежать на своём же сервере, а открытая в браузере ссылка показывает
        // человеку адрес, куда он идёт.
        assertEquals("https://mahalla.uz/app.apk", StoreLink.sanitize("https://mahalla.uz/app.apk"))
    }

    @Test
    fun `arbitrary intents are refused`() {
        assertNull("чужой deep link", StoreLink.sanitize("mahalla://place/42"))
        assertNull("intent с явным компонентом", StoreLink.sanitize("intent://evil#Intent;end"))
        assertNull(StoreLink.sanitize("javascript:alert(1)"))
        assertNull(StoreLink.sanitize("content://media/external/file/1"))
    }

    @Test
    fun `cleartext is refused`() {
        // Установочный файл не должен ехать по каналу, где его подменят.
        assertNull(StoreLink.sanitize("http://mahalla.uz/app.apk"))
    }

    @Test
    fun `blank and malformed links are refused`() {
        assertNull(StoreLink.sanitize(null))
        assertNull(StoreLink.sanitize("   "))
        assertNull("схемы нет вовсе", StoreLink.sanitize("play.google.com/store"))
        assertNull("https без разделителя", StoreLink.sanitize("https:evil"))
    }

    @Test
    fun `surrounding spaces do not break a valid link`() {
        assertEquals("https://mahalla.uz/", StoreLink.sanitize("  https://mahalla.uz/  "))
    }

    @Test
    fun `the fallback points at our own package`() {
        assertEquals(
            "https://play.google.com/store/apps/details?id=uz.mahalla",
            StoreLink.playStore("uz.mahalla"),
        )
    }
}
