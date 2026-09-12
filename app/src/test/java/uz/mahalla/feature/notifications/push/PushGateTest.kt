package uz.mahalla.feature.notifications.push

import org.junit.Assert.assertEquals
import org.junit.Test
import uz.mahalla.feature.notifications.domain.NotificationCategory
import uz.mahalla.feature.notifications.domain.NotificationSettings
import uz.mahalla.feature.notifications.domain.QuietHours
import java.time.LocalTime

/**
 * Показывать ли пуш (эпик 11): отозванное разрешение, выключенный канал,
 * выключенная категория, тихие часы.
 *
 * Проверяется здесь, а не на настоящем `NotificationManager`: эмулятора в CI
 * нет (AGENTS.md), а решение — чистая функция ровно для того, чтобы это было
 * возможно.
 */
class PushGateTest {

    @Test
    fun `push is shown with sound by default`() {
        val decision = decide()

        assertEquals(PushDecision.Show(NotificationCategory.Orders, silent = false), decision)
    }

    /**
     * Разрешение отозвали — показать нельзя физически, и это первое, что
     * проверяется: остальные причины уже не имеют значения.
     */
    @Test
    fun `revoked permission suppresses the push`() {
        val decision = decide(permissions = allowed().copy(permissionGranted = false))

        assertEquals(PushDecision.Suppressed(PushSuppression.PermissionRevoked), decision)
    }

    /**
     * Отозванное разрешение важнее выключенной категории: причина уходит в
     * отчёты, и «человек выключил заказы» вместо «у приложения нет разрешения»
     * увело бы разбор жалобы не туда.
     */
    @Test
    fun `revoked permission wins over a muted category`() {
        val decision = decide(
            permissions = allowed().copy(permissionGranted = false),
            settings = NotificationSettings(mutedCategories = setOf(NotificationCategory.Orders)),
        )

        assertEquals(PushDecision.Suppressed(PushSuppression.PermissionRevoked), decision)
    }

    @Test
    fun `notifications disabled system wide suppress the push`() {
        val decision = decide(permissions = allowed().copy(notificationsEnabled = false))

        assertEquals(PushDecision.Suppressed(PushSuppression.NotificationsDisabled), decision)
    }

    @Test
    fun `channel disabled in system settings suppresses the push`() {
        val decision = decide(permissions = allowed().copy(channelEnabled = false))

        assertEquals(PushDecision.Suppressed(PushSuppression.ChannelDisabled), decision)
    }

    @Test
    fun `muted category suppresses the push`() {
        val decision = decide(
            settings = NotificationSettings(mutedCategories = setOf(NotificationCategory.Orders)),
        )

        assertEquals(PushDecision.Suppressed(PushSuppression.CategoryMuted), decision)
    }

    /** Выключена другая категория — на заказ это не влияет. */
    @Test
    fun `muting one category does not touch the others`() {
        val decision = decide(
            settings = NotificationSettings(
                mutedCategories = setOf(NotificationCategory.Marketing),
            ),
        )

        assertEquals(PushDecision.Show(NotificationCategory.Orders, silent = false), decision)
    }

    /**
     * Тихие часы **не выбрасывают** пуш: уведомление показывается без звука.
     * Иначе человек, включивший тишину ради сна, не узнал бы утром, что ночью
     * заказ отменили.
     */
    @Test
    fun `quiet hours show the push silently`() {
        val decision = decide(
            settings = NotificationSettings(quietHours = QuietHours(enabled = true)),
            now = LocalTime.of(2, 30),
        )

        assertEquals(PushDecision.Show(NotificationCategory.Orders, silent = true), decision)
    }

    @Test
    fun `outside quiet hours the push sounds again`() {
        val decision = decide(
            settings = NotificationSettings(quietHours = QuietHours(enabled = true)),
            now = LocalTime.of(9, 0),
        )

        assertEquals(PushDecision.Show(NotificationCategory.Orders, silent = false), decision)
    }

    /** Категория берётся из типа: пуш акции уходит в канал маркетинга. */
    @Test
    fun `decision carries the category of the message type`() {
        val decision = decide(
            message = PushMessage.of(mapOf("type" to "PROMOTION_CREATED")),
        )

        assertEquals(PushDecision.Show(NotificationCategory.Marketing, silent = false), decision)
    }

    private fun decide(
        message: PushMessage = orderPush(),
        settings: NotificationSettings = NotificationSettings(),
        permissions: PushPermissions = allowed(),
        now: LocalTime = LocalTime.of(12, 0),
    ): PushDecision = PushGate.decide(message, settings, permissions, now)

    private fun orderPush(): PushMessage = PushMessage.of(
        mapOf("type" to "ORDER_PLACED", "entityId" to "o-1"),
    )

    private fun allowed(): PushPermissions = PushPermissions(
        permissionGranted = true,
        notificationsEnabled = true,
        channelEnabled = true,
    )
}
