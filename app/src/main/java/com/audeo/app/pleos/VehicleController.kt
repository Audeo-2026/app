package com.audeo.app.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/** 이 차에서 지원하지 않는 기능. 제어하지 않고 직접 조작을 안내합니다. */
class UnsupportedFeatureException(message: String) : Exception(message)

/** 문·트렁크 명령은 기어가 P일 때만 실행합니다. 서버에는 detail `GEAR_NOT_PARK`로 보고해요. */
class GearNotParkException : Exception("GEAR_NOT_PARK")

/** 차량 응답이 제때 오지 않았어요. 성공·실패를 모르니 서버에는 unknown으로 보고합니다. */
class VehicleTimeoutException : Exception("no vehicle callback")

/**
 * Vehicle SDK로 공조·시트·창문·문을 제어하고 상태를 읽습니다.
 * - 지원 여부(check*Capabilities)를 먼저 확인하고, 지원되는 영역에만 요청합니다.
 * - 결과는 콜백으로 오며, SDK가 성공을 돌려줬을 때만 성공으로 봅니다.
 * - 서버 명령의 op는 SDK 메서드 이름 그대로입니다(setHvacAc, setDoorPosition …).
 */
class VehicleController(context: Context) {

    private val vehicle = Vehicle(context.applicationContext)

    @Volatile
    private var ready = false

    /** check*Capabilities 결과. 처음 한 번 읽어 두고 상태 읽기·명령 실행에 씁니다. */
    private class Areas(
        val power: List<SeatArea> = emptyList(),
        val ac: List<SeatArea> = emptyList(),
        val airCleaner: List<SeatArea> = emptyList(),
        val recirculation: List<SeatArea> = emptyList(),
        val temperature: Map<SeatArea, AreaBounds<Float>> = emptyMap(),
        val defroster: List<WindowArea> = emptyList(),
        val heating: Map<SeatArea, AreaBounds<Int>> = emptyMap(),
        val ventilation: Map<SeatArea, AreaBounds<Int>> = emptyMap(),
        val occupancy: List<SeatArea> = emptyList(),
        val belt: List<SeatArea> = emptyList(),
        val window: Map<WindowArea, AreaBounds<Int>> = emptyMap(),
        val doorLock: List<DoorArea> = emptyList(),
        val doorPosition: Map<DoorArea, AreaBounds<Int>> = emptyMap(),
    )

    @Volatile
    private var areas: Areas? = null

