package com.audeo.app.server

import android.content.Context
import androidx.core.content.edit

/** 서버에 등록된 이 차량 기기. 토큰은 등록 응답에서 한 번만 받으므로 앱 저장소에 둡니다. */
data class DeviceIdentity(
    val deviceId: String,
    val token: String,
    val userId: String,
    val name: String,
    val callName: String,
)

class DeviceStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("audeo_device", Context.MODE_PRIVATE)

    fun identity(): DeviceIdentity? {
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        return DeviceIdentity(
            deviceId = prefs.getString(KEY_DEVICE, "").orEmpty(),
            token = token,
            userId = prefs.getString(KEY_USER, "").orEmpty(),
            name = prefs.getString(KEY_NAME, "").orEmpty(),
            callName = prefs.getString(KEY_CALL_NAME, "").orEmpty(),
        )
    }

    fun save(identity: DeviceIdentity) = prefs.edit {
        putString(KEY_TOKEN, identity.token)
        putString(KEY_DEVICE, identity.deviceId)
        putString(KEY_USER, identity.userId)
        putString(KEY_NAME, identity.name)
        putString(KEY_CALL_NAME, identity.callName)
        remove(KEY_SEQ)
    }

    fun clear() = prefs.edit { clear() }

    /** 마지막으로 처리한 서버 이벤트 번호. 앱을 다시 켜도 이어서 받습니다. */
    var lastSeq: Long
        get() = prefs.getLong(KEY_SEQ, 0L)
        set(value) = prefs.edit { putLong(KEY_SEQ, value) }

    private companion object {
        const val KEY_TOKEN = "token"
        const val KEY_DEVICE = "device_id"
        const val KEY_USER = "user_id"
        const val KEY_NAME = "name"
        const val KEY_CALL_NAME = "call_name"
        const val KEY_SEQ = "last_seq"
    }
}
