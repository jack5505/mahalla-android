package uz.mahalla.feature.auth.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Разбор поля `channel` из ответа `auth/send-otp` (issue #54).
 *
 * Значение решает, что написано на экране кода: «код отправлен на номер» или
 * «код в Telegram». Ошибиться в эту сторону дорого — человек ждёт SMS, которого
 * не будет, до самого истечения кода.
 */
class OtpDeliveryChannelTest {

    @Test
    fun `server values are recognised`() {
        assertEquals(OtpDeliveryChannel.Sms, OtpDeliveryChannel.of("SMS"))
        assertEquals(OtpDeliveryChannel.Telegram, OtpDeliveryChannel.of("TELEGRAM"))
    }

    @Test
    fun `case and spaces do not matter`() {
        // Список значений бэкенда уже менялся (issue #42); лишний пробел или
        // другой регистр не повод соврать пользователю про SMS.
        assertEquals(OtpDeliveryChannel.Telegram, OtpDeliveryChannel.of(" telegram "))
    }

    @Test
    fun `silence and unknown channels stay sms`() {
        // Обещать Telegram там, где его может не быть, хуже, чем не обещать
        // ничего: это поведение экрана до issue #54.
        assertEquals(OtpDeliveryChannel.Sms, OtpDeliveryChannel.of(null))
        assertEquals(OtpDeliveryChannel.Sms, OtpDeliveryChannel.of(""))
        assertEquals(OtpDeliveryChannel.Sms, OtpDeliveryChannel.of("VOICE"))
    }

    @Test
    fun `route argument survives a round trip`() {
        // Канал едет в маршрут именем константы: типизированные маршруты
        // Navigation кладут аргументы в Bundle, и для enum нужен свой NavType.
        OtpDeliveryChannel.entries.forEach { channel ->
            assertEquals(channel, OtpDeliveryChannel.byName(channel.name))
        }
    }

    @Test
    fun `a broken route argument does not crash the screen`() {
        assertEquals(OtpDeliveryChannel.Sms, OtpDeliveryChannel.byName(null))
        // `byName` читает именно имя константы, а не значение сервера: сырой
        // «TELEGRAM» в аргумент маршрута не кладётся (это делает `of`), и
        // принимать его здесь значило бы завести второй формат одного поля.
        assertEquals(OtpDeliveryChannel.Sms, OtpDeliveryChannel.byName("TELEGRAM"))
    }
}
