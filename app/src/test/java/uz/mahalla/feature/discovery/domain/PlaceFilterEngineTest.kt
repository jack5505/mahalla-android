package uz.mahalla.feature.discovery.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.testutil.place

/**
 * Фильтрация и сортировка выдачи (эпик 4.3).
 *
 * Правила проверяются здесь, а не на экране: те же функции применяются к
 * ответу сервера и к кэшу Room, и расхождение между онлайном и офлайном
 * начиналось бы именно с них.
 */
class PlaceFilterEngineTest {

    @Test
    fun `category filter keeps only the selected categories`() {
        val places = listOf(
            place("food", category = PlaceCategory.Food),
            place("pharmacy", category = PlaceCategory.Pharmacy),
            place("cinema", category = PlaceCategory.Cinema),
        )

        val result = PlaceFilterEngine.apply(
            places,
            DiscoveryFilters(categories = setOf(PlaceCategory.Food, PlaceCategory.Cinema)),
        )

        assertEquals(setOf("food", "cinema"), result.map(Place::id).toSet())
    }

    @Test
    fun `remote results keep everything the server matched`() {
        // Сервер ищет по описанию, меню и тегам — совпадения по названию может
        // не быть вовсе. Локальный фильтр вырезал бы всю выдачу.
        val places = listOf(place("a", name = "Chorsu", rating = 0.0, reviewCount = 0))

        val result = PlaceFilterEngine.applyRemote(
            places,
            DiscoveryFilters(query = "osh", minRating = 4.0, openNowOnly = true),
        )

        assertEquals(listOf("a"), result.map(Place::id))
    }

    @Test
    fun `remote results are still sorted the same way as the cache`() {
        val places = listOf(place("far", distanceMeters = 900), place("near", distanceMeters = 100))

        val result = PlaceFilterEngine.applyRemote(places, DiscoveryFilters())

        assertEquals(listOf("near", "far"), result.map(Place::id))
    }

    @Test
    fun `remote results drop only the categories that did not fit the request`() {
        val places = listOf(
            place("food", category = PlaceCategory.Food),
            place("pharmacy", category = PlaceCategory.Pharmacy),
            // Категория, которой ещё нет в приложении: скрывать её значит
            // прятать новые разделы каталога до следующего релиза.
            place("other", category = PlaceCategory.Other),
        )

        val result = PlaceFilterEngine.applyRemote(
            places,
            DiscoveryFilters(categories = setOf(PlaceCategory.Pharmacy)),
        )

        assertEquals(setOf("pharmacy", "other"), result.map(Place::id).toSet())
    }

