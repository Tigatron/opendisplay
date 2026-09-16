package build.terrynamic.opendisplay

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import build.terrynamic.opendisplay.service.ReceiverService
import build.terrynamic.opendisplay.ui.ReceiverRoot

class MainActivity : ComponentActivity() {
    private val controller: ReceiverController
        get() = (application as ReceiverApp).controller

    private var settingsOpen by mutableStateOf(false)

    private val notifyPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applyCutoutMode()
        requestNotifyPermission()
        ReceiverService.start(this)
        controller.startListening()
        publishPanelSize()

        setContent {
            val state by controller.uiState.collectAsState()
            applyImmersive(state.phase == ReceiverPhase.STREAMING)
            ReceiverRoot(
                state = state,
                controller = controller,
                settingsOpen = settingsOpen,
                onOpenSettings = { settingsOpen = true },
                onCloseSettings = { settingsOpen = false },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        publishPanelSize()
        if (!isLocked()) controller.resumeFromSleep()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) publishPanelSize()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        controller.shutdown(sendClosing = true)
        ReceiverService.stop(this)
        finish()
    }

    override fun onDestroy() {
        if (isFinishing) {
            controller.shutdown(sendClosing = true)
            ReceiverService.stop(this)
        }
        super.onDestroy()
    }

    private fun publishPanelSize() {
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
        val density = resources.displayMetrics.density
        val smallest = resources.configuration.smallestScreenWidthDp
        controller.updatePanel(
            width = bounds.width(),
            height = bounds.height(),
            scale = density,
            smallestWidthDp = smallest,
        )
    }

    private fun applyCutoutMode() {
        val attrs = window.attributes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            attrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            attrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.attributes = attrs
    }

    private fun applyImmersive(streaming: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (streaming) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun requestNotifyPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun isLocked(): Boolean {
        val km = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
        return km.isKeyguardLocked
    }
}
