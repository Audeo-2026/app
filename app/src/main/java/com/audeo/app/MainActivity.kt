package com.audeo.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.audeo.app.audeo.AudeoViewModel
import com.audeo.app.ui.audeo.AudeoApp

class MainActivity : ComponentActivity() {

    private val vm: AudeoViewModel by viewModels()

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            vm.onLocationPermission(result[FUSED_LOCATION] ?: granted(FUSED_LOCATION))
            if (result[Manifest.permission.RECORD_AUDIO] == false) vm.onMicDenied()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AudeoApp(vm) }

        // 위치(FusedLocation)와 마이크(통화 음성)는 dangerous 권한이라 실행 중에 허락을 받습니다.
        val missing = listOf(FUSED_LOCATION, Manifest.permission.RECORD_AUDIO).filterNot(::granted)
        if (granted(FUSED_LOCATION)) vm.onLocationPermission(true)
        if (missing.isNotEmpty() && savedInstanceState == null) permissions.launch(missing.toTypedArray())
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val FUSED_LOCATION = "pleos.car.permission.FUSED_LOCATION"
    }
}
