package com.audeo.app.ui.audeo

import android.view.Gravity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.audeo.app.audeo.AudeoUiState
import com.audeo.app.audeo.CallStatus
import com.audeo.app.audeo.GeoPoint
import com.audeo.app.audeo.Scene
import com.audeo.app.ui.theme.Audeo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 무료 지도: MapLibre(오픈소스 지도 엔진) + OpenFreeMap 타일(키·요금 없음) + OSRM 공개 경로 서버.
 * Pleos에는 앱 안에 넣을 지도가 없어서 직접 그립니다. 실제 길안내는 여전히 차의 내비(NaviHelper)가 합니다.
 */
private const val STYLE_DAY = "https://tiles.openfreemap.org/styles/liberty"
private const val STYLE_NIGHT = "https://tiles.openfreemap.org/styles/dark"

/** 경로를 그리는 장면(약속이 정해진 뒤). */
private val ROUTE_SCENES = setOf(Scene.PLAN, Scene.DRIVE, Scene.CHANGE, Scene.VEHICLE, Scene.ARRIVE)

/** OSRM이 돌려준 길. */
data class RouteLine(val points: List<GeoPoint>, val seconds: Double, val meters: Double)

private val http by lazy {
    OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
}

/** OSRM 공개 서버로 자동차 길을 받습니다. 실패하면 null(지도는 두 점만 보여 줘요). */
private suspend fun fetchRoute(from: GeoPoint, to: GeoPoint): RouteLine? = withContext(Dispatchers.IO) {
    runCatching {
        val url = "https://router.project-osrm.org/route/v1/driving/" +
            "${from.lng},${from.lat};${to.lng},${to.lat}?overview=full&geometries=geojson"
        val req = Request.Builder().url(url).header("User-Agent", "Audeo-Car/0.1").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@use null
            val route = JSONObject(r.body?.string().orEmpty()).optJSONArray("routes")?.optJSONObject(0) ?: return@use null
            val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
            val pts = (0 until coords.length()).map { i -> coords.getJSONArray(i).let { GeoPoint(it.getDouble(1), it.getDouble(0)) } }
            RouteLine(pts, route.optDouble("duration"), route.optDouble("distance"))
        }
    }.getOrNull()
}

/** 앱 화면 전체를 채우는 지도. 아래 시트·위 통화 카드에 가리지 않도록 카메라 여백을 줍니다. */
@Composable
fun MapLayer(s: AudeoUiState, sheetPx: Int) {
    val journey = s.journey ?: s.finished
    val dest = journey?.meetPlace?.let { GeoPoint(it.latitude, it.longitude) }
    val car = s.car
    val wantRoute = s.scene in ROUTE_SCENES && dest != null && car != null
    var route by remember { mutableStateOf<RouteLine?>(null) }
    // 목적지가 바뀌거나 차가 300m쯤 움직였을 때만 다시 받습니다.
    val routeKey = if (wantRoute) "${dest!!.lat},${dest.lng}|${(car!!.lat * 300).roundToInt()},${(car.lng * 300).roundToInt()}" else null
    LaunchedEffect(routeKey) {
        if (routeKey == null) {
            route = null
            return@LaunchedEffect
        }
        fetchRoute(car!!, dest!!)?.let { route = it }
    }
    val destLabel = journey?.meetPlace?.name?.let { name ->
        val eta = if (wantRoute) etaText(s, route) else null
        listOfNotNull(shortName(name), eta).joinToString(" · ")
    }
    val density = LocalDensity.current
    val topPx = with(density) {
        (if (s.call?.status?.let { it != CallStatus.ENDED } == true) 230.dp else 40.dp).roundToPx()
    }
    val bottomPx = sheetPx + with(density) { (ODI_RISE + 40.dp).roundToPx() }
    LiveMap(
        car = car,
        dest = if (s.scene in ROUTE_SCENES || s.scene == Scene.DONE) dest else null,
        destLabel = destLabel,
        route = route?.takeIf { wantRoute },
        night = Audeo.colors.isNight,
        topPx = topPx,
        bottomPx = bottomPx,
    )
}

private fun etaText(s: AudeoUiState, route: RouteLine?): String? {
    s.coordination?.etaLabel?.let { return it }
    s.navi.remainSeconds?.let { return "${ceil(it / 60.0).toInt()}분" }
    return route?.let { "${ceil(it.seconds / 60.0).toInt()}분" }
}