    /** 초기화. 실패하면 이유를 돌려줍니다. */
    fun start(): String? = try {
        vehicle.initialize()
        ready = true
        null
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    private suspend fun areas(): Areas {
        areas?.let { return it }
        val hvac = vehicle.getHVAC()
        val seat = vehicle.getSeat()
        val window = vehicle.getWindow()
        val door = vehicle.getDoor()
        val a = Areas(
            power = await<List<SeatArea>> { ok, fail -> hvac.checkHvacPowerCapabilities({ ok(it) }, { fail() }) }.orEmpty(),
            ac = await<List<SeatArea>> { ok, fail -> hvac.checkHvacAcCapabilities({ ok(it) }, { fail() }) }.orEmpty(),
            airCleaner = await<List<SeatArea>> { ok, fail -> hvac.checkHvacAirCleanerCapabilities({ ok(it) }, { fail() }) }.orEmpty(),
            recirculation = await<List<SeatArea>> { ok, fail -> hvac.checkHvacRecirculationCapabilities({ ok(it) }, { fail() }) }.orEmpty(),
            temperature = await<Map<SeatArea, AreaBounds<Float>>> { ok, fail -> hvac.checkHvacTemperatureCapabilities({ ok(it) }, { fail() }) }.orEmpty(),
            defroster = await<List<WindowArea>> { ok, fail -> hvac.checkHvacDefrosterCapabilities({ ok(it) }, { fail() }) }.orEmpty(),
            heating = await<Map<SeatArea, AreaBounds<Int>>> { ok, fail -> seat.checkSeatTemperatureCapabilities({ ok(it) }, { fail() }) }.orEmpty(),
            ventilation = await<Map<SeatArea, AreaBounds<Int>>> { ok, fail -> seat.checkSeatVentilationCapabilities({ ok(it) }, { fail() }) }.orEmpty(),
            occupancy = await<List<SeatArea>> { ok, fail -> seat.checkSeatOccupancyCapabilities({ ok(it) }, { fail() }) }.orEmpty(),
            belt = await<List<SeatArea>> { ok, fail -> seat.checkSeatBeltBuckledCapabilities({ ok(it) }, { fail() }) }.orEmpty(),
            window = await<Map<WindowArea, AreaBounds<Int>>> { ok, fail -> window.checkWindowPositionCapabilities({ ok(it) }, { fail() }) }.orEmpty(),
            doorLock = await<List<DoorArea>> { ok, fail -> door.checkDoorLockCapabilities({ ok(it) }, { fail() }) }.orEmpty(),
            doorPosition = await<Map<DoorArea, AreaBounds<Int>>> { ok, fail -> door.checkDoorPositionCapabilities({ ok(it) }, { fail() }) }.orEmpty(),
        )
        areas = a
        return a
    }

    /**
     * 서버에 올릴 지원 범위 (서버 docs/api.md 의 capabilities 모양).
     * `ops`에는 이 앱이 실제로 실행할 수 있는 명령만 넣어요. 서버는 이 목록에 있는 명령만 제안합니다.
     */
    suspend fun capabilities(): JSONObject {
        if (!ready) return JSONObject()
        val a = areas()
        val driverTemp = a.temperature.keys.firstOrNull { SeatValue.ROW_1_LEFT in it.values } ?: a.temperature.keys.firstOrNull()
        val current = driverTemp?.let { area ->
            await<Float> { ok, fail -> vehicle.getHVAC().getHvacTemperatureCurrent(area, TemperatureUnit.CELSIUS, { v -> if (v != null) ok(v) else fail() }, { fail() }) }
        }
        val range = driverTemp?.let { a.temperature[it] }
        val ops = buildList {
            if (a.power.isNotEmpty()) add("setHvacPower")
            if (a.ac.isNotEmpty()) add("setHvacAc")
            if (a.airCleaner.isNotEmpty()) add("setHvacAirCleaner")
            if (a.recirculation.isNotEmpty()) add("setHvacRecirculation")
            if (a.temperature.isNotEmpty()) add("setHvacTemperature")
            if (a.defroster.isNotEmpty()) add("setHvacDefroster")
            if (a.heating.isNotEmpty()) add("setHvacSeatTemperature")
            if (a.ventilation.isNotEmpty()) add("setHvacSeatVentilation")
            if (a.window.isNotEmpty()) add("setWindowPosition")
            if (a.doorLock.isNotEmpty()) add("setDoorLock")
            if (a.doorPosition.isNotEmpty()) add("setDoorPosition")
        }
        return JSONObject()
            .put(
                "hvac",
                JSONObject()
                    .put("power", seatNames(a.power))
                    .put("ac", seatNames(a.ac))
                    .put("air_cleaner", seatNames(a.airCleaner))
                    .put("recirculation", seatNames(a.recirculation))
                    .put("temperature", seatNames(a.temperature.keys))
                    .putOpt("temperature_range", range?.let { JSONArray().put(it.minValue.toDouble()).put(it.maxValue.toDouble()) })
                    .put("defroster", windowNames(a.defroster))
                    .putOpt("current_celsius", current?.let { "%.0f°".format(it) }),
            )
            .put(
                "seat",
                JSONObject()
                    .put("heating", seatNames(a.heating.keys))
                    .putOpt("heating_max", a.heating.values.maxOfOrNull { it.maxValue })
                    .put("ventilation", seatNames(a.ventilation.keys))
                    .putOpt("ventilation_max", a.ventilation.values.maxOfOrNull { it.maxValue })
                    .put("occupancy", seatNames(a.occupancy))
                    .put("belt", seatNames(a.belt)),
            )
            .put("window", JSONObject().put("position", windowNames(a.window.keys)))
            .put("door", JSONObject().put("lock", doorNames(a.doorLock)).put("position", doorNames(a.doorPosition.keys)))
            .put("ops", JSONArray(ops))
    }

    /**
     * 지금 차 상태 (서버 status 의 `vehicle`). 읽지 못한 값은 넣지 않아요. 서버는 빈 값이면 그 규칙을 건너뜁니다.
     */
    suspend fun state(): JSONObject {
        val out = JSONObject()
        if (!ready) return out
        val a = areas()
        val hvac = vehicle.getHVAC()
        val seat = vehicle.getSeat()
        val driverTemp = a.temperature.keys.firstOrNull { SeatValue.ROW_1_LEFT in it.values } ?: a.temperature.keys.firstOrNull()
        driverTemp?.let { area ->
            quick<Float> { ok, fail -> hvac.getHvacTemperatureCurrent(area, TemperatureUnit.CELSIUS, { v -> if (v != null) ok(v) else fail() }, { fail() }) }
        }?.let { out.put("cabin_c", it.toDouble()) }
        a.power.firstOrNull()?.let { area -> quick<Boolean> { ok, fail -> hvac.getHvacPowerState(area, { v -> if (v != null) ok(v) else fail() }, { fail() }) } }
            ?.let { out.put("hvac_on", it) }
        a.ac.firstOrNull()?.let { area -> quick<Boolean> { ok, fail -> hvac.getHvacAcState(area, { v -> if (v != null) ok(v) else fail() }, { fail() }) } }
            ?.let { out.put("ac_on", it) }
        val heat = JSONObject()
        for (area in a.heating.keys) {
            val v = quick<Int> { ok, fail -> seat.getHvacSeatTemperature(area, { v -> if (v != null) ok(v) else fail() }, { fail() }) } ?: continue
            area.values.forEach { heat.put(it.name, v) }
        }
        if (heat.length() > 0) out.put("seat_heat", heat)
        val vent = JSONObject()
        for (area in a.ventilation.keys) {
            val v = quick<Int> { ok, fail -> seat.getHvacSeatVentilationState(area, { v -> if (v != null) ok(v) else fail() }, { fail() }) } ?: continue
            area.values.forEach { vent.put(it.name, v) }
        }
        if (vent.length() > 0) out.put("seat_vent", vent)
        val open = JSONArray()
        for ((area, bounds) in a.window) {
            val p = quick<Int> { ok, fail -> vehicle.getWindow().getCurrentWindowPosition(area, { v -> if (v != null) ok(v) else fail() }, { fail() }) } ?: continue
            if (p > bounds.minValue) area.values.forEach { open.put(it.name) }
        }
        if (a.window.isNotEmpty()) out.put("windows_open", open)
        a.doorLock.firstOrNull()?.let { area ->
            quick<Boolean> { ok, fail -> vehicle.getDoor().getDoorLockState(area, { v -> if (v != null) ok(v) else fail() }, { fail() }) }
        }?.let { out.put("doors_locked", it) }
        gear()?.let { out.put("gear", it.name.removePrefix("GEAR_")) }
        val occupied = JSONArray()
        for (area in a.occupancy) {
            if (quick<Boolean> { ok, fail -> seat.getCurrentSeatOccupancy(area, { v -> if (v != null) ok(v) else fail() }, { fail() }) } == true) {
                area.values.forEach { occupied.put(it.name) }
            }
        }
        if (a.occupancy.isNotEmpty()) out.put("occupied", occupied)
        val belted = JSONArray()
        for (area in a.belt) {
            if (quick<Boolean> { ok, fail -> seat.getCurrentSeatBeltBuckledState(area, { v -> if (v != null) ok(v) else fail() }, { fail() }) } == true) {
                area.values.forEach { belted.put(it.name) }
            }
        }
        if (a.belt.isNotEmpty()) out.put("belted", belted)
        return out
    }

    private suspend fun gear(): VehicleGear? =
        quick<VehicleGear> { ok, fail -> vehicle.getGear().getCurrentGearSelectionState({ v -> if (v != null) ok(v) else fail() }, { fail() }) }

    /**
     * 서버 명령 하나를 실행합니다. [op]는 Vehicle SDK 메서드 이름, [args]는 명령의 나머지 값(area, on, level, position …).
     * 성공하면 SDK가 돌려준 값을 문자열로 돌려줘요.
     */
    suspend fun execute(op: String, args: JSONObject): Result<String> {
        if (!ready) return Result.failure(IllegalStateException("Vehicle 연결 안 됨"))
        val a = areas()
        val hvac = vehicle.getHVAC()
        val seat = vehicle.getSeat()
        val areaName = args.optString("area").ifEmpty { null }
        val on = args.optBoolean("on", true)
        return runCatching {
            when (op) {
                "setHvacPower" -> call<Boolean> { ok, fail -> hvac.setHvacPower(seatArea(a.power, areaName, loose = true), on, { _, v -> ok(v ?: on) }, fail) }
                "setHvacAc" -> call<Boolean> { ok, fail -> hvac.setHvacAc(seatArea(a.ac, areaName, loose = true), on, { _, v -> ok(v ?: on) }, fail) }
                "setHvacAirCleaner" -> call<Boolean> { ok, fail -> hvac.setHvacAirCleaner(seatArea(a.airCleaner, areaName, loose = true), on, { _, v -> ok(v ?: on) }, fail) }
                "setHvacRecirculation" -> call<Boolean> { ok, fail -> hvac.setHvacRecirculation(seatArea(a.recirculation, areaName, loose = true), on, { _, v -> ok(v ?: on) }, fail) }
                "setHvacTemperature" -> {
                    val target = seatArea(a.temperature.keys, areaName ?: SeatValue.ROW_1_LEFT.name, loose = true)
                    val b = a.temperature[target]
                    val c = args.optDouble("celsius", 24.0).toFloat().let { if (b != null) it.coerceIn(b.minValue, b.maxValue) else it }
                    call<Float> { ok, fail -> hvac.setHvacTemperature(target, c, TemperatureUnit.CELSIUS, { _, v -> ok(v ?: c) }, fail) }
                }
                "setHvacDefroster" -> call<Boolean> { ok, fail -> hvac.setHvacDefroster(windowArea(a.defroster, areaName), on, { _, v -> ok(v ?: on) }, fail) }
                "setHvacSeatTemperature" -> {
                    val target = seatArea(a.heating.keys, areaName, loose = false)
                    val level = args.optInt("level", 1).coerceIn(0, a.heating[target]?.maxValue ?: 3)
                    call<Int> { ok, fail -> seat.setHvacSeatTemperature(target, level, { _, v -> ok(v ?: level) }, fail) }
                }
                "setHvacSeatVentilation" -> {
                    val target = seatArea(a.ventilation.keys, areaName, loose = false)
                    val level = args.optInt("level", 1).coerceIn(0, a.ventilation[target]?.maxValue ?: 3)
                    call<Int> { ok, fail -> seat.setHvacSeatVentilation(target, level, { _, v -> ok(v ?: level) }, fail) }
                }
                "setWindowPosition" -> {
                    val target = windowArea(a.window.keys, areaName)
                    val b = a.window[target]
                    val p = args.optInt("position", 0).let { if (b != null) scale(it, b) else it }
                    call<Int> { ok, fail -> vehicle.getWindow().setWindowPosition(target, p, { _, v -> ok(v ?: p) }, fail) }
                }
                "setDoorLock" -> {
                    requirePark()
                    val target = doorArea(a.doorLock, areaName)
                    val locked = args.optBoolean("locked", true)
                    call<Boolean> { ok, fail -> vehicle.getDoor().setDoorLock(target, locked, { _, v -> ok(v ?: locked) }, fail) }
                }
                "setDoorPosition" -> {
                    requirePark()
                    val target = doorArea(a.doorPosition.keys, areaName)
                    val b = a.doorPosition[target]
                    val p = args.optInt("position", 100).let { if (b != null) scale(it, b) else it }
                    call<Int> { ok, fail -> vehicle.getDoor().setDoorPosition(target, p, { _, v -> ok(v ?: p) }, fail) }
                }
                else -> throw UnsupportedFeatureException("이 앱이 모르는 명령: $op")
            }.getOrThrow().toString()
        }
    }

    /** 실행 직전 기어 확인. P가 아니면 문·트렁크를 열지 않아요. */
    private suspend fun requirePark() {
        if (gear() != VehicleGear.GEAR_PARK) throw GearNotParkException()
    }

    /** 서버는 0(닫힘)~100(열림)으로 보냅니다. SDK 영역의 실제 범위로 바꿔요. */
    private fun scale(percent: Int, b: AreaBounds<Int>): Int =
        (b.minValue + (b.maxValue - b.minValue) * percent.coerceIn(0, 100) / 100f).toInt()

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
        areas = null
    }

