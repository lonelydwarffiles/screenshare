package com.screenshare

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Manages a fullscreen [WindowManager] overlay that "blindfolds" the broadcaster when
 * the viewer/controller engages the lock.
 *
 * **Behaviour:**
 *  - The overlay is opaque black — the broadcaster cannot see their screen.
 *  - The overlay uses [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE] so all touch events
 *    (including gestures injected by the viewer via [RestrictedAppsAccessibilityService])
 *    pass through to the underlying apps.  This keeps the viewer in full remote control while
 *    the broadcaster is visually blinded.
 *
 * Requires [android.Manifest.permission.SYSTEM_ALERT_WINDOW].
 */
class LockOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null

    val isShowing: Boolean get() = overlayView != null

    /**
     * Shows the blindfold overlay.
     *
     * @param message Text shown briefly before the screen goes dark
     *                (e.g. "🔒 Locked by your controller").
     */
    fun show(message: String = context.getString(R.string.label_lock_overlay)) {
        if (isShowing) return
        if (!Settings.canDrawOverlays(context)) return

        val tv = TextView(context).apply {
            text = message
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // FLAG_NOT_TOUCHABLE: overlay does NOT intercept any touch events.
            // Touch events (including viewer-injected gestures) pass straight through
            // to whatever app is running beneath the overlay.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            windowManager.addView(tv, params)
            overlayView = tv
        } catch (e: Exception) {
            overlayView = null
        }
    }

    fun hide() {
        val v = overlayView ?: return
        try {
            windowManager.removeView(v)
        } catch (_: Exception) {}
        overlayView = null
    }

    fun toggle() {
        if (isShowing) hide() else show()
    }
}
