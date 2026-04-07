package com.screenshare

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.screenshare.databinding.ActivityMainBinding

/**
 * Entry point.  The broadcaster enters a custom session code (slug) and optional options,
 * then taps "Share My Screen".  The viewer taps "Watch a Stream" to open [ViewerActivity].
 *
 * Deep-links (`screenshare://session/<code>`) open [ViewerActivity] directly.
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
            val sessionId = binding.etSessionCode.text?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: generateFallbackCode()

            // Collect restricted-app package names (comma or newline separated).
            val restrictedRaw = binding.etRestrictedApps.text?.toString()?.trim() ?: ""
            val restrictedPkgs = ArrayList(
                restrictedRaw.split(Regex("[,\\n]+"))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            )

            val intent = Intent(this, BroadcastActivity::class.java).apply {
                putExtra(BroadcastActivity.EXTRA_RESULT_CODE, resultCode)
                putExtra(BroadcastActivity.EXTRA_RESULT_DATA, data)
                putExtra(BroadcastActivity.EXTRA_SERVER_URL, serverUrl())
                putExtra(BroadcastActivity.EXTRA_SESSION_ID, sessionId)
                putExtra(BroadcastActivity.EXTRA_PASSWORD,
                    binding.etPassword.text?.toString()?.trim()?.takeIf { it.isNotEmpty() })
                putExtra(BroadcastActivity.EXTRA_USE_MIC, binding.switchMic.isChecked)
                putExtra(BroadcastActivity.EXTRA_QUALITY, quality)
                putExtra(BroadcastActivity.EXTRA_TURN_URL,
                    binding.etTurnUrl.text?.toString()?.trim()?.takeIf { it.isNotEmpty() })
                putExtra(BroadcastActivity.EXTRA_TURN_USER,
                    binding.etTurnUser.text?.toString()?.trim()?.takeIf { it.isNotEmpty() })
                putExtra(BroadcastActivity.EXTRA_TURN_PASS,
                    binding.etTurnPass.text?.toString()?.trim()?.takeIf { it.isNotEmpty() })
                putStringArrayListExtra(BroadcastActivity.EXTRA_RESTRICTED_PKGS, restrictedPkgs)
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

    private fun generateFallbackCode(): String =
        (1000..9999).random().toString()

    companion object {
        private const val REQUEST_SCREEN_CAPTURE = 1001
    }
}
