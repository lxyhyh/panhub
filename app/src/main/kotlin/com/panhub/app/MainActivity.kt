package com.panhub.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.panhub.app.ui.PanHubRoot
import com.panhub.core.design.PanHubTheme
import com.panhub.app.di.PanHubContainer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val session = PanHubContainer.sessionManager(this)
        setContent {
            PanHubTheme {
                PanHubRoot(session)
            }
        }
    }
}
