package com.audeo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.audeo.app.ui.PleosStatusScreen
import com.audeo.app.ui.theme.AudeoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AudeoTheme {
                PleosStatusScreen()
            }
        }
    }
}
