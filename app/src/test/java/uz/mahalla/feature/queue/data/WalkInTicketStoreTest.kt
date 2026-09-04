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
import uz.mahalla.feature.queue.domain.WalkInStatus
import uz.mahalla.feature.queue.domain.WalkInTicket
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
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WalkInTicketStoreTest {

    @Test
    fun `a ticket survives a rewrite and is found by its place`() = runTest {
        val dataStore = dataStore()
        DataStoreWalkInTicketStore(dataStore, clock(NOW)).save(
            ticket(
                queuePosition = 3,
                estimatedWaitMinutes = 25,
                counterTime = LocalTime.of(14, 30),
            ),
        )

        // Новый экземпляр — как после перезапуска процесса.
        val restored = DataStoreWalkInTicketStore(dataStore, clock(NOW)).active("p-1")

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
        val store = DataStoreWalkInTicketStore(dataStore(), clock(NOW))
        store.save(ticket())

        assertNull(store.active("p-2"))
    }

    @Test
    fun `a finished ticket is dropped, not stored`() = runTest {
        val store = DataStoreWalkInTicketStore(dataStore(), clock(NOW))
        store.save(ticket())

        store.save(ticket(status = WalkInStatus.Cancelled))

        // Иначе отменённый талон запирал бы новую запись в это же заведение.
        assertNull(store.active("p-1"))
    }

    @Test
    fun `a new ticket replaces the previous one of the same place`() = runTest {
        val store = DataStoreWalkInTicketStore(dataStore(), clock(NOW))
        store.save(ticket())

        store.save(ticket(id = "t-2", queuePosition = 1))

        assertEquals("t-2", store.active("p-1")?.id)
    }

    @Test
    fun `a ticket from yesterday is not alive any more`() = runTest {
        val dataStore = dataStore()
        DataStoreWalkInTicketStore(dataStore, clock(NOW)).save(ticket())

        val later = NOW + Duration.ofHours(13)

        assertNull(DataStoreWalkInTicketStore(dataStore, clock(later)).active("p-1"))
    }

    @Test
    fun `a broken stored value reads as no tickets`() = runTest {
        val dataStore = dataStore()
        dataStore.edit { it[PreferenceKeys.WalkInTickets] = "{not json" }

        // Формат мог измениться в прошлой версии приложения: экран должен
        // открыться с формой записи, а не упасть.
        assertNull(DataStoreWalkInTicketStore(dataStore, clock(NOW)).active("p-1"))
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

    private fun clock(now: Instant) = Clock.fixed(now, ZoneOffset.UTC)

    private fun dataStore(): DataStore<Preferences> {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = File(context.filesDir, "walkin_${counter.incrementAndGet()}.preferences_pb")
        return PreferenceDataStoreFactory.create(produceFile = { file })
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-04T09:00:00Z")
        val counter = AtomicInteger(0)
    }
}
