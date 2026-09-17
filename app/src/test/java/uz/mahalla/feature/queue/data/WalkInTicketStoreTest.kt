package uz.mahalla.feature.queue.data

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.data.prefs.PreferenceKeys
import uz.mahalla.data.prefs.UserProfile
import uz.mahalla.feature.queue.domain.WalkInStatus
import uz.mahalla.feature.queue.domain.WalkInTicket
import uz.mahalla.testutil.FakeUserProfileStore
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/**
 * Взятый талон на настоящем DataStore (issue #96).
 *
 * Хранилище здесь не кэш, а единственное место, где талон вообще можно
 * прочитать: ручки `walkin/my` / `walkin/{id}` у бэкенда нет. Поэтому
 * проверяется именно то, что талон переживает перезапуск, а мёртвый и
 * вчерашний — нет.
 *
 * Вторая половина проверок — владелец (issue #257): талон достаётся только
 * тому аккаунту, который его взял, и не затирается чужой записью. Телефон
 * один на семью, и человек B в очереди не должен видеть талон человека A —
 * тем более отменять его своим токеном.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WalkInTicketStoreTest {

    @Test
    fun `a ticket survives a rewrite and is found by its place`() = runTest {
        val dataStore = dataStore()
        store(dataStore).save(
            ticket(
                queuePosition = 3,
                estimatedWaitMinutes = 25,
                counterTime = LocalTime.of(14, 30),
            ),
        )

        // Новый экземпляр — как после перезапуска процесса.
        val restored = store(dataStore).active("p-1")

        assertEquals("t-1", restored?.id)
        assertEquals(WalkInStatus.Waiting, restored?.status)
        assertEquals(3, restored?.queuePosition)
        assertEquals(25, restored?.estimatedWaitMinutes)
        assertEquals(LocalTime.of(14, 30), restored?.counterTime)
        assertEquals("Barber House", restored?.placeName)
        assertEquals(NOW, restored?.receivedAt)
    }

    @Test
    fun `a ticket of another place is not offered here`() = runTest {
        val store = store()
        store.save(ticket())

        assertNull(store.active("p-2"))
    }

    @Test
    fun `a finished ticket is dropped, not stored`() = runTest {
        val store = store()
        store.save(ticket())

        store.save(ticket(status = WalkInStatus.Cancelled))

        // Иначе отменённый талон запирал бы новую запись в это же заведение.
        assertNull(store.active("p-1"))
    }

    @Test
    fun `a new ticket replaces the previous one of the same place`() = runTest {
        val store = store()
        store.save(ticket())

        store.save(ticket(id = "t-2", queuePosition = 1))

        assertEquals("t-2", store.active("p-1")?.id)
    }

    @Test
    fun `a ticket from yesterday is not alive any more`() = runTest {
        val dataStore = dataStore()
        store(dataStore).save(ticket())

        val later = NOW + Duration.ofHours(13)

        assertNull(store(dataStore, now = later).active("p-1"))
    }

    @Test
    fun `a broken stored value reads as no tickets`() = runTest {
        val dataStore = dataStore()
        dataStore.edit { it[PreferenceKeys.WalkInTickets] = "{not json" }

        // Формат мог измениться в прошлой версии приложения: экран должен
        // открыться с формой записи, а не упасть.
        assertNull(store(dataStore).active("p-1"))
    }

    @Test
    fun `a ticket is not offered to another account`() = runTest {
        val dataStore = dataStore()
        store(dataStore, accountId = "u-1").save(ticket())

        assertNull(store(dataStore, accountId = "u-2").active("p-1"))
    }

    @Test
    fun `a ticket waits for its owner and is not wiped by someone else's login`() = runTest {
        val dataStore = dataStore()
        store(dataStore, accountId = "u-1").save(ticket())

        // Другой человек вошёл и вышел: своего талона он не видит (выше), а
        // чужой при этом не стёрся — вернувшись, владелец находит его на месте.
        assertNull(store(dataStore, accountId = "u-2").active("p-1"))
        assertEquals("t-1", store(dataStore, accountId = "u-1").active("p-1")?.id)
    }

    @Test
    fun `a ticket of another account is not replaced by a ticket of the same place`() = runTest {
        val dataStore = dataStore()
        store(dataStore, accountId = "u-1").save(ticket())

        store(dataStore, accountId = "u-2").save(ticket(id = "t-2"))

        // Место одно и то же, но владельцы разные: чужой талон не затирается.
        assertEquals("t-1", store(dataStore, accountId = "u-1").active("p-1")?.id)
        assertEquals("t-2", store(dataStore, accountId = "u-2").active("p-1")?.id)
    }

    @Test
    fun `a ticket taken without a named account is not offered to anyone`() = runTest {
        val dataStore = dataStore()
        store(dataStore, accountId = null).save(ticket())

        // Безопасная сторона та же, что у анкеты (ADR 0008): лишний раз взять
        // талон дешевле, чем показать чужой номер в очереди.
        assertNull(store(dataStore, accountId = "u-1").active("p-1"))
        assertNull(store(dataStore, accountId = null).active("p-1"))
    }

    @Test
    fun `a ticket stored before ownership is not offered`() = runTest {
        val dataStore = dataStore()
        dataStore.edit {
            it[PreferenceKeys.WalkInTickets] = """
                [{"id":"t-1","placeId":"p-1","status":"WAITING","receivedAtEpochSeconds":${NOW.epochSecond}}]
            """.trimIndent()
        }

        assertNull(store(dataStore, accountId = "u-1").active("p-1"))
    }

    private fun ticket(
        id: String = "t-1",
        status: WalkInStatus = WalkInStatus.Waiting,
        queuePosition: Int? = null,
        estimatedWaitMinutes: Int? = null,
        counterTime: LocalTime? = null,
    ) = WalkInTicket(
        id = id,
        placeId = "p-1",
        placeName = "Barber House",
        userName = "Jahongir",
        status = status,
        queuePosition = queuePosition,
        estimatedWaitMinutes = estimatedWaitMinutes,
        counterTime = counterTime,
        receivedAt = NOW,
    )

    /** Хранилище при закрытом профиле: `accountId` — кто вошёл на устройстве. */
    private fun store(
        dataStore: DataStore<Preferences> = dataStore(),
        now: Instant = NOW,
        accountId: String? = ACCOUNT_ID,
    ) = DataStoreWalkInTicketStore(
        dataStore = dataStore,
        profileStore = FakeUserProfileStore(UserProfile(id = accountId)),
        clock = clock(now),
    )

    private fun clock(now: Instant) = Clock.fixed(now, ZoneOffset.UTC)

    private fun dataStore(): DataStore<Preferences> {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = File(context.filesDir, "walkin_${counter.incrementAndGet()}.preferences_pb")
        return PreferenceDataStoreFactory.create(produceFile = { file })
    }

    private companion object {
        const val ACCOUNT_ID = "u-1"
        val NOW: Instant = Instant.parse("2026-09-04T09:00:00Z")
        val counter = AtomicInteger(0)
    }
}
