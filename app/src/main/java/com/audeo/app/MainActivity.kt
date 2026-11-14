package com.audeo.app

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

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> vm.onLocationPermission(granted) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AudeoApp(vm) }

        // FusedLocation은 dangerous 권한이라 실행 중에 허락을 받습니다.
        if (ContextCompat.checkSelfPermission(this, FUSED_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            vm.onLocationPermission(true)
        } else if (savedInstanceState == null) {
            locationPermission.launch(FUSED_LOCATION)
        }
    }

    private companion object {
        const val FUSED_LOCATION = "pleos.car.permission.FUSED_LOCATION"
    }
}
