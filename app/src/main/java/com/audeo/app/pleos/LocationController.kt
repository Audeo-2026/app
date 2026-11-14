package com.audeo.app.pleos

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
import android.content.Context
import com.audeo.app.audeo.Place

/**
 * FusedLocation SDK: 현재 위치를 읽고, 픽업 지점에 가까워지면 알려 줍니다(만남 직전 안내).
 * FUSED_LOCATION은 dangerous 권한이라 실행 중 허락을 받은 뒤에 [start]를 부릅니다.
 */
class LocationController(context: Context) {

    private val fused = FusedLocation(context.applicationContext)

    @Volatile private var ready = false
    private var alertId: String? = null

    fun start(onLocation: (LocationData) -> Unit, onError: (String) -> Unit) {
        try {
            fused.initialize()
            ready = true
        } catch (e: Exception) {
            onError("위치 초기화 실패: ${e.message}")
            return
        }
        runCatching {
            fused.getLastKnownLocation(object : FusedLocationListener {
                override fun onChanged(location: LocationData?) {
                    location?.let(onLocation)
                }
                override fun onError(exception: Exception) = onError("위치 읽기 실패: ${exception.message}")
            })
        }.onFailure { e -> onError("위치 읽기 실패: ${e.message}") }
    }

    /** 마지막 위치를 다시 읽습니다. 서버에 상태를 보고할 때 씁니다. */
    fun latest(onLocation: (LocationData) -> Unit) {
        if (!ready) return
        runCatching {
            fused.getLastKnownLocation(object : FusedLocationListener {
                override fun onChanged(location: LocationData?) {
                    location?.let(onLocation)
                }

                override fun onError(exception: Exception) = Unit
            })
        }
    }

    /** [place] 반경 [radiusMeters] 안으로 들어오면 [onEnter]를 부릅니다. */
    fun watchArrival(place: Place, radiusMeters: Float, onEnter: () -> Unit, onError: (String) -> Unit) {
        if (!ready) return
        clearArrival()
        runCatching {
            fused.addProximityAlert(
                ProximityAlertRequest(place.latitude, place.longitude, radiusMeters, setOf(ProximityTransition.ENTER)),
                object : ProximityAlertListener {
                    override fun onRegistered(id: String) {
                        alertId = id
                    }

                    override fun onTransition(event: ProximityAlertEvent) = onEnter()
                    override fun onError(exception: Exception) = onError("근접 알림 실패: ${exception.message}")
                },
            )
        }.onFailure { e -> onError("근접 알림 실패: ${e.message}") }
    }

    fun clearArrival() {
        alertId?.let { id -> runCatching { fused.removeProximityAlert(id) } }
        alertId = null
    }

    fun release() {
        clearArrival()
        if (ready) runCatching { fused.release() }
        ready = false
    }
}
