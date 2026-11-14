package com.audeo.app.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/** 이 차에서 지원하지 않는 기능. 제어하지 않고 직접 조작을 안내합니다. */
class UnsupportedFeatureException(message: String) : Exception(message)

/**
 * Vehicle SDK로 공조 온도와 시트 열선을 조절합니다.
 * 지원 여부(capabilities)를 먼저 확인하고, 지원되는 구역에만 요청합니다.
 * 결과는 콜백으로 오며, SDK가 성공을 돌려줬을 때만 성공으로 봅니다.
 */
class VehicleController(context: Context) {

    private val vehicle = Vehicle(context.applicationContext)

    @Volatile
    private var ready = false

    /** 초기화. 실패하면 이유를 돌려줍니다. */
    fun start(): String? = try {
        vehicle.initialize()
        ready = true
        null
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    /**
     * 서버에 올릴 지원 범위.
     * 예: {"hvac": {"temperature": ["ROW_1_LEFT"], "current_celsius": "21°"}, "seat": {"heating": ["ROW_2_LEFT"], "ventilation": []}}
     */
    suspend fun capabilities(): JSONObject {
        if (!ready) return JSONObject()
        val hvac = vehicle.getHVAC()
        val seat = vehicle.getSeat()
        val temperatureAreas = await<List<SeatArea>> { ok, fail ->
            hvac.checkHvacTemperatureCapabilities({ areas -> ok(areas.keys.toList()) }, { fail() })
        }.orEmpty()
        val heatingAreas = await<List<SeatArea>> { ok, fail ->
            seat.checkSeatTemperatureCapabilities({ areas -> ok(areas.keys.toList()) }, { fail() })
        }.orEmpty()
        val ventilationAreas = await<List<SeatArea>> { ok, fail ->
            seat.checkSeatVentilationCapabilities({ areas -> ok(areas.keys.toList()) }, { fail() })
        }.orEmpty()
        val driverArea = temperatureAreas.firstOrNull { SeatValue.ROW_1_LEFT in it.values } ?: temperatureAreas.firstOrNull()
        val current = driverArea?.let { area ->
            await<Float> { ok, fail -> hvac.getHvacTemperatureCurrent(area, TemperatureUnit.CELSIUS, { v -> if (v != null) ok(v) else fail() }, { fail() }) }
        }
        return JSONObject()
            .put(
                "hvac",
                JSONObject()
                    .put("temperature", names(temperatureAreas))
                    .putOpt("current_celsius", current?.let { "%.0f°".format(it) }),
            )
            .put("seat", JSONObject().put("heating", names(heatingAreas)).put("ventilation", names(ventilationAreas)))
    }

    /** 실내 온도를 [celsius]로 맞춥니다. [area]가 없으면 운전석 구역을 씁니다. */
    fun setCabinTemperature(celsius: Float, area: String? = null, onDone: (Result<Float>) -> Unit) {
        if (!ready) return onDone(Result.failure(IllegalStateException("Vehicle 연결 안 됨")))
        val hvac = vehicle.getHVAC()
        runCatching {
            hvac.checkHvacTemperatureCapabilities(
                { areas ->
                    val target = pick(areas.keys, listOfNotNull(seatValue(area), SeatValue.ROW_1_LEFT)) ?: areas.keys.firstOrNull()
                    if (target == null) {
                        onDone(Result.failure(UnsupportedFeatureException("공조 온도 조절을 지원하지 않아요")))
                    } else {
                        hvac.setHvacTemperature(
                            target,
                            celsius,
                            TemperatureUnit.CELSIUS,
                            { _, value -> onDone(Result.success(value ?: celsius)) },
                            { e -> onDone(Result.failure(e)) },
                        )
                    }
                },
                { e -> onDone(Result.failure(e)) },
            )
        }.onFailure { e -> onDone(Result.failure(e)) }
    }

    /** 시트 열선을 [level]단으로 켭니다. [area]가 없으면 탑승자 자리(뒷좌석 오른쪽 → 왼쪽 → 조수석)를 고릅니다. */
    fun setSeatHeat(level: Int, area: String? = null, onDone: (Result<Int>) -> Unit) {
        if (!ready) return onDone(Result.failure(IllegalStateException("Vehicle 연결 안 됨")))
        val seat = vehicle.getSeat()
        runCatching {
            seat.checkSeatTemperatureCapabilities(
                { areas ->
                    val preferred = listOfNotNull(seatValue(area)).ifEmpty { listOf(SeatValue.ROW_2_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_1_RIGHT) }
                    val target = pick(areas.keys, preferred)
                    if (target == null) {
                        onDone(Result.failure(UnsupportedFeatureException("이 자리 열선을 지원하지 않아요")))
                    } else {
                        seat.setHvacSeatTemperature(
                            target,
                            level,
                            { _, value -> onDone(Result.success(value ?: level)) },
                            { e -> onDone(Result.failure(e)) },
                        )
                    }
                },
                { e -> onDone(Result.failure(e)) },
            )
        }.onFailure { e -> onDone(Result.failure(e)) }
    }

    fun release() {
        if (!ready) return
        runCatching { vehicle.release() }
        ready = false
    }

    private fun seatValue(name: String?): SeatValue? = name?.let { n -> SeatValue.entries.firstOrNull { it.name == n } }

    private fun pick(areas: Collection<SeatArea>, preferred: List<SeatValue>): SeatArea? =
        preferred.firstNotNullOfOrNull { v -> areas.firstOrNull { v in it.values } }

    private fun names(areas: List<SeatArea>): JSONArray = JSONArray(areas.flatMap { a -> a.values.map { it.name } }.distinct())

    /** 콜백 SDK 호출을 기다립니다. 3초 안에 답이 없거나 실패하면 null. */
    private suspend fun <T> await(call: (ok: (T) -> Unit, fail: () -> Unit) -> Unit): T? = withTimeoutOrNull(3_000) {
        suspendCancellableCoroutine { c ->
            runCatching {
                call({ v -> if (c.isActive) c.resume(v) }, { if (c.isActive) c.resume(null) })
            }.onFailure { if (c.isActive) c.resume(null) }
        }
    }
}
