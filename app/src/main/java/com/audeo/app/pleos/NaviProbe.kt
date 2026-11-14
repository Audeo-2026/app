package com.audeo.app.pleos

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteOverviewShowInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log

/** 내비 화면 시험 버튼. 경로 요청과 내비 화면 전환을 하나씩 눌러 동작을 확인합니다. */
enum class NaviTest(val label: String) {
    ROUTE_WITH_WAYPOINT("경로 요청 (판교역 경유)"),
    OVERVIEW_ROUTE("경로 전체 보기"),
    FOREGROUND("지도 화면"),
    CURRENT_LOCATION("현재 위치로"),
    SEARCH_PLACE("장소 검색: 카페"),
    CANCEL_ROUTE("경로 취소"),
}

/**
 * NaviHelper SDK 연결 확인: 기본 내비 앱에 연결한 뒤 경로 상태를 요청합니다.
 * 결과는 요청의 반환값이 아니라 [NaviHelperEventListener] 콜백으로 옵니다.
 */
class NaviProbe(context: Context) : NaviHelperEventListener {

    private val helper = NaviHelper(context.applicationContext)
    private var onResult: ((String) -> Unit)? = null
    private var onEvent: ((String) -> Unit)? = null
    private var connected = false

    fun start(onResult: (String) -> Unit, onEvent: (String) -> Unit) {
        this.onResult = onResult
        this.onEvent = onEvent
        helper.initialize(
            {
                connected = true
                helper.addListener(this)
                onResult("연결됨, 경로 상태 요청 중")
                helper.getRouteStateInfo()
            },
            { e -> onResult("초기화 실패: ${e.message}") },
        )
    }

    fun run(test: NaviTest) {
        if (!connected) {
            event("${test.label}: 아직 내비에 연결되지 않음")
            return
        }
        runCatching {
            when (test) {
                NaviTest.ROUTE_WITH_WAYPOINT -> helper.requestRouteWithWaypoints(
                    RouteWithWaypointsRequest(
                        destination = RouteInfo(
                            latitude = 37.48544722,
                            longitude = 127.03636666,
                            displayLatitude = null,
                            displayLongitude = null,
                            poiName = "강남파이낸스센터",
                            poiId = "",
                            address = "",
                            poiSubId = "",
                        ),
                        waypoints = listOf(
                            RequestWaypointInfo(
                                latitude = 37.39467,
                                longitude = 127.11120,
                                poiName = "판교역",
                                waypointIndex = WaypointIndex.FIRST,
                                poiId = "",
                                address = "",
                                poiSubId = "",
                            ),
                        ),
                    ),
                )
                NaviTest.OVERVIEW_ROUTE -> helper.requestNavigationScreen(
                    NavigationScreenRequest.OverviewRoute(RouteOverviewShowInfo()),
                )
                NaviTest.FOREGROUND -> helper.requestNavigationScreen(NavigationScreenRequest.Foreground)
                NaviTest.CURRENT_LOCATION -> helper.requestNavigationScreen(NavigationScreenRequest.MoveToCurrentLocationOnMap)
                NaviTest.SEARCH_PLACE -> helper.requestNavigationScreen(NavigationScreenRequest.SearchPlace("카페"))
                NaviTest.CANCEL_ROUTE -> helper.cancelRoute()
            }
        }
            .onSuccess { event("${test.label}: 요청 보냄") }
            .onFailure { e -> event("${test.label}: 실패 ${e.javaClass.simpleName} ${e.message}") }
    }

    override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
        onResult?.invoke("응답 받음: 모드 ${routeStateInfo.naviMode}, 경로 옵션 ${routeStateInfo.routeOption}")
    }

    override fun onRoutingCompleted() = event("onRoutingCompleted")

    override fun onRouteStarted(routeStartInfo: RouteStartInfo) = event(
        "onRouteStarted: ${routeStartInfo.name}, ${routeStartInfo.duration}초, ${routeStartInfo.distance}m",
    )

    override fun onRouteCancelled() = event("onRouteCancelled")

    override fun onNavigationStatus(status: NavigationStatus) = event("onNavigationStatus: $status")

    override fun onError(errorCode: NaviErrorCode) {
        onResult?.invoke("내비 오류: $errorCode")
        event("onError: $errorCode")
    }

    private fun event(message: String) {
        Log.d(TAG, message)
        onEvent?.invoke(message)
    }

    fun release() {
        onResult = null
        onEvent = null
        connected = false
        runCatching {
            helper.removeListener(this)
            helper.release()
        }
    }

    private companion object {
        const val TAG = "AudeoNavi"
    }
}
