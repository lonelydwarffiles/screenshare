package com.screenshare

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.screenshare.databinding.ActivityMainBinding

/**
 * Entry point.  The user enters a server URL and then chooses to either
 * broadcast their screen or watch an existing broadcast.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaProjectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        binding.btnShare.setOnClickListener {
            @Suppress("DEPRECATION")
            startActivityForResult(
                mediaProjectionManager.createScreenCaptureIntent(),
                REQUEST_SCREEN_CAPTURE
            )
        }

        binding.btnWatch.setOnClickListener {
            val intent = Intent(this, ViewerActivity::class.java).apply {
                putExtra(ViewerActivity.EXTRA_SERVER_URL, serverUrl())
            }
            startActivity(intent)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SCREEN_CAPTURE
            && resultCode == Activity.RESULT_OK
            && data != null
        ) {
            val intent = Intent(this, BroadcastActivity::class.java).apply {
                putExtra(BroadcastActivity.EXTRA_RESULT_CODE, resultCode)
                putExtra(BroadcastActivity.EXTRA_RESULT_DATA, data)
                putExtra(BroadcastActivity.EXTRA_SERVER_URL, serverUrl())
            }
            startActivity(intent)
        }
    }

    private fun serverUrl(): String =
        binding.etServerUrl.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: ScreenShareService.DEFAULT_SERVER_URL

    companion object {
        private const val REQUEST_SCREEN_CAPTURE = 1001
    }
}