    @Test
    fun `unknown category never matches a category filter`() {
        val places = listOf(place("other", category = PlaceCategory.Other))

        val result = PlaceFilterEngine.apply(
            places,
            DiscoveryFilters(categories = setOf(PlaceCategory.Food)),
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `open now filter drops closed places`() {
        val places = listOf(place("open"), place("closed", isOpenNow = false))

        val result = PlaceFilterEngine.apply(places, DiscoveryFilters(openNowOnly = true))

        assertEquals(listOf("open"), result.map(Place::id))
    }

    @Test
    fun `distance filter is inclusive on the boundary`() {
        val places = listOf(
            place("exactly", distanceMeters = 1_000),
            place("further", distanceMeters = 1_001),
        )

        val result = PlaceFilterEngine.apply(places, DiscoveryFilters(maxDistanceMeters = 1_000))

        assertEquals(listOf("exactly"), result.map(Place::id))
    }

    @Test
    fun `place without reviews does not pass a rating threshold`() {
        // 0.0 — это «нет оценок», а не «очень плохо»: подставлять такое место
        // под фильтр «от 3 звёзд» нельзя.
        val unrated = place("unrated", rating = 0.0, reviewCount = 0)
        val rated = place("rated", rating = 4.2)

        val result = PlaceFilterEngine.apply(
            listOf(unrated, rated),
            DiscoveryFilters(minRating = 3.0),
        )

        assertEquals(listOf("rated"), result.map(Place::id))
    }

    @Test
    fun `query matches name and address ignoring case`() {
        val byName = place("name", name = "Choyxona Registon")
        val byAddress = place("address", name = "Kafe", address = "Registon ko'chasi 5")
        val other = place("other", name = "Dorixona", address = "Amir Temur 1")

        val result = PlaceFilterEngine.apply(
            listOf(byName, byAddress, other),
            DiscoveryFilters(query = "reGISton"),
        )

        assertEquals(setOf("name", "address"), result.map(Place::id).toSet())
    }

    @Test
    fun `apostrophe style does not break the search`() {
        // В узбекской латинице апостроф пишут четырьмя разными символами —
        // без нормализации «oʻzbek» не находил бы «o'zbek».
        val target = place("p", name = "O'zbek taomlari")

        assertTrue(PlaceFilterEngine.matchesQuery(target, "oʻzbek"))
        assertTrue(PlaceFilterEngine.matchesQuery(target, "o‘zbek"))
        assertTrue(PlaceFilterEngine.matchesQuery(target, "o'zbek"))
    }

    @Test
    fun `blank query matches everything`() {
        val target = place("p")

        assertTrue(PlaceFilterEngine.matchesQuery(target, ""))
        assertTrue(PlaceFilterEngine.matchesQuery(target, "   "))
    }

    @Test
    fun `filters combine as an intersection`() {
        val match = place("match", category = PlaceCategory.Food, distanceMeters = 300, rating = 4.8)
        val wrongCategory = place("cat", category = PlaceCategory.Cinema, distanceMeters = 300)
        val tooFar = place("far", category = PlaceCategory.Food, distanceMeters = 4_000)
        val closed = place("closed", category = PlaceCategory.Food, isOpenNow = false)

        val result = PlaceFilterEngine.apply(
            listOf(match, wrongCategory, tooFar, closed),
            DiscoveryFilters(
                categories = setOf(PlaceCategory.Food),
                maxDistanceMeters = 1_000,
                minRating = 4.0,
                openNowOnly = true,
            ),
        )

        assertEquals(listOf("match"), result.map(Place::id))
    }

    @Test
    fun `distance sort is ascending`() {
        val places = listOf(
            place("far", distanceMeters = 900),
            place("near", distanceMeters = 100),
            place("mid", distanceMeters = 400),
        )

        val result = PlaceFilterEngine.apply(places, DiscoveryFilters(sort = PlaceSort.Distance))

        assertEquals(listOf("near", "mid", "far"), result.map(Place::id))
    }

    @Test
    fun `rating sort is descending and breaks ties by review count`() {
        val places = listOf(
            place("few", rating = 4.8, reviewCount = 3),
            place("many", rating = 4.8, reviewCount = 300),
            place("low", rating = 3.1),
        )

        val result = PlaceFilterEngine.apply(places, DiscoveryFilters(sort = PlaceSort.Rating))

        assertEquals(listOf("many", "few", "low"), result.map(Place::id))
    }

    @Test
    fun `relevance puts an exact name first then prefix then substring`() {
        val places = listOf(
            place("substring", name = "Katta Osh markazi", distanceMeters = 100),
            place("exact", name = "Osh", distanceMeters = 900),
            place("prefix", name = "Osh markazi", distanceMeters = 800),
        )

        val result = PlaceFilterEngine.apply(places, DiscoveryFilters(query = "osh"))

        assertEquals(listOf("exact", "prefix", "substring"), result.map(Place::id))
    }

    @Test
    fun `relevance with an empty query degrades to distance`() {
        val places = listOf(
            place("far", distanceMeters = 900),
            place("near", distanceMeters = 100),
        )

        val result = PlaceFilterEngine.apply(places, DiscoveryFilters())

        assertEquals(listOf("near", "far"), result.map(Place::id))
    }

    @Test
    fun `equal places keep a stable order between runs`() {
        // Без запасного ключа порядок одинаковых элементов зависит от входа, и
        // список «прыгает» при каждом обновлении.
        val a = place("a", distanceMeters = 500, rating = 4.5)
        val b = place("b", distanceMeters = 500, rating = 4.5)

        val direct = PlaceFilterEngine.apply(listOf(a, b), DiscoveryFilters(sort = PlaceSort.Rating))
        val reversed = PlaceFilterEngine.apply(listOf(b, a), DiscoveryFilters(sort = PlaceSort.Rating))

        assertEquals(direct.map(Place::id), reversed.map(Place::id))
        assertEquals(listOf("a", "b"), direct.map(Place::id))
    }

    @Test
    fun `default filters change nothing but the order`() {
        val places = listOf(place("b", distanceMeters = 800), place("a", distanceMeters = 100))

        val result = PlaceFilterEngine.apply(places, DiscoveryFilters())

        assertEquals(2, result.size)
        assertFalse(result.first().id == "b")
    }
}
