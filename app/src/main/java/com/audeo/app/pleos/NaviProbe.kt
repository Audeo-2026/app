package com.audeo.app.pleos

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context

/**
 * NaviHelper SDK 연결 확인: 기본 내비 앱에 연결한 뒤 경로 상태를 요청합니다.
 * 결과는 요청의 반환값이 아니라 [NaviHelperEventListener] 콜백으로 옵니다.
 */
class NaviProbe(context: Context) : NaviHelperEventListener {

    private val helper = NaviHelper(context.applicationContext)
    private var onResult: ((String) -> Unit)? = null

    fun start(onResult: (String) -> Unit) {
        this.onResult = onResult
        helper.initialize(
            {
                helper.addListener(this)
                onResult("연결됨, 경로 상태 요청 중")
                helper.getRouteStateInfo()
            },
            { e -> onResult("초기화 실패: ${e.message}") },
        )
    }

    override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
        onResult?.invoke("응답 받음: 모드 ${routeStateInfo.naviMode}, 경로 옵션 ${routeStateInfo.routeOption}")
    }

    override fun onError(errorCode: NaviErrorCode) {
        onResult?.invoke("내비 오류: $errorCode")
    }

    fun release() {
        onResult = null
        runCatching {
            helper.removeListener(this)
            helper.release()
        }
    }
}
