package com.hackmit.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hackmit.app.ui.StrokeApp
import com.hackmit.app.ui.theme.StrokeSenseTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as StrokeApplication).container
        setContent {
            StrokeSenseTheme {
                StrokeApp(container)
            }
        }
    }
}
