package uz.mahalla.feature.food.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uz.mahalla.data.db.MahallaDatabase
import uz.mahalla.feature.food.domain.CartCalculator
import uz.mahalla.feature.food.domain.CartLine
import uz.mahalla.testutil.cartLine

/**
 * Черновик корзины (эпик 5.2) на настоящей Room: ключ строки и количество — то,
 * из-за чего корзина после перезапуска приложения может оказаться не той, что
 * была.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CartRepositoryTest {

    private lateinit var database: MahallaDatabase
    private lateinit var repository: DefaultCartRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MahallaDatabase::class.java,
        ).build()
        repository = DefaultCartRepository(database.cartDraftDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `the same dish with the same options increases the quantity`() = runTest {
        add(cartLine("osh"))
        add(cartLine("osh"))

        val cart = repository.snapshot(PLACE_ID)
        assertEquals(1, cart.lines.size)
        assertEquals(2, cart.lines.single().quantity)
    }

    @Test
    fun `the same dish with different options makes a second line`() = runTest {
        add(cartLine("osh"))
        add(cartLine("osh", optionIds = setOf("cheese"), unitPriceSum = 35_000))

        assertEquals(2, repository.snapshot(PLACE_ID).lines.size)
    }

    @Test
    fun `options survive a round trip through the database`() = runTest {
        add(cartLine("osh", optionIds = setOf("egg", "cheese")).copy(optionsLabel = "Cheese, Egg"))

        val line = repository.snapshot(PLACE_ID).lines.single()
        assertEquals(setOf("cheese", "egg"), line.optionIds)
        assertEquals("Cheese, Egg", line.optionsLabel)
        assertEquals(CartCalculator.lineId("osh", setOf("cheese", "egg")), line.id)
    }

    @Test
    fun `the place name lives with the draft`() = runTest {
        // Корзину показывают до загрузки меню и без сети, а в ответе меню
        // названия заведения нет вовсе.
        add(cartLine("osh"))

        assertEquals("Osh markazi", repository.cart(PLACE_ID).first().placeName)
    }

    @Test
    fun `zero quantity removes the line`() = runTest {
        add(cartLine("osh"))

        repository.setQuantity(PLACE_ID, CartCalculator.lineId("osh", emptySet()), 0)

        assertEquals(emptyList<CartLine>(), repository.snapshot(PLACE_ID).lines)
    }

    @Test
    fun `quantity is capped instead of growing without a limit`() = runTest {
        add(cartLine("osh"))

        repository.setQuantity(PLACE_ID, CartCalculator.lineId("osh", emptySet()), 500)

        assertEquals(
            CartCalculator.MAX_QUANTITY,
            repository.snapshot(PLACE_ID).lines.single().quantity,
        )
    }

    @Test
    fun `changing the quantity of a missing line does nothing`() = runTest {
        repository.setQuantity(PLACE_ID, "ghost", 3)

        assertEquals(emptyList<CartLine>(), repository.snapshot(PLACE_ID).lines)
    }

    @Test
    fun `the active place is the one with a started draft`() = runTest {
        assertNull(repository.activePlaceId())

        add(cartLine("osh"))

        assertEquals(PLACE_ID, repository.activePlaceId())
    }

    @Test
    fun `clearing one place does not touch another`() = runTest {
        add(cartLine("osh"))
        repository.add("place-2", "Somsa uyi", cartLine("somsa"))

        repository.clear(PLACE_ID)

        assertEquals(emptyList<CartLine>(), repository.snapshot(PLACE_ID).lines)
        assertEquals(1, repository.snapshot("place-2").lines.size)
    }

    @Test
    fun `replace swaps the whole draft, including the draft of another place`() = runTest {
        // Повтор заказа: прежний черновик исчезает вместе с появлением нового,
        // одной транзакцией — а не «сначала почистить, потом добавить».
        repository.add("place-2", "Somsa uyi", cartLine("somsa"))

        repository.replace(PLACE_ID, "Osh markazi", listOf(cartLine("osh", quantity = 2)))

        assertEquals(emptyList<CartLine>(), repository.snapshot("place-2").lines)
        val cart = repository.snapshot(PLACE_ID)
        assertEquals(listOf("osh"), cart.lines.map(CartLine::itemId))
        assertEquals(2, cart.lines.single().quantity)
        assertEquals("Osh markazi", cart.placeName)
    }

    private suspend fun add(line: CartLine) = repository.add(PLACE_ID, "Osh markazi", line)

    private companion object {
        const val PLACE_ID = "place-1"
    }
}
