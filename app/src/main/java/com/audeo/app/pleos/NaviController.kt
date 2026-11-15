package com.audeo.app.pleos

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.WaypointArrivedInfo
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
    /** 재탐색(requestReRoute)이 끝났어요. */
    data object ReRouteEnded : NaviEvent
    data class RouteOptionChanged(val option: String) : NaviEvent
    /** 경유지에 도착했어요(대체 경로를 다시 요청할 때 이미 지난 경유지는 빼요). */
    data class WaypointArrived(val name: String?) : NaviEvent
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

    /**
     * 내비 앱 화면을 앞으로 띄웁니다. 운전 중에는 진짜 내비가 주 화면이고, 오디는 그 위에 떠 있어요(AssistantOverlay).
     * NaviHelper는 내비 지도를 우리 앱 안에 그려 주지 않고, 내비 앱을 조종하고 안내 정보를 받기만 합니다.
     */
    fun showScreen(): Boolean = connected && runCatching {
        helper.requestNavigationScreen(NavigationScreenRequest.Foreground)
    }.isSuccess

    /**
     * 같은 목적지로 경로 재탐색. 목적지·경유지는 그대로예요(requestRoute로 새로 잡으면 경유지가 사라져요).
     * [option]이 RouteOption 이름(SHORT_TIME …)이면 그 옵션으로 바꾼 뒤 재탐색해요. 옵션만 바꾸면 내비가 경로를
     * 다시 계산하지 않아(에뮬레이터에서 확인) 콜백이 오지 않으므로 requestReRoute까지 불러요.
     */
    fun reroute(option: String?): Boolean = connected && runCatching {
        option?.let { name -> RouteOption.entries.firstOrNull { it.name == name } }?.let(helper::changeRouteOption)
        helper.requestReRoute()
    }.isSuccess

    /**
     * 목적지와 경유지(최대 2곳, 앞에서부터)를 함께 넣어 경로를 새로 요청해요. 재탐색 콜백이 오지 않을 때
     * (에뮬레이터 내비는 차가 경로 위에 없으면 재탐색 결과를 알려 주지 않아요) 경유지를 잃지 않고 경로를 다시 잡는 길이에요.
     */
    fun routeWithStops(destination: Place, stops: List<Place>): Boolean = connected && runCatching {
        if (stops.isEmpty()) {
            helper.requestRoute(routeInfo(destination))
        } else {
            val slots = listOf(WaypointIndex.FIRST, WaypointIndex.SECOND)
            helper.requestRouteWithWaypoints(
                RouteWithWaypointsRequest(
                    routeInfo(destination),
                    stops.take(slots.size).mapIndexed { i, p ->
                        RequestWaypointInfo(
                            latitude = p.latitude,
                            longitude = p.longitude,
                            poiName = p.name,
                            waypointIndex = slots[i],
                            poiId = "",
                            address = "",
                            poiSubId = "",
                        )
                    },
                ),
            )
        }
    }.isSuccess

    private fun routeInfo(place: Place) = RouteInfo(
        latitude = place.latitude,
        longitude = place.longitude,
        displayLatitude = null,
        displayLongitude = null,
        poiName = place.name,
        poiId = "",
        address = "",
        poiSubId = "",
    )

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

    override fun onReRouteEnded() = emit(NaviEvent.ReRouteEnded)

    override fun onRouteOptionChanged(routeOption: RouteOption) = emit(NaviEvent.RouteOptionChanged(routeOption.name))

    override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) = emit(NaviEvent.WaypointArrived(waypointArrivedInfo.name))

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
