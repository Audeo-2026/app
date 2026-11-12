package com.audeo.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.audeo.app.pleos.NaviProbe
import com.audeo.app.pleos.ServiceStatus
import com.audeo.app.pleos.VehicleProbe
import com.audeo.app.pleos.checkPleosServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MainUiState(
    val services: List<ServiceStatus> = emptyList(),
    val vehicleResult: String = "확인 중…",
    val naviResult: String = "확인 중…",
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private var vehicle: VehicleProbe? = null
    private var navi: NaviProbe? = null

    init {
        refresh()
    }

    fun refresh() {
        releaseProbes()
        val app = getApplication<Application>()
        _state.value = MainUiState(services = checkPleosServices(app))

        vehicle = runCatching { VehicleProbe(app) }
            .onFailure { e -> _state.update { it.copy(vehicleResult = "SDK 생성 실패: ${e.message}") } }
            .getOrNull()
            ?.also { probe -> probe.start { result -> _state.update { it.copy(vehicleResult = result) } } }

        navi = runCatching { NaviProbe(app) }
            .onFailure { e -> _state.update { it.copy(naviResult = "SDK 생성 실패: ${e.message}") } }
            .getOrNull()
            ?.also { probe -> probe.start { result -> _state.update { it.copy(naviResult = result) } } }
    }

    private fun releaseProbes() {
        vehicle?.release()
        navi?.release()
        vehicle = null
        navi = null
    }

    override fun onCleared() {
        releaseProbes()
    }
}