/** "인천공항 제2터미널 3번 출구" → "제2터미널 3번 출구"처럼 지도 글자를 줄입니다. 괄호 설명은 뺍니다. */
private fun shortName(name: String): String {
    val plain = name.replace(Regex("\\s*\\(.*?\\)"), "").trim().ifEmpty { name }
    return if (plain.length <= 16) plain else plain.split(" ").takeLast(2).joinToString(" ")
}

/** 지도 확대 배율. MapLibre는 카메라 여백도 이 배율로 키우므로 여백은 이 값으로 나눠서 넘깁니다. */
private const val MAP_SCALE = 1.6f

private const val SRC_ROUTE = "audeo-route"
private const val SRC_CAR = "audeo-car"
private const val SRC_DEST = "audeo-dest"
private const val SRC_ENDS = "audeo-route-ends"

@Composable
private fun LiveMap(
    car: GeoPoint?,
    dest: GeoPoint?,
    destLabel: String?,
    route: RouteLine?,
    night: Boolean,
    topPx: Int,
    bottomPx: Int,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val c = Audeo.colors
    val brand = c.brand.toArgb()
    val ink = c.ink.toArgb()
    val halo = c.surface.toArgb()
    val mapView = remember {
        MapLibre.getInstance(context)
        val options = MapLibreMapOptions.createFromAttributes(context)
            .textureMode(true)
            // 차 화면은 멀리서 보니 지도 글자·길을 크게 그립니다.
            .pixelRatio(MAP_SCALE)
            .logoEnabled(false)
            .compassEnabled(false)
            .attributionEnabled(true)
            .attributionGravity(Gravity.TOP or Gravity.END)
            .attributionMargins(intArrayOf(0, 24, 24, 0))
            .rotateGesturesEnabled(false)
            .tiltGesturesEnabled(false)
        MapView(context, options).apply { onCreate(null) }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    LaunchedEffect(Unit) { mapView.getMapAsync { map = it } }
    LaunchedEffect(map, night) {
        val m = map ?: return@LaunchedEffect
        style = null
        m.setStyle(Style.Builder().fromUri(if (night) STYLE_NIGHT else STYLE_DAY)) { st ->
            koreanLabels(st)
            addAudeoLayers(st, brand = brand, ink = ink, halo = halo)
            style = st
        }
    }
    // 데이터: 길·차·만날 곳
    LaunchedEffect(style, route, car, dest, destLabel) {
        val st = style ?: return@LaunchedEffect
        st.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(
            route?.let { r -> Feature.fromGeometry(LineString.fromLngLats(r.points.map { Point.fromLngLat(it.lng, it.lat) })) }
                ?.let { FeatureCollection.fromFeature(it) } ?: FeatureCollection.fromFeatures(emptyList()),
        )
        // 경로 서버는 길 위로 붙여서 계산하므로, 차·만날 곳과 길 끝 사이는 점선으로 잇습니다.
        st.getSourceAs<GeoJsonSource>(SRC_ENDS)?.setGeoJson(
            FeatureCollection.fromFeatures(
                listOfNotNull(
                    route?.points?.firstOrNull()?.let { a -> car?.let { segment(it, a) } },
                    route?.points?.lastOrNull()?.let { b -> dest?.let { segment(b, it) } },
                ),
            ),
        )
        st.getSourceAs<GeoJsonSource>(SRC_CAR)?.setGeoJson(
            car?.let { FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(it.lng, it.lat))) } ?: FeatureCollection.fromFeatures(emptyList()),
        )
        st.getSourceAs<GeoJsonSource>(SRC_DEST)?.setGeoJson(
            dest?.let {
                val f = Feature.fromGeometry(Point.fromLngLat(it.lng, it.lat))
                f.addStringProperty("label", destLabel.orEmpty())
                FeatureCollection.fromFeature(f)
            } ?: FeatureCollection.fromFeatures(emptyList()),
        )
    }
    // 카메라: 길이 있으면 길 전체가, 없으면 차(또는 만날 곳)가 시트 위 빈 곳 가운데 오도록
    val carKey = car?.let { "${(it.lat * 2000).roundToInt()},${(it.lng * 2000).roundToInt()}" }
    LaunchedEffect(style, route, dest, carKey, topPx / 40, bottomPx / 40) {
        val m = map ?: return@LaunchedEffect
        if (style == null) return@LaunchedEffect
        val side = (96 / MAP_SCALE).roundToInt()
        val top = ((topPx + 60) / MAP_SCALE).roundToInt()
        val bottom = (bottomPx / MAP_SCALE).roundToInt()
        val pts = buildList {
            route?.points?.let { addAll(it) }
            car?.let { add(it) }
            dest?.let { add(it) }
        }
        val update = when {
            pts.size >= 2 && spread(pts) -> {
                val b = LatLngBounds.Builder().apply { pts.forEach { include(LatLng(it.lat, it.lng)) } }.build()
                CameraUpdateFactory.newLatLngBounds(b, side, top, side, bottom)
            }
            pts.isNotEmpty() -> CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(pts.first().lat, pts.first().lng))
                    .zoom(15.0)
                    .padding(side.toDouble(), top.toDouble(), side.toDouble(), bottom.toDouble())
                    .build(),
            )
            else -> null
        } ?: return@LaunchedEffect
        m.easeCamera(update, 1200)
    }
    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
}

