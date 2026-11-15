package com.audeo.app.map

import com.audeo.app.audeo.GeoPoint
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 대화에 나온 장소 이름("은마아파트 정문", "서울역 3번 출구")을 지도에서 찾아 좌표로 바꿉니다.
 *
 * Audeo 서버는 장소 이름을 차가 올린 저장 장소(내비 즐겨찾기·최근 목적지)에서만 찾아요. 처음 듣는 곳이면
 * 좌표가 없어 내비에 넣지 못하고 '어디서 만날까요?'로 되묻습니다. 그래서 차가 무료 지도(OpenStreetMap)에서
 * 먼저 찾아 봅니다. 탑승자 위치(통화 중 공유)나 차 위치 근처를 먼저 찾아서 '정문'처럼 흔한 이름도 맞는 곳을 골라요.
 *
 * - Overpass API: 이름의 낱말이 모두 들어간 지점을 근처에서 찾습니다(정류장·출입구·건물 이름 포함).
 * - 못 찾으면 Nominatim 검색으로 한 번 더 찾습니다.
 * 둘 다 무료 공개 서버라 느리거나 막힐 수 있어요. 실패하면 null을 돌려주고 운전자에게 물어요.
 */
class PlaceFinder(private val http: OkHttpClient = DEFAULT_HTTP) {

    data class Hit(val lat: Double, val lng: Double, val label: String, val distanceM: Double?, val source: String)

    /** 막히는 네트워크 호출입니다. 작업 스레드에서 부르세요. */
    fun find(name: String, near: GeoPoint?): Hit? {
        // '정문'처럼 흔한 이름은 가까운 곳부터 찾아요(먼 학교 정문을 고르지 않게).
        // 가까운 곳 검색이 '없음'일 때만 넓혀요. 서버가 응답하지 않은 것(실패)이면 넓히지 않고 다른 검색으로 가요.
        for (r in RADII) {
            val res = runCatching { overpass(name, near, r) }
            if (res.isFailure) break
            res.getOrNull()?.let { return it }
        }
        return runCatching { nominatim(name, near) }.getOrNull()
    }

    /**
     * 역·터미널 앞에서 잠깐 세울 곳: 택시 하차장·승하차장(OSM amenity=taxi, parking=drop_off, 이름에 '하차'·'승하차').
     * 픽업 차는 오래 서 있을 수 없어서 하차장을 먼저 고르고, 없으면 가장 가까운 곳을 씁니다.
     */
    fun findStopSpot(station: GeoPoint): Hit? = runCatching {
        val q = "[out:json][timeout:20];(" +
            "nwr[\"amenity\"=\"taxi\"](around:600,${station.lat},${station.lng});" +
            "nwr[\"parking\"~\"drop_off|layby\"](around:600,${station.lat},${station.lng});" +
            "nwr[\"name\"~\"승하차|하차장\"](around:600,${station.lat},${station.lng});" +
            ");out center 30;"
        val hits = query(q).map { (p, tags) ->
            val name = tags.optString("name").ifEmpty { if (tags.optString("amenity") == "taxi") "택시 승강장" else "정차 구역" }
            Hit(p.lat, p.lng, name, distanceM(station, p), "OpenStreetMap")
        }
        hits.filter { "하차" in it.label }.minByOrNull { it.distanceM ?: 0.0 } ?: hits.minByOrNull { it.distanceM ?: 0.0 }
    }.getOrNull()