    private fun seatValue(name: String?): SeatValue? = name?.let { n -> SeatValue.entries.firstOrNull { it.name == n } }

    private fun pick(areas: Collection<SeatArea>, preferred: List<SeatValue>): SeatArea? =
        preferred.firstNotNullOfOrNull { v -> areas.firstOrNull { v in it.values } }

    /** 이름이 맞는 영역. [loose]면 못 찾을 때 첫 영역을 씁니다(공조처럼 차 전체에 걸리는 기능). 시트는 엉뚱한 자리를 켜지 않게 정확히 맞춰요. */
    private fun seatArea(areas: Collection<SeatArea>, name: String?, loose: Boolean): SeatArea =
        areas.firstOrNull { a -> a.values.any { it.name == name } }
            ?: (if (loose || name == null) areas.firstOrNull() else null)
            ?: throw UnsupportedFeatureException("${name ?: "이 영역"}은 지원하지 않아요")

    private fun windowArea(areas: Collection<WindowArea>, name: String?): WindowArea =
        areas.firstOrNull { a -> a.values.any { it.name == name } } ?: (if (name == null) areas.firstOrNull() else null)
            ?: throw UnsupportedFeatureException("${name ?: "창문"}은 지원하지 않아요")

    private fun doorArea(areas: Collection<DoorArea>, name: String?): DoorArea =
        areas.firstOrNull { a -> a.values.any { it.name == name } } ?: (if (name == null) areas.firstOrNull() else null)
            ?: throw UnsupportedFeatureException("${name ?: "문"}은 지원하지 않아요")

