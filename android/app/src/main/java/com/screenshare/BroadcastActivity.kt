package com.screenshare

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.screenshare.databinding.ActivityBroadcastBinding

/**
 * Shows the room ID and current streaming status while [ScreenShareService] runs in the
 * foreground.  Bound to the service so it can receive live status updates.
 */
class BroadcastActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBroadcastBinding
    private var service: ScreenShareService? = null
    private var isBound = false

    private val errorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val msg = intent.getStringExtra(ScreenShareService.EXTRA_ERROR_MESSAGE)
                ?: "Unknown error"
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val statusListener = ScreenShareService.StatusListener { status ->
        runOnUiThread { binding.tvStatus.text = status }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as ScreenShareService.LocalBinder).getService()
            service?.setStatusListener(statusListener)
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
        val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: ScreenShareService.DEFAULT_SERVER_URL

        val roomId = (1000..9999).random().toString()
        binding.tvRoomId.text = roomId

        // Listen for service error (e.g. missing MediaProjection permission).
        val filter = IntentFilter(ScreenShareService.ACTION_ERROR)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(errorReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(errorReceiver, filter)
        }

        // Start the foreground service that does the actual capturing.
        val serviceIntent = Intent(this, ScreenShareService::class.java).apply {
            putExtra(ScreenShareService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenShareService.EXTRA_RESULT_DATA, resultData)
            putExtra(ScreenShareService.EXTRA_ROOM_ID, roomId)
            putExtra(ScreenShareService.EXTRA_SERVER_URL, serverUrl)
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
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(errorReceiver)
        if (isBound) {
            service?.setStatusListener(null)
            unbindService(serviceConnection)
            isBound = false
        }
        // Stop the foreground service when the activity is finished.
        stopService(Intent(this, ScreenShareService::class.java))
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SERVER_URL = "server_url"
    }
}
