package com.panhub.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.panhub.app.di.PanHubContainer
import com.panhub.app.ui.PanHubRoot
import com.panhub.core.design.PanHubTheme

class MainActivity : ComponentActivity() {

    /**
     * 通知权限。
     *
     * 传输靠一条常驻通知告诉用户「还在传」，Android 13 起没有这个权限
     * 通知就不显示（前台服务本身仍然生效，进程照样有优先级）。
     * 拒绝也继续用，只是看不见进度——所以不做二次劝说。
     */
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askForNotificationPermission()
        val session = PanHubContainer.sessionManager(this)
        val transfers = PanHubContainer.transferEngine(this)
        val previews = PanHubContainer.previewLoader(this)
        setContent {
            PanHubTheme {
                PanHubRoot(session, transfers, previews)
            }
        }
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
