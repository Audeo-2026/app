package com.audeo.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.audeo.app.pleos.NaviProbe
import com.audeo.app.pleos.NaviTest
import com.audeo.app.pleos.ServiceStatus
import com.audeo.app.pleos.VehicleProbe
import com.audeo.app.pleos.checkPleosServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class MainUiState(
    val services: List<ServiceStatus> = emptyList(),
    val vehicleResult: String = "확인 중…",
    val naviResult: String = "확인 중…",
    /** 내비 시험 버튼과 내비 이벤트 기록. 최신이 위. */
    val naviEvents: List<String> = emptyList(),
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
            ?.also { probe ->
                probe.start(
                    onResult = { result -> _state.update { it.copy(naviResult = result) } },
                    onEvent = ::addNaviEvent,
                )
            }
    }

    fun runNaviTest(test: NaviTest) {
        navi?.run(test) ?: addNaviEvent("${test.label}: NaviHelper 없음")
    }

    private fun addNaviEvent(message: String) {
        val time = LocalTime.now().format(TIME_FORMAT)
        _state.update { it.copy(naviEvents = (listOf("$time  $message") + it.naviEvents).take(12)) }
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

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
