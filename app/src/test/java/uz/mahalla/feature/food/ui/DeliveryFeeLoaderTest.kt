package uz.mahalla.feature.food.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult
import uz.mahalla.testutil.FakeDeliveryFeeRepository

/**
 * Загрузчик стоимости доставки (issue #179): когда спрашивать и что показывать
 * между вопросом и ответом.
 *
 * Проверяется здесь, а не только через две ViewModel: правило одно на оба
 * экрана, и цена ошибки — деньги на экране, разошедшиеся с чеком.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeliveryFeeLoaderTest {

    private val repository = FakeDeliveryFeeRepository()
    private val fees = mutableListOf<Long?>()

    @Test
    fun `the first fee is asked without waiting for the debounce`() = runTest {
        // Сумму человек читает сразу, как открыл корзину: полсекунды пустого
        // итога — не то же, что полсекунды в поиске.
        repository.fee = ApiResult.Success(100)
        val loader = loader()

        loader.refresh(itemsSum = 50_000, needed = true)
        // Одна миллисекунда, а не `DEBOUNCE_MS`: первый запрос обязан уйти
        // сразу.
        advanceTimeBy(1)
        runCurrent()

        assertEquals(listOf(50_000L), repository.requestedSums)
        assertEquals(100L, fees.last())
    }

    @Test
    fun `a burst of changes costs one request, for the last sum`() = runTest {
        repository.fee = ApiResult.Success(100)
        val loader = loader()
        loader.refresh(itemsSum = 10_000, needed = true)
        settle()
        repository.requestedSums.clear()

        loader.refresh(itemsSum = 20_000, needed = true)
        loader.refresh(itemsSum = 30_000, needed = true)
        loader.refresh(itemsSum = 40_000, needed = true)
        settle()

        assertEquals(listOf(40_000L), repository.requestedSums)
    }

    @Test
    fun `the same sum is not asked twice`() = runTest {
        // Доставка зависит только от суммы позиций: перерисовка экрана с той
        // же корзиной — не повод для запроса.
        repository.fee = ApiResult.Success(100)
        val loader = loader()

        loader.refresh(itemsSum = 50_000, needed = true)
        settle()
        loader.refresh(itemsSum = 50_000, needed = true)
        settle()

        assertEquals(listOf(50_000L), repository.requestedSums)
    }

    @Test
    fun `a changed sum drops the old fee before asking for the new one`() = runTest {
        // Иначе корзина, перешедшая порог бесплатной доставки, полсекунды
        // показывает прежние 100 сум — ровно то расхождение с чеком, из-за
        // которого задача и появилась.
        repository.fee = ApiResult.Success(100)
        val loader = loader()
        loader.refresh(itemsSum = 1_800, needed = true)
        settle()
        assertEquals(100L, fees.last())
        repository.fee = ApiResult.Success(0)

        loader.refresh(itemsSum = 3_600, needed = true)

        assertEquals(null, fees.last())
        settle()
        assertEquals(0L, fees.last())
    }

    @Test
    fun `pickup neither asks nor keeps a fee`() = runTest {
        repository.fee = ApiResult.Success(100)
        val loader = loader()
        loader.refresh(itemsSum = 50_000, needed = true)
        settle()
        repository.requestedSums.clear()

        loader.refresh(itemsSum = 50_000, needed = false)
        settle()

        assertEquals(emptyList<Long>(), repository.requestedSums)
        assertEquals(null, fees.last())
    }

    @Test
    fun `coming back to delivery asks again`() = runTest {
        repository.fee = ApiResult.Success(100)
        val loader = loader()
        loader.refresh(itemsSum = 50_000, needed = true)
        settle()
        loader.refresh(itemsSum = 50_000, needed = false)
        repository.requestedSums.clear()

        loader.refresh(itemsSum = 50_000, needed = true)
        settle()

        assertEquals(listOf(50_000L), repository.requestedSums)
        assertEquals(100L, fees.last())
    }

    @Test
    fun `an empty cart does not spend a request`() = runTest {
        val loader = loader()

        loader.refresh(itemsSum = 0, needed = true)
        settle()

        assertEquals(emptyList<Long>(), repository.requestedSums)
        assertEquals(listOf<Long?>(null), fees)
    }

    @Test
    fun `a refused request forgets the sum so the next change retries`() = runTest {
        repository.fee = ApiResult.Failure(ApiError.NoConnection)
        val loader = loader()
        loader.refresh(itemsSum = 50_000, needed = true)
        settle()
        assertEquals(null, fees.last())

        // Та же сумма после отказа — не «уже спрашивали».
        loader.refresh(itemsSum = 50_000, needed = true)
        settle()

        assertEquals(listOf(50_000L, 50_000L), repository.requestedSums)
    }

    /**
     * Прокрутить дебаунс и дать запросу дойти.
     *
     * Именно `advanceTimeBy`, а не `advanceUntilIdle`: загрузчик живёт в
     * `backgroundScope` (у него скоуп извне, как `viewModelScope` в
     * ViewModel), а `advanceUntilIdle` останавливается, как только кончилась
     * работа самого теста, и фоновую корутину не запускает вовсе.
     */
    private fun TestScope.settle() {
        advanceTimeBy(DeliveryFeeLoader.DEBOUNCE_MS + 1)
        runCurrent()
    }

    /** Скоуп теста: у загрузчика он приходит извне (`viewModelScope`). */
    private fun TestScope.loader() = DeliveryFeeLoader(
        repository = repository,
        scope = backgroundScope,
        onFee = fees::add,
    )
}
