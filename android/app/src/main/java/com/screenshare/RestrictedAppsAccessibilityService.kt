package com.screenshare

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject

/**
 * Accessibility service with two responsibilities:
 *
 * 1. **Restricted-app monitor**: fires [ACTION_APP_VIOLATION] when the broadcaster opens
 *    a package in [restrictedPackages], then navigates back.
 *
 * 2. **Remote gesture injector**: listens for [ACTION_REMOTE_TOUCH] broadcasts sent by
 *    [ScreenShareService] when the viewer taps or swipes on the live video.  Converts the
 *    normalised coordinates into absolute screen pixels and injects the gesture via
 *    [dispatchGesture] (API 24+), so the viewer can navigate the broadcaster's device
 *    freely even when the lock overlay is visible.
 *
 * **Enabling:** the user must grant this service in Settings → Accessibility.
 * [BroadcastActivity] shows a prompt if the service is not enabled when a restricted-app
 * list is configured or when the viewer engages the lock.
 */
class RestrictedAppsAccessibilityService : AccessibilityService() {

    private val remoteTouchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
            val json = intent.getStringExtra(EXTRA_TOUCH_JSON) ?: return
            try {
                handleRemoteTouch(JSONObject(json))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse remote touch: $json", e)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter(ACTION_REMOTE_TOUCH)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(remoteTouchReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(remoteTouchReceiver, filter)
        }
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val restricted = restrictedPackages
        if (restricted.isEmpty()) return
        if (pkg == applicationContext.packageName) return
        if (pkg == "com.android.systemui") return

        if (restricted.contains(pkg)) {
            Log.w(TAG, "Restricted app opened: $pkg")
            val appName = try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(pkg, 0)
                ).toString()
            } catch (_: Exception) { pkg }

            sendBroadcast(Intent(ACTION_APP_VIOLATION).apply {
                putExtra(EXTRA_PKG, pkg)
                putExtra(EXTRA_APP_NAME, appName)
                `package` = applicationContext.packageName
            })
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(remoteTouchReceiver) }
    }

    // -----------------------------------------------------------------------
    // Remote gesture injection
    // -----------------------------------------------------------------------

    private fun handleRemoteTouch(obj: JSONObject) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels.toFloat()
        val screenH = dm.heightPixels.toFloat()

        val action = obj.optString("action", "tap")
        when (action) {
            "tap" -> {
                val x = (obj.optDouble("x", 0.5) * screenW).toFloat()
                val y = (obj.optDouble("y", 0.5) * screenH).toFloat()
                injectTap(x, y)
            }
            "swipe" -> {
                val x1 = (obj.optDouble("x1", 0.5) * screenW).toFloat()
                val y1 = (obj.optDouble("y1", 0.5) * screenH).toFloat()
                val x2 = (obj.optDouble("x2", 0.5) * screenW).toFloat()
                val y2 = (obj.optDouble("y2", 0.5) * screenH).toFloat()
                val duration = obj.optLong("duration", 300).coerceIn(50, 3000)
                injectSwipe(x1, y1, x2, y2, duration)
            }
        }
    }

    private fun injectTap(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, /* startTime= */ 0L, TAP_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun injectSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val stroke = GestureDescription.StrokeDescription(path, /* startTime= */ 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    companion object {
        const val ACTION_APP_VIOLATION = "com.screenshare.ACTION_APP_VIOLATION"
        const val ACTION_REMOTE_TOUCH  = "com.screenshare.ACTION_REMOTE_TOUCH"
        const val EXTRA_PKG            = "pkg"
        const val EXTRA_APP_NAME       = "app_name"
        const val EXTRA_TOUCH_JSON     = "touch_json"

        private const val TAG              = "RestrictedAppsSvc"
        private const val TAP_DURATION_MS  = 50L

        /**
         * Package names the broadcaster is not allowed to open.
         * Populated by [ScreenShareService] when a session starts.
         */
        @Volatile
        var restrictedPackages: Set<String> = emptySet()
    }
}
