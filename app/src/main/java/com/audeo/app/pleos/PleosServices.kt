package com.audeo.app.pleos

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * 앱에 포함된 Pleos SDK와, 그 SDK가 연결하는 차량 쪽 서비스.
 *
 * SDK 버전과 서비스 최소 버전은 각 SDK가 앱 매니페스트에 합쳐 넣는 meta-data에서 읽으므로
 * libs.versions.toml에서 SDK 버전을 올리면 화면에도 그대로 반영됩니다.
 */
enum class PleosSdk(
    val label: String,
    val sdkVersionKey: String,
    val minServiceVersionKey: String,
    /** SDK가 서비스를 찾을 때 쓰는 인텐트 액션. */
    val serviceAction: String?,
    /** 액션으로 찾지 못할 때 확인할 서비스 패키지. */
    val servicePackage: String?,
) {
    VEHICLE(
        label = "Vehicle",
        sdkVersionKey = "ai.pleos.playground.sdk.vehicle.current_version_name",
        minServiceVersionKey = "ai.pleos.playground.service.vehicle.min_version_name",
        serviceAction = "ai.pleos.playground.vehicle.VEHICLE_START",
        servicePackage = "ai.pleos.playground.service.vehicle",
    ),
    NAVI_HELPER(
        label = "NaviHelper",
        sdkVersionKey = "ai.pleos.playground.sdk.navi.helper.current_version_name",
        minServiceVersionKey = "ai.pleos.playground.service.navi.helper.min_version_name",
        serviceAction = "ai.pleos.playground.intent.action.NAVI_START",
        servicePackage = null,
    ),
    TEXT_TO_SPEECH(
        label = "TextToSpeech",
        sdkVersionKey = "ai.pleos.playground.sdk.tts.current_version_name",
        minServiceVersionKey = "ai.pleos.playground.caas.min_version_name",
        serviceAction = "ai.pleos.playground.caas.intent.action.SPEECH_START",
        servicePackage = "ai.pleos.playground.caas",
    ),
    SPEECH_TO_TEXT(
        label = "SpeechToText",
        sdkVersionKey = "ai.pleos.playground.sdk.stt.current_version_name",
        minServiceVersionKey = "ai.pleos.playground.caas.min_version_name",
        serviceAction = "ai.pleos.playground.caas.intent.action.SPEECH_START",
        servicePackage = "ai.pleos.playground.caas",
    ),
    FUSED_LOCATION(
        label = "FusedLocation",
        sdkVersionKey = "ai.pleos.playground.sdk.location.fused.current_version_name",
        minServiceVersionKey = "ai.pleos.playground.service.location.fused.min_version_name",
        serviceAction = null,
        servicePackage = "ai.pleos.playground.service.location.fused",
    ),
}

data class ServiceStatus(
    val sdk: PleosSdk,
    val sdkVersion: String?,
    val servicePackage: String?,
    val serviceVersion: String?,
    val minServiceVersion: String?,
) {
    enum class State { READY, OUTDATED, MISSING }

    val state: State
        get() = when {
            serviceVersion == null -> State.MISSING
            minServiceVersion != null && compareVersions(serviceVersion, minServiceVersion) < 0 -> State.OUTDATED
            else -> State.READY
        }
}

fun checkPleosServices(context: Context): List<ServiceStatus> {
    val pm = context.packageManager
    val meta = pm.getApplicationInfo(
        context.packageName,
        PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
    ).metaData

    return PleosSdk.entries.map { sdk ->
        val pkg = sdk.serviceAction?.let { pm.findServicePackage(it) } ?: sdk.servicePackage
        ServiceStatus(
            sdk = sdk,
            sdkVersion = meta?.getString(sdk.sdkVersionKey),
            servicePackage = pkg,
            serviceVersion = pkg?.let { pm.versionNameOf(it) },
            minServiceVersion = meta?.getString(sdk.minServiceVersionKey),
        )
    }
}

private fun PackageManager.findServicePackage(action: String): String? =
    queryIntentServices(Intent(action), PackageManager.ResolveInfoFlags.of(0))
        .firstOrNull()?.serviceInfo?.packageName

private fun PackageManager.versionNameOf(packageName: String): String? =
    try {
        getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

/** "2.3.21"처럼 점으로 나뉜 버전을 숫자 단위로 비교합니다. "-debug" 같은 꼬리는 무시합니다. */
fun compareVersions(a: String, b: String): Int {
    val x = a.numericParts()
    val y = b.numericParts()
    for (i in 0 until maxOf(x.size, y.size)) {
        val diff = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
        if (diff != 0) return diff
    }
    return 0
}

private fun String.numericParts(): List<Int> =
    substringBefore('-').split('.').map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
