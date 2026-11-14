package com.audeo.app.pleos

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import com.audeo.app.audeo.Place

/** NaviHelper 이벤트 중 Audeo가 쓰는 것. */
sealed interface NaviEvent {
    data object Connected : NaviEvent
    data class ConnectFailed(val message: String) : NaviEvent
    data class RouteStarted(val name: String?, val seconds: Int, val meters: Int) : NaviEvent
    data object RoutingCompleted : NaviEvent
    data object WaypointChanged : NaviEvent
    data class Driving(val seconds: Int?, val meters: Int?) : NaviEvent
    data object Arrived : NaviEvent
    data object Cancelled : NaviEvent
    data class Error(val code: String) : NaviEvent
}

/**
 * 기본 내비 앱에 목적지·경유지를 요청합니다. 요청의 결과는 반환값이 아니라 콜백(이벤트)으로 옵니다.
 * Audeo는 운전자가 승인한 뒤에만 이 요청을 보냅니다.
 */
class NaviController(context: Context) : NaviHelperEventListener {

    private val helper = NaviHelper(context.applicationContext)
    private var listener: ((NaviEvent) -> Unit)? = null

    @Volatile
    var connected = false
        private set

    fun connect(onEvent: (NaviEvent) -> Unit) {
        listener = onEvent
        runCatching {
            helper.initialize(
                {
                    connected = true
                    helper.addListener(this)
                    onEvent(NaviEvent.Connected)
                },
                { e -> onEvent(NaviEvent.ConnectFailed(e.message ?: e.javaClass.simpleName)) },
            )
        }.onFailure { e -> onEvent(NaviEvent.ConnectFailed(e.message ?: e.javaClass.simpleName)) }
    }

    /** 목적지를 [place]로 경로 요청. 연결 전이거나 요청이 실패하면 false. */
    fun routeTo(place: Place): Boolean = connected && runCatching {
        helper.requestRoute(
            RouteInfo(
                latitude = place.latitude,
                longitude = place.longitude,
                displayLatitude = null,
                displayLongitude = null,
                poiName = place.name,
                poiId = "",
                address = "",
                poiSubId = "",
            ),
        )
    }.isSuccess

    /** [place]를 첫 번째 경유지로 추가. */
    fun addStop(place: Place): Boolean = connected && runCatching {
        helper.addWaypoint(
            RequestWaypointInfo(
                latitude = place.latitude,
                longitude = place.longitude,
                poiName = place.name,
                waypointIndex = WaypointIndex.FIRST,
                poiId = "",
                address = "",
                poiSubId = "",
            ),
        )
    }.isSuccess

    override fun onRouteStarted(routeStartInfo: RouteStartInfo) =
        emit(NaviEvent.RouteStarted(routeStartInfo.name, routeStartInfo.duration, routeStartInfo.distance))

    override fun onRoutingCompleted() = emit(NaviEvent.RoutingCompleted)

    override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) = emit(NaviEvent.WaypointChanged)

    override fun onDrivingInfo(drivingInfo: DrivingInfo) {
        val destination = drivingInfo.destination
        emit(NaviEvent.Driving(destination?.duration, destination?.distance))
    }

    override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) = emit(NaviEvent.Arrived)

    override fun onRouteCancelled() = emit(NaviEvent.Cancelled)

    override fun onError(errorCode: NaviErrorCode) = emit(NaviEvent.Error(errorCode.toString()))

    private fun emit(event: NaviEvent) {
        listener?.invoke(event)
    }

    fun release() {
        listener = null
        if (connected) runCatching { helper.removeListener(this) }
        runCatching { helper.release() }
        connected = false
    }
}
