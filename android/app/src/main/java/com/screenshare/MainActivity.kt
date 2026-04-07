package com.screenshare

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.screenshare.databinding.ActivityMainBinding

/**
 * Entry point.  The user enters a server URL and broadcast options, then chooses to either
 * broadcast their screen or watch an existing broadcast.
 *
 * Also handles deep-links (screenshare://room/<id>) to open [ViewerActivity] directly.
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

        // Populate quality spinner
        val qualityLabels = resources.getStringArray(R.array.quality_labels)
        val adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_item, qualityLabels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerQuality.adapter = adapter
        binding.spinnerQuality.setSelection(WebRTCClient.Quality.MEDIUM.ordinal)

        binding.btnShare.setOnClickListener {
            @Suppress("DEPRECATION")
            startActivityForResult(
                mediaProjectionManager.createScreenCaptureIntent(),
                REQUEST_SCREEN_CAPTURE
            )
        }

        binding.btnWatch.setOnClickListener {
            startActivity(watchIntent())
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
            val quality = binding.spinnerQuality.selectedItemPosition
            val intent = Intent(this, BroadcastActivity::class.java).apply {
                putExtra(BroadcastActivity.EXTRA_RESULT_CODE, resultCode)
                putExtra(BroadcastActivity.EXTRA_RESULT_DATA, data)
                putExtra(BroadcastActivity.EXTRA_SERVER_URL, serverUrl())
                putExtra(BroadcastActivity.EXTRA_PASSWORD,
                    binding.etBroadcastPassword.text?.toString()?.trim()?.takeIf { it.isNotEmpty() })
                putExtra(BroadcastActivity.EXTRA_MAX_VIEWERS,
                    binding.etMaxViewers.text?.toString()?.trim()?.toIntOrNull() ?: 0)
                putExtra(BroadcastActivity.EXTRA_USE_KNOCK, binding.switchKnock.isChecked)
                putExtra(BroadcastActivity.EXTRA_USE_MIC,   binding.switchMic.isChecked)
                putExtra(BroadcastActivity.EXTRA_QUALITY, quality)
                putExtra(BroadcastActivity.EXTRA_TURN_URL,
                    binding.etTurnUrl.text?.toString()?.trim()?.takeIf { it.isNotEmpty() })
                putExtra(BroadcastActivity.EXTRA_TURN_USER,
                    binding.etTurnUser.text?.toString()?.trim()?.takeIf { it.isNotEmpty() })
                putExtra(BroadcastActivity.EXTRA_TURN_PASS,
                    binding.etTurnPass.text?.toString()?.trim()?.takeIf { it.isNotEmpty() })
            }
            startActivity(intent)
        }
    }

    private fun watchIntent() = Intent(this, ViewerActivity::class.java).apply {
        putExtra(ViewerActivity.EXTRA_SERVER_URL, serverUrl())
    }

    private fun serverUrl(): String =
        binding.etServerUrl.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: ScreenShareService.DEFAULT_SERVER_URL

    companion object {
        private const val REQUEST_SCREEN_CAPTURE = 1001
    }
}
