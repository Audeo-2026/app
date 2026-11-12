package com.audeo.app.pleos

import ai.pleos.playground.vehicle.Vehicle
import android.content.Context

/**
 * Vehicle SDK 연결 확인: 세션을 열고 누적 주행거리를 한 번 읽습니다.
 * 실제 기능을 만들 때도 같은 순서(initialize → API 호출 → release)를 따릅니다.
 */
class VehicleProbe(context: Context) {

    private val vehicle = Vehicle(context.applicationContext)
    private var initialized = false

    fun start(onResult: (String) -> Unit) {
        try {
            vehicle.initialize()
            initialized = true
        } catch (e: Exception) {
            onResult("초기화 실패: ${e.message}")
            return
        }

        vehicle.getOdometer().getCurrentDistanceDriven(
            { km -> onResult("누적 주행거리 ${km ?: "-"} km") },
            { e -> onResult("주행거리 읽기 실패: ${e.message}") },
        )
    }

    fun release() {
        if (!initialized) return
        runCatching { vehicle.release() }
        initialized = false
    }
}
