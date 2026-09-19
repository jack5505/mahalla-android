package uz.mahalla.data.network.analytics

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.core.analytics.AnalyticsQueuedEvent
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.data.db.MahallaDatabase
import uz.mahalla.data.db.entity.AnalyticsEventEntity
import uz.mahalla.data.device.DeviceIdStore
import uz.mahalla.data.network.NetworkFactory
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Очередь `analytics/events` на диске (issue #226): события переживают
 * отказ сети и не задваиваются на успехе, но и не копятся вечно поверх того,
 * что сервер и так отбросит по возрасту.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DefaultAnalyticsEventQueueTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var database: MahallaDatabase
    private val repository = FakeEventsRepository()
    private val now = Instant.parse("2026-09-19T08:00:00Z")

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MahallaDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `an event is sent with the device id and its own occurredAt`() = runTest {
        val queue = queue()

        queue.enqueue(AnalyticsQueuedEvent(name = "screen_view", metadata = mapOf("screen" to "wallet")))

        val sent = repository.calls.single()
        assertEquals(1, sent.events.size)
        assertEquals("screen_view", sent.events.single().name)
        assertEquals(now.toString(), sent.events.single().occurredAt)
        assertEquals(mapOf("screen" to "wallet"), sent.events.single().metadata)
        assertTrue(sent.deviceId.isNotBlank())
    }

    @Test
    fun `a successfully sent event is removed from the queue`() = runTest {
        val queue = queue()

        queue.enqueue(AnalyticsQueuedEvent(name = "search"))

        assertEquals(0, database.analyticsEventDao().count())
    }

    @Test
    fun `a network failure keeps the event queued for the next attempt`() = runTest {
        repository.result = ApiResult.Failure(ApiError.NoConnection)
        val queue = queue()

        queue.enqueue(AnalyticsQueuedEvent(name = "search"))

        assertEquals(1, database.analyticsEventDao().count())
        assertEquals(1, repository.calls.size)
    }

    @Test
    fun `rate limiting also keeps the batch for later, it is not a poison payload`() = runTest {
        repository.result = ApiResult.Failure(ApiError.Http(429, null))
        val queue = queue()

        queue.enqueue(AnalyticsQueuedEvent(name = "search"))

        assertEquals(1, database.analyticsEventDao().count())
    }

    @Test
    fun `a structural validation error drops the batch instead of looping forever`() = runTest {
        repository.result = ApiResult.Failure(ApiError.Http(400, null))
        val queue = queue()

        queue.enqueue(AnalyticsQueuedEvent(name = "search"))

        assertEquals(0, database.analyticsEventDao().count())
    }

    @Test
    fun `enqueue also flushes whatever was stuck from a previous session`() = runTest {
        database.analyticsEventDao().insert(
            AnalyticsEventEntity(
                name = "search",
                occurredAt = now.minusSeconds(60).toString(),
                enqueuedAtEpochSecond = now.minusSeconds(60).epochSecond,
            ),
        )

        queue().enqueue(AnalyticsQueuedEvent(name = "screen_view"))

        val sent = repository.calls.single()
        assertEquals(listOf("search", "screen_view"), sent.events.map { it.name })
        assertEquals(0, database.analyticsEventDao().count())
    }

    @Test
    fun `events older than 30 days are trimmed before they are ever sent`() = runTest {
        val stale = now.minus(Duration.ofDays(31))
        database.analyticsEventDao().insert(
            AnalyticsEventEntity(
                name = "search",
                occurredAt = stale.toString(),
                enqueuedAtEpochSecond = stale.epochSecond,
            ),
        )

        queue().enqueue(AnalyticsQueuedEvent(name = "screen_view"))

        // Устаревшая строка отброшена молча, до сети — сервер бы её всё равно
        // не принял (`occurredAt` вне окна 30 суток).
        val sent = repository.calls.single()
        assertEquals(listOf("screen_view"), sent.events.map { it.name })
    }

    private fun queue() = DefaultAnalyticsEventQueue(
        dao = database.analyticsEventDao(),
        repository = repository,
        deviceIdStore = DeviceIdStore(
            PreferenceDataStoreFactory.create(
                produceFile = { File(temporaryFolder.root, "device.preferences_pb") },
            ),
        ),
        clock = Clock.fixed(now, ZoneOffset.UTC),
        json = NetworkFactory.json(),
    )

    private class FakeEventsRepository : AnalyticsEventsRepository {
        var result: ApiResult<Int> = ApiResult.Success(0)
        val calls = mutableListOf<Call>()

        override suspend fun send(deviceId: String, events: List<AnalyticsEventItemRequest>): ApiResult<Int> {
            calls += Call(deviceId, events)
            return result
        }

        data class Call(val deviceId: String, val events: List<AnalyticsEventItemRequest>)
    }
}