    /** 공개 Overpass 서버는 붐비면 느려요. 차례로 시도하고, 모두 실패하면 예외(빈 결과와 구분). */
    private fun query(q: String): List<Pair<GeoPoint, JSONObject>> {
        var body: String? = null
        for (url in OVERPASS) {
            body = runCatching {
                val req = Request.Builder().url(url).post(FormBody.Builder().add("data", q).build()).header("User-Agent", UA).build()
                http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string()?.takeIf { it.trimStart().startsWith("{") } else null }
            }.getOrNull()
            if (body != null) break
        }
        body ?: throw java.io.IOException("Overpass 응답 없음")
        val elements = JSONObject(body).optJSONArray("elements") ?: return emptyList()
        return (0 until elements.length()).mapNotNull { i ->
            val e = elements.getJSONObject(i)
            val c = if (e.has("lat")) e else e.optJSONObject("center") ?: return@mapNotNull null
            GeoPoint(c.getDouble("lat"), c.getDouble("lon")) to (e.optJSONObject("tags") ?: JSONObject())
        }
    }

    private fun overpass(name: String, near: GeoPoint?, radius: Int): Hit? {
        near ?: return null
        val words = name.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        // 띄어쓰기가 달라도 맞도록 낱말마다 따로 조건을 겁니다("은마아파트 정문" ↔ "은마아파트정문").
        val filters = words.joinToString("") { "[\"name\"~\"${regex(it)}\"]" }
        val q = "[out:json][timeout:20];nwr$filters(around:$radius,${near.lat},${near.lng});out center 20;"
        return query(q).map { (p, tags) ->
            Hit(p.lat, p.lng, readable(tags.optString("name").ifEmpty { name }, words), distanceM(near, p), "OpenStreetMap")
        }.minByOrNull { it.distanceM ?: Double.MAX_VALUE }
    }

    private fun nominatim(name: String, near: GeoPoint?): Hit? {
        val url = NOMINATIM.toHttpUrl().newBuilder()
            .addQueryParameter("q", name)
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("countrycodes", "kr")
            .addQueryParameter("accept-language", "ko")
            .addQueryParameter("limit", "5")
            .apply {
                near?.let { p ->
                    val d = 0.05
                    addQueryParameter("viewbox", "${p.lng - d},${p.lat + d},${p.lng + d},${p.lat - d}")
                }
            }
            .build()
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        val body = http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null } ?: return null
        val arr = JSONArray(body)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val p = GeoPoint(o.getString("lat").toDouble(), o.getString("lon").toDouble())
            Hit(p.lat, p.lng, o.optString("name").ifEmpty { o.optString("display_name").substringBefore(",") }, near?.let { distanceM(it, p) }, "OpenStreetMap")
        }.minByOrNull { it.distanceM ?: 0.0 }
    }

    /** OSM 이름은 붙여 쓴 경우가 많아요("은마아파트정문"). 들은 낱말 앞을 띄워 읽기 좋게 합니다. */
    private fun readable(label: String, words: List<String>): String {
        var out = label
        words.forEach { w -> out = out.replace(Regex("(?<=\\S)" + Regex.escape(w)), " $w") }
        return out.replace(Regex("\\s+"), " ").trim()
    }

    private fun regex(word: String) = word.replace(Regex("[\\\\.^$|?*+()\\[\\]{}\"]"), "")

    companion object {
        private val OVERPASS = listOf("https://overpass.openstreetmap.fr/api/interpreter", "https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")
        private const val NOMINATIM = "https://nominatim.openstreetmap.org/search"
        private const val UA = "Audeo-Car/0.1 (Pleos Connect app; contact via Audeo team)"
        private val RADII = listOf(800, 3000)
        private val DEFAULT_HTTP = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()

        fun distanceM(a: GeoPoint, b: GeoPoint): Double {
            val r = Math.PI / 180
            val h = sin((b.lat - a.lat) * r / 2).pow(2) + cos(a.lat * r) * cos(b.lat * r) * sin((b.lng - a.lng) * r / 2).pow(2)
            return 2 * 6_371_000 * asin(sqrt(h))
        }

        /** a에서 b를 볼 때의 방위(북쪽 0°, 시계 방향). */
        fun bearing(a: GeoPoint, b: GeoPoint): Double {
            val r = Math.PI / 180
            val y = sin((b.lng - a.lng) * r) * cos(b.lat * r)
            val x = cos(a.lat * r) * sin(b.lat * r) - sin(a.lat * r) * cos(b.lat * r) * cos((b.lng - a.lng) * r)
            return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360) % 360
        }
    }
}
