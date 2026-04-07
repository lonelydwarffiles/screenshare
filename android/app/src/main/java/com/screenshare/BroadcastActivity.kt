package com.screenshare

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
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
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.screenshare.databinding.ActivityBroadcastBinding

/**
 * Shows the session code, QR code, streaming status, chat panel, and interactive controls
 * while [ScreenShareService] runs in the foreground.
 *
 * Interactive controls (sent to viewer via DataChannel):
 *   🌑 Blackout – blanks the viewer's screen
 *   ⏸ Freeze   – pauses live capture (viewer sees still frame)
 *   📳 Buzz     – vibrates the viewer's device + Lovense toy
 *   ⏱ Countdown – syncs a countdown timer on the viewer's screen
 *
 * Incoming from viewer:
 *   emoji         – floating emoji animation
 *   app_violation – toast showing broadcaster opened a restricted app
 */
class BroadcastActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBroadcastBinding
    private var service: ScreenShareService? = null
    private var isBound = false

    private var isBlackoutOn = false
    private var isFreezeOn   = false

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

    private val chatListener = ScreenShareService.ChatListener { text ->
        runOnUiThread { appendChat("👁 $text") }
    }

    private val emojiListener = ScreenShareService.EmojiListener { emoji ->
        runOnUiThread { showFloatingEmoji(emoji) }
    }

    private val violationListener = ScreenShareService.ViolationListener { appName, _ ->
        runOnUiThread {
            Toast.makeText(
                this,
                getString(R.string.msg_app_violation, appName),
                Toast.LENGTH_SHORT
            ).show()
            appendChat("⚠️ ${getString(R.string.msg_app_violation, appName)}")
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as ScreenShareService.LocalBinder).getService().also { svc ->
                svc.setStatusListener(statusListener)
                svc.setChatListener(chatListener)
                svc.setEmojiListener(emojiListener)
                svc.setViolationListener(violationListener)
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
        val serverUrl       = intent.getStringExtra(EXTRA_SERVER_URL) ?: ScreenShareService.DEFAULT_SERVER_URL
        val sessionId       = intent.getStringExtra(EXTRA_SESSION_ID) ?: ""
        val password        = intent.getStringExtra(EXTRA_PASSWORD)
        val useMic          = intent.getBooleanExtra(EXTRA_USE_MIC, true)
        val quality         = intent.getIntExtra(EXTRA_QUALITY, WebRTCClient.Quality.MEDIUM.ordinal)
        val turnUrl         = intent.getStringExtra(EXTRA_TURN_URL)
        val turnUser        = intent.getStringExtra(EXTRA_TURN_USER)
        val turnPass        = intent.getStringExtra(EXTRA_TURN_PASS)
        val restrictedPkgs  = intent.getStringArrayListExtra(EXTRA_RESTRICTED_PKGS) ?: arrayListOf()

        binding.tvSessionCode.text = sessionId

        // Generate QR code for the deep-link.
        val deepLink = "screenshare://session/$sessionId"
        binding.ivQrCode.setImageBitmap(generateQrCode(deepLink, QR_SIZE_PX))

        // Register error receiver.
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

        // Prompt for SYSTEM_ALERT_WINDOW (needed for the lock overlay).
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }

        // Prompt to enable accessibility service if restricted apps are configured.
        if (restrictedPkgs.isNotEmpty() && !isAccessibilityServiceEnabled()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.title_enable_accessibility)
                .setMessage(R.string.msg_enable_accessibility)
                .setPositiveButton(R.string.action_open_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // Start the foreground service.
        val serviceIntent = Intent(this, ScreenShareService::class.java).apply {
            putExtra(ScreenShareService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenShareService.EXTRA_RESULT_DATA, resultData)
            putExtra(ScreenShareService.EXTRA_SESSION_ID, sessionId)
            putExtra(ScreenShareService.EXTRA_SERVER_URL, serverUrl)
            putExtra(ScreenShareService.EXTRA_PASSWORD, password)
            putExtra(ScreenShareService.EXTRA_USE_MIC, useMic)
            putExtra(ScreenShareService.EXTRA_QUALITY, quality)
            putExtra(ScreenShareService.EXTRA_TURN_URL, turnUrl)
            putExtra(ScreenShareService.EXTRA_TURN_USER, turnUser)
            putExtra(ScreenShareService.EXTRA_TURN_PASS, turnPass)
            putStringArrayListExtra(ScreenShareService.EXTRA_RESTRICTED_PKGS, restrictedPkgs)
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

        // --- Control buttons ---

        binding.btnStop.setOnClickListener {
            stopService(Intent(this, ScreenShareService::class.java))
            finish()
        }

        binding.btnBlackout.setOnClickListener {
            isBlackoutOn = !isBlackoutOn
            service?.sendControl("blackout", mapOf("on" to isBlackoutOn))
            binding.btnBlackout.text = if (isBlackoutOn)
                getString(R.string.action_blackout_off)
            else
                getString(R.string.action_blackout_on)
        }

        binding.btnFreeze.setOnClickListener {
            isFreezeOn = !isFreezeOn
            service?.freezeFrame(isFreezeOn)
            binding.btnFreeze.text = if (isFreezeOn)
                getString(R.string.action_freeze_off)
            else
                getString(R.string.action_freeze_on)
        }

        binding.btnBuzz.setOnClickListener {
            service?.sendControl("buzz", mapOf("pattern" to listOf(0, 80, 50, 80)))
        }

        binding.btnCountdown.setOnClickListener { showCountdownPicker() }

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
            service?.setChatListener(null)
            service?.setEmojiListener(null)
            service?.setViolationListener(null)
            unbindService(serviceConnection)
            isBound = false
        }
        stopService(Intent(this, ScreenShareService::class.java))
    }

    // -----------------------------------------------------------------------
    // Countdown picker
    // -----------------------------------------------------------------------

    private fun showCountdownPicker() {
        val options = arrayOf("10 s", "30 s", "60 s")
        val seconds = intArrayOf(10, 30, 60)
        AlertDialog.Builder(this)
            .setTitle(R.string.title_pick_countdown)
            .setItems(options) { _, which ->
                service?.sendControl("countdown", mapOf("seconds" to seconds[which]))
            }
            .show()
    }

    // -----------------------------------------------------------------------
    // Chat helpers
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
        binding.scrollChat.post { binding.scrollChat.fullScroll(View.FOCUS_DOWN) }
    }

    // -----------------------------------------------------------------------
    // Floating emoji animation
    // -----------------------------------------------------------------------

    private fun showFloatingEmoji(emoji: String) {
        val container = binding.emojiContainer
        val tv = TextView(this).apply {
            text = emoji
            textSize = 36f
            gravity = Gravity.CENTER
        }
        val size = resources.getDimensionPixelSize(R.dimen.emoji_size)
        val lp = FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        container.addView(tv, lp)

        val translateY = ObjectAnimator.ofFloat(tv, View.TRANSLATION_Y, 0f, -container.height.toFloat())
        val alpha      = ObjectAnimator.ofFloat(tv, View.ALPHA, 1f, 0f)
        AnimatorSet().apply {
            playTogether(translateY, alpha)
            duration = 1500
            start()
        }
        tv.postDelayed({ (tv.parent as? ViewGroup)?.removeView(tv) }, 1600)
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

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

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(
            "${packageName}/${RestrictedAppsAccessibilityService::class.java.name}"
        )
    }

    companion object {
        const val EXTRA_RESULT_CODE     = "result_code"
        const val EXTRA_RESULT_DATA     = "result_data"
        const val EXTRA_SERVER_URL      = "server_url"
        const val EXTRA_SESSION_ID      = "session_id"
        const val EXTRA_PASSWORD        = "password"
        const val EXTRA_USE_MIC         = "use_mic"
        const val EXTRA_QUALITY         = "quality"
        const val EXTRA_TURN_URL        = "turn_url"
        const val EXTRA_TURN_USER       = "turn_user"
        const val EXTRA_TURN_PASS       = "turn_pass"
        const val EXTRA_RESTRICTED_PKGS = "restricted_pkgs"

        private const val REQ_RECORD_AUDIO = 1002
        private const val QR_SIZE_PX       = 400
    }
}