    private fun seatNames(areas: Collection<SeatArea>): JSONArray = JSONArray(areas.flatMap { a -> a.values.map { it.name } }.distinct())
    private fun windowNames(areas: Collection<WindowArea>): JSONArray = JSONArray(areas.flatMap { a -> a.values.map { it.name } }.distinct())
    private fun doorNames(areas: Collection<DoorArea>): JSONArray = JSONArray(areas.flatMap { a -> a.values.map { it.name } }.distinct())

    /** 콜백 SDK 호출을 기다립니다. 3초 안에 답이 없거나 실패하면 null. */
    private suspend fun <T> await(call: (ok: (T) -> Unit, fail: () -> Unit) -> Unit): T? = withTimeoutOrNull(3_000) {
        suspendCancellableCoroutine { c ->
            runCatching {
                call({ v -> if (c.isActive) c.resume(v) }, { if (c.isActive) c.resume(null) })
            }.onFailure { if (c.isActive) c.resume(null) }
        }
    }

    /** 상태 읽기용: 15초마다 여러 값을 읽으니 짧게 기다립니다. */
    private suspend fun <T> quick(call: (ok: (T) -> Unit, fail: () -> Unit) -> Unit): T? = withTimeoutOrNull(1_500) {
        suspendCancellableCoroutine { c ->
            runCatching {
                call({ v -> if (c.isActive) c.resume(v) }, { if (c.isActive) c.resume(null) })
            }.onFailure { if (c.isActive) c.resume(null) }
        }
    }

    /** 제어 명령: 성공 콜백 값 또는 SDK가 준 오류. 8초 안에 답이 없으면 [VehicleTimeoutException]. */
    private suspend fun <T> call(block: (ok: (T) -> Unit, fail: (Exception) -> Unit) -> Unit): Result<T> =
        withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine<Result<T>> { c ->
                runCatching {
                    block({ v -> if (c.isActive) c.resume(Result.success(v)) }, { e -> if (c.isActive) c.resume(Result.failure(e)) })
                }.onFailure { e -> if (c.isActive) c.resume(Result.failure(e)) }
            }
        } ?: Result.failure(VehicleTimeoutException())
}
