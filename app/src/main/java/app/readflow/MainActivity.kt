package app.readflow

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.readflow.playback.ReadingService
import app.readflow.ui.ReadFlowUi
import app.readflow.ui.ReaderViewModel
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : ComponentActivity() {
    private val vm: ReaderViewModel by viewModels()
    private var controller: ListenableFuture<MediaController>? = null
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, ReadingService::class.java))).buildAsync().also { future ->
            future.addListener({ runCatching { future.get() }.onFailure { vm.message.value = "Playback service connection failed" } }, ContextCompat.getMainExecutor(this))
        }
        setContent { ReadFlowUi(vm) { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) } }
        if (savedInstanceState == null) handleShare(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleShare(intent) }
    private fun handleShare(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        if (uri != null) vm.import(uri)
        else intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
            Regex("https://[^\\s]+").find(text)?.value?.let(vm::importUrl)
                ?: run { vm.message.value = "Share an HTTPS article URL, PDF, or image" }
        }
    }
    override fun onDestroy() { controller?.let(MediaController::releaseFuture); super.onDestroy() }
}
