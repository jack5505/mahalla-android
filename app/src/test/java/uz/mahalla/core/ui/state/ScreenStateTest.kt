package uz.mahalla.core.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uz.mahalla.core.result.ApiError
import uz.mahalla.core.result.ApiResult

class ScreenStateTest {

    @Test
    fun `successful list becomes content`() {
        val state = ApiResult.Success(listOf("a", "b")).toListScreenState()

        assertEquals(ScreenState.Content(listOf("a", "b")), state)
        assertEquals(listOf("a", "b"), state.dataOrNull())
    }

    @Test
    fun `empty list becomes empty state, not empty content`() {
        val state = ApiResult.Success(emptyList<String>()).toListScreenState()

        assertEquals(ScreenState.Empty, state)
        assertNull(state.dataOrNull())
    }

    @Test
    fun `failure carries the error to the retry screen`() {
        val state = ApiResult.Failure(ApiError.NoConnection).toListScreenState()

        assertEquals(ApiError.NoConnection, state.errorOrNull())
    }

    @Test
    fun `single object never becomes empty by default`() {
        val state: ScreenState<String> = ApiResult.Success("place").toScreenState()

        assertEquals(ScreenState.Content("place"), state)
    }

    @Test
    fun `map transforms content and keeps other states`() {
        assertEquals(
            ScreenState.Content(2),
            ScreenState.Content("ab").map(String::length),
        )
        assertEquals(ScreenState.Empty, ScreenState.Empty.map { it })
        assertEquals(ScreenState.Loading, ScreenState.Loading.map { it })

        val error: ScreenState<String> = ScreenState.Error(ApiError.Timeout)
        assertEquals(ApiError.Timeout, error.map { it }.errorOrNull())
    }

    @Test
    fun `loading is the only state showing a skeleton`() {
        assertTrue(ScreenState.Loading.isLoading)
        listOf(
            ScreenState.Empty,
            ScreenState.Error(ApiError.Timeout),
            ScreenState.Content(Unit),
        ).forEach { state ->
            assertTrue("$state не должен считаться загрузкой", !state.isLoading)
        }
    }
}
