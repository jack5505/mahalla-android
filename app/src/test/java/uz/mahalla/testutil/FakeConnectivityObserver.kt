package uz.mahalla.testutil

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import uz.mahalla.data.network.ConnectivityObserver

class FakeConnectivityObserver(initiallyConnected: Boolean = true) : ConnectivityObserver {

    private val state = MutableStateFlow(initiallyConnected)

    override val isConnected: Flow<Boolean> = state

    fun setConnected(connected: Boolean) {
        state.value = connected
    }
}
