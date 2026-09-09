package uz.mahalla.testutil

import kotlinx.coroutines.CompletableDeferred
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

    /**
     * Ответ по самому курсору — когда набора источников не хватает, потому что
     * вторую страницу надо отличить от третьей: догрузка нескольких страниц
     * подряд спрашивает один и тот же источник (issue #143).
     */
    var pageFeeds: ((Map<ActivitySource, Int>) -> ActivityFeed?)? = null

    /**
     * Ответ можно задержать: пока `gate` не завершён, запрос висит в полёте —
     * тест успевает проверить, что экран делает **во время** загрузки. Сам
     * запрос при этом уже записан в [requests].
     */
    var gate: CompletableDeferred<Unit>? = null

    /**
     * Предохранитель: догрузка, потерявшая условие остановки, крутит цикл, в
     * котором нет ни одной настоящей приостановки — виртуальные часы `runTest`
     * в такой цикл не вклиниваются, и тест не падает по таймауту, а **висит**.
     * Упасть с внятным текстом лучше, чем занять раннер до его собственного
     * лимита.
     */
    var maxRequests: Int = 100

    /** Что и с какими страницами спрашивали — по порядку вызовов. */
    val requests = mutableListOf<Map<ActivitySource, Int>>()

    override suspend fun feed(pages: Map<ActivitySource, Int>, size: Int): ActivityFeed {
        requests += pages
        check(requests.size <= maxRequests) {
            "фейк получил ${requests.size} запросов подряд — догрузка не останавливается"
        }
        gate?.await()
        val feed = pageFeeds?.invoke(pages) ?: feeds[pages.keys] ?: defaultFeed
        // `requested` заполняем сами: тесты задают только полезную часть
        // ответа, а без этого поля `isTotalFailure` всегда ложен.
        return feed.copy(requested = pages.keys)
    }
}
