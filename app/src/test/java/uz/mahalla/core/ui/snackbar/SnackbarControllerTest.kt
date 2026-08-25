package uz.mahalla.core.ui.snackbar

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.core.ui.components.MahallaTone

class SnackbarControllerTest {

    @Test
    fun `message reaches the subscriber`() = runTest {
        val controller = SnackbarController()

        controller.show(SnackbarMessage(text = "Buyurtma yuborildi", tone = MahallaTone.Success))

        val message = controller.messages.take(1).toList().single()
        assertEquals("Buyurtma yuborildi", message.text)
        assertEquals(MahallaTone.Success, message.tone)
    }

    @Test
    fun `messages queue up and keep their order`() = runTest {
        val controller = SnackbarController()

        controller.show("первое")
        controller.show("второе")

        // Буферизованный канал: сообщения, отправленные до подписки, не теряются —
        // иначе ошибка, случившаяся при старте экрана, осталась бы немой.
        assertEquals(
            listOf("первое", "второе"),
            controller.messages.take(2).toList().map(SnackbarMessage::text),
        )
    }

    @Test
    fun `message is delivered exactly once`() = runTest {
        val controller = SnackbarController()
        controller.show("однократно")

        assertEquals(1, controller.messages.take(1).toList().size)

        controller.show("второе")
        // Первое сообщение уже получено и повторно не приходит — иначе после
        // поворота экрана снекбар показывался бы заново.
        assertEquals("второе", controller.messages.take(1).toList().single().text)
    }

    @Test
    fun `tryShow reports success from non suspending code`() {
        val controller = SnackbarController()

        assertTrue(controller.tryShow(SnackbarMessage(text = "клик")))
    }

    @Test
    fun `action is carried with the message`() = runTest {
        var undone = false
        val controller = SnackbarController()

        controller.show(
            SnackbarMessage(
                text = "Buyurtma bekor qilindi",
                actionLabel = "Qaytarish",
                length = SnackbarLength.Long,
                onAction = { undone = true },
            ),
        )

        val message = controller.messages.take(1).toList().single()
        message.onAction?.invoke()

        assertEquals("Qaytarish", message.actionLabel)
        assertEquals(SnackbarLength.Long, message.length)
        assertTrue(undone)
    }
}