private fun segment(a: GeoPoint, b: GeoPoint): Feature =
    Feature.fromGeometry(LineString.fromLngLats(listOf(Point.fromLngLat(a.lng, a.lat), Point.fromLngLat(b.lng, b.lat))))

/** 점들이 한곳에 몰려 있으면(같은 자리) 범위 맞추기 대신 확대 고정으로 봅니다. */
private fun spread(pts: List<GeoPoint>): Boolean {
    val lat = pts.maxOf { it.lat } - pts.minOf { it.lat }
    val lng = pts.maxOf { it.lng } - pts.minOf { it.lng }
    return abs(lat) > 0.002 || abs(lng) > 0.002
}

/** 지도 글자를 한국어 이름(name:ko)으로. 없으면 기본 이름을 씁니다. 한글 글꼴은 기기 글꼴로 그려요. */
private fun koreanLabels(st: Style) {
    val ko = Expression.coalesce(Expression.get("name:ko"), Expression.get("name"))
    st.layers.filterIsInstance<SymbolLayer>().forEach { layer ->
        val field = layer.textField
        val text = (field.expression?.toString() ?: field.value?.toString()).orEmpty()
        if ("name" in text) layer.setProperties(PropertyFactory.textField(ko))
    }
}

private fun addAudeoLayers(st: Style, brand: Int, ink: Int, halo: Int) {
    st.addSource(GeoJsonSource(SRC_ROUTE))
    st.addSource(GeoJsonSource(SRC_CAR))
    st.addSource(GeoJsonSource(SRC_DEST))
    st.addSource(GeoJsonSource(SRC_ENDS))
    st.addLayer(
        LineLayer("audeo-route-ends", SRC_ENDS).withProperties(
            PropertyFactory.lineColor(brand),
            PropertyFactory.lineOpacity(0.7f),
            PropertyFactory.lineWidth(3.5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineDasharray(arrayOf(0.1f, 2f)),
        ),
    )
    st.addLayer(
        LineLayer("audeo-route-casing", SRC_ROUTE).withProperties(
            PropertyFactory.lineColor(halo),
            PropertyFactory.lineWidth(10f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    st.addLayer(
        LineLayer("audeo-route", SRC_ROUTE).withProperties(
            PropertyFactory.lineColor(brand),
            PropertyFactory.lineWidth(6f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    st.addLayer(
        CircleLayer("audeo-dest-ring", SRC_DEST).withProperties(
            PropertyFactory.circleRadius(16f),
            PropertyFactory.circleColor(brand),
            PropertyFactory.circleOpacity(0.18f),
        ),
    )
    st.addLayer(
        CircleLayer("audeo-dest", SRC_DEST).withProperties(
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleColor(ink),
            PropertyFactory.circleStrokeColor(halo),
            PropertyFactory.circleStrokeWidth(3.5f),
        ),
    )
    st.addLayer(
        SymbolLayer("audeo-dest-label", SRC_DEST).withProperties(
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
            PropertyFactory.textSize(17f),
            PropertyFactory.textColor(ink),
            PropertyFactory.textHaloColor(halo),
            PropertyFactory.textHaloWidth(3f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textOffset(arrayOf(0f, 1.2f)),
            PropertyFactory.textAllowOverlap(true),
            PropertyFactory.textIgnorePlacement(true),
        ),
    )
    st.addLayer(
        CircleLayer("audeo-car-halo", SRC_CAR).withProperties(
            PropertyFactory.circleRadius(22f),
            PropertyFactory.circleColor(brand),
            PropertyFactory.circleOpacity(0.18f),
        ),
    )
    st.addLayer(
        CircleLayer("audeo-car", SRC_CAR).withProperties(
            PropertyFactory.circleRadius(10f),
            PropertyFactory.circleColor(brand),
            PropertyFactory.circleStrokeColor(halo),
            PropertyFactory.circleStrokeWidth(4f),
        ),
    )
}
