package uz.mahalla.testutil

import uz.mahalla.feature.activity.data.ActivityRepository
import uz.mahalla.feature.activity.domain.ActivityFeed
import uz.mahalla.feature.activity.domain.ActivitySource

/**
 * «Мои активности» в памяти (issue #73): ViewModel проверяется без
 * MockWebServer.
 *
 * Ответ задаётся по **набору запрошенных источников**, а не по одному номеру
 * страницы: догрузка спрашивает уже не всех, и без этого её нельзя отличить от
 * повторной загрузки первой страницы.
 */
class FakeActivityRepository : ActivityRepository {

    /** Ответ на конкретный набор источников; ключ — множество запрошенных. */
    val feeds: MutableMap<Set<ActivitySource>, ActivityFeed> = mutableMapOf()

    var defaultFeed: ActivityFeed = ActivityFeed()

    /** Что и с какими страницами спрашивали — по порядку вызовов. */
    val requests = mutableListOf<Map<ActivitySource, Int>>()

    override suspend fun feed(pages: Map<ActivitySource, Int>, size: Int): ActivityFeed {
        requests += pages
        val feed = feeds[pages.keys] ?: defaultFeed
        // `requested` заполняем сами: тесты задают только полезную часть
        // ответа, а без этого поля `isTotalFailure` всегда ложен.
        return feed.copy(requested = pages.keys)
    }
}
