package com.screenshare

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.screenshare.databinding.ActivityBroadcastBinding

/**
 * Shows the room ID, QR code, streaming status, and chat panel while
 * [ScreenShareService] runs in the foreground.  Bound to the service so it can
 * receive live status, viewer-count, knock, and chat events.
 */
class BroadcastActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBroadcastBinding
    private var service: ScreenShareService? = null
    private var isBound = false

    private val errorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val msg = intent.getStringExtra(ScreenShareService.EXTRA_ERROR_MESSAGE) ?: "Unknown error"
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val statusListener = ScreenShareService.StatusListener { status ->
        runOnUiThread { binding.tvStatus.text = status }
    }

    private val viewerCountListener = ScreenShareService.ViewerCountListener { count ->
        runOnUiThread {
            binding.tvViewerCount.text = getString(R.string.label_viewer_count, count)
        }
    }

    private val chatListener = ScreenShareService.ChatListener { text ->
        runOnUiThread { appendChat("👁 $text") }
    }

    private val knockListener = ScreenShareService.KnockListener { viewerId, displayName ->
        runOnUiThread { showKnockDialog(viewerId, displayName) }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as ScreenShareService.LocalBinder).getService().also { svc ->
                svc.setStatusListener(statusListener)
                svc.setViewerCountListener(viewerCountListener)
                svc.setChatListener(chatListener)
                svc.setKnockListener(knockListener)
            }
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBroadcastBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val serverUrl  = intent.getStringExtra(EXTRA_SERVER_URL) ?: ScreenShareService.DEFAULT_SERVER_URL
        val password   = intent.getStringExtra(EXTRA_PASSWORD)
        val maxViewers = intent.getIntExtra(EXTRA_MAX_VIEWERS, 0)
        val useKnock   = intent.getBooleanExtra(EXTRA_USE_KNOCK, false)
        val useMic     = intent.getBooleanExtra(EXTRA_USE_MIC, true)
        val quality    = intent.getIntExtra(EXTRA_QUALITY, WebRTCClient.Quality.MEDIUM.ordinal)
        val turnUrl    = intent.getStringExtra(EXTRA_TURN_URL)
        val turnUser   = intent.getStringExtra(EXTRA_TURN_USER)
        val turnPass   = intent.getStringExtra(EXTRA_TURN_PASS)

        val roomId = (1000..9999).random().toString()
        binding.tvRoomId.text = roomId
        binding.tvViewerCount.text = getString(R.string.label_viewer_count, 0)

        // Generate QR code (room deep-link)
        val deepLink = "screenshare://room/$roomId"
        binding.ivQrCode.setImageBitmap(generateQrCode(deepLink, QR_SIZE_PX))

        // Listen for service error (e.g. missing MediaProjection permission).
        val filter = IntentFilter(ScreenShareService.ACTION_ERROR)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(errorReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(errorReceiver, filter)
        }

        // Request RECORD_AUDIO if mic is enabled.
        if (useMic && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQ_RECORD_AUDIO
            )
        }

        // Start the foreground service.
        val serviceIntent = Intent(this, ScreenShareService::class.java).apply {
            putExtra(ScreenShareService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenShareService.EXTRA_RESULT_DATA, resultData)
            putExtra(ScreenShareService.EXTRA_ROOM_ID, roomId)
            putExtra(ScreenShareService.EXTRA_SERVER_URL, serverUrl)
            putExtra(ScreenShareService.EXTRA_PASSWORD, password)
            putExtra(ScreenShareService.EXTRA_MAX_VIEWERS, maxViewers)
            putExtra(ScreenShareService.EXTRA_USE_KNOCK, useKnock)
            putExtra(ScreenShareService.EXTRA_USE_MIC, useMic)
            putExtra(ScreenShareService.EXTRA_QUALITY, quality)
            putExtra(ScreenShareService.EXTRA_TURN_URL, turnUrl)
            putExtra(ScreenShareService.EXTRA_TURN_USER, turnUser)
            putExtra(ScreenShareService.EXTRA_TURN_PASS, turnPass)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(
            Intent(this, ScreenShareService::class.java),
            serviceConnection,
            BIND_AUTO_CREATE
        )

        binding.btnStop.setOnClickListener {
            stopService(Intent(this, ScreenShareService::class.java))
            finish()
        }

        // Chat send
        binding.btnSendChat.setOnClickListener { sendChat() }
        binding.etChatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendChat(); true } else false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(errorReceiver)
        if (isBound) {
            service?.setStatusListener(null)
            service?.setViewerCountListener(null)
            service?.setChatListener(null)
            service?.setKnockListener(null)
            unbindService(serviceConnection)
            isBound = false
        }
        stopService(Intent(this, ScreenShareService::class.java))
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun sendChat() {
        val text = binding.etChatInput.text?.toString()?.trim() ?: return
        if (text.isEmpty()) return
        service?.sendChatMessage(text)
        appendChat("📡 $text")
        binding.etChatInput.text?.clear()
    }

    private fun appendChat(line: String) {
        val current = binding.tvChatLog.text?.toString() ?: ""
        binding.tvChatLog.text = if (current.isEmpty()) line else "$current\n$line"
        // Auto-scroll
        binding.scrollChat.post { binding.scrollChat.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun showKnockDialog(viewerId: String, displayName: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_viewer_knock))
            .setMessage(getString(R.string.msg_viewer_knock, displayName))
            .setPositiveButton(getString(R.string.action_accept)) { _, _ ->
                service?.acceptViewer(viewerId)
            }
            .setNegativeButton(getString(R.string.action_reject)) { _, _ ->
                service?.rejectViewer(viewerId)
            }
            .setCancelable(false)
            .show()
    }

    private fun generateQrCode(content: String, sizePx: Int): Bitmap {
        val bits = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (bits[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SERVER_URL  = "server_url"
        const val EXTRA_PASSWORD    = "password"
        const val EXTRA_MAX_VIEWERS = "max_viewers"
        const val EXTRA_USE_KNOCK   = "use_knock"
        const val EXTRA_USE_MIC     = "use_mic"
        const val EXTRA_QUALITY     = "quality"
        const val EXTRA_TURN_URL    = "turn_url"
        const val EXTRA_TURN_USER   = "turn_user"
        const val EXTRA_TURN_PASS   = "turn_pass"

        private const val REQ_RECORD_AUDIO = 1002
        private const val QR_SIZE_PX = 400
    }
}
